package fr.veripuce.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.tech.IsoDep
import com.gemalto.jp2.JP2Decoder
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature

/**
 * Lecture d'un document eMRTD (ICAO 9303) via NFC : CNIe française **ou** passeport.
 *
 * Flux : IsoDep -> CardService (SCUBA) -> PassportService (JMRTD) -> ouverture de session
 * (PACE-CAN pour la CNIe, PACE-MRZ / BAC pour le passeport) -> lecture des data groups
 * -> passive authentication (intégrité), cohérence MRZ optique/puce, détection de clone.
 */
class CnieReader {

    /**
     * @param expectedMrz MRZ lue optiquement (scan), pour la comparer à la puce (DG1).
     *                    null si aucune comparaison n'est demandée.
     */
    fun read(isoDep: IsoDep, key: AccessKey, expectedMrz: MrzOcr.MrzData? = null): ReadResult {
        isoDep.timeout = 15_000

        val cardService = CardService.getInstance(isoDep)
        var service: PassportService? = null
        try {
            cardService.open()

            val svc = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                /* isSFIEnabled = */ false,
                // shouldCheckMAC=true : la détection de clone par Chip Authentication REPOSE
                // sur la vérification du MAC de l'échange sous le SM issu de la CA. Une vraie
                // puce produit toujours un MAC valide (aucune régression), un clone échoue.
                /* shouldCheckMAC = */ true,
            )
            service = svc
            svc.open()

            // 1) Ouverture de la session sécurisée selon le type de clé.
            when (key) {
                is AccessKey.Can -> openWithCan(svc, key.can)
                is AccessKey.Mrz -> openWithMrz(svc, key)
            }

            // 2) Lecture des data groups sur octets BRUTS on-card (cf. passive auth).
            val dg1Bytes = readEf(svc, PassportService.EF_DG1)!!
            val dg2Bytes = readEf(svc, PassportService.EF_DG2)!!
            val dg1 = DG1File(dg1Bytes.inputStream())
            val dg2 = DG2File(dg2Bytes.inputStream())
            val mrz = dg1.mrzInfo

            val photo = extractPhoto(dg2)

            // DG13 (France), DG14 (Chip Authentication), DG15 (Active Authentication) :
            // optionnels selon le document. Lus en brut, inclus dans l'intégrité s'ils
            // existent (le SOD les référence).
            val dg13Bytes = readEf(svc, PassportService.EF_DG13)
            val dg13 = dg13Bytes?.let { runCatching { Dg13Parser.parse(it) }.getOrNull() }
            val dg14Bytes = readEf(svc, PassportService.EF_DG14)
            val dg15Bytes = readEf(svc, PassportService.EF_DG15)
            val dg14 = dg14Bytes?.let { runCatching { DG14File(it.inputStream()) }.getOrNull() }
            val dg15 = dg15Bytes?.let { runCatching { DG15File(it.inputStream()) }.getOrNull() }

            // 3) Passive authentication (intégrité) : hashs recalculés == hashs signés du SOD.
            val sod = SODFile(svc.getInputStream(PassportService.EF_SOD, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val integrityOk = verifyDataGroupHashes(
                sod,
                buildMap {
                    put(1, dg1Bytes)
                    put(2, dg2Bytes)
                    dg13Bytes?.let { put(13, it) }
                    dg14Bytes?.let { put(14, it) }
                    dg15Bytes?.let { put(15, it) }
                },
            )
            // TODO: signature du SOD + chaîne DSC -> CSCA de confiance (ANTS / ICAO PKD).
            val signatureVerified = false

            // 4) Cohérence MRZ imprimée (scan) <-> puce (DG1). PERTINENTE uniquement pour un
            //    accès CAN : la clé CAN est indépendante de la MRZ, donc un DG1 != scan est
            //    détectable. Pour un accès MRZ, la clé BAC/PACE dérive DÉJÀ de doc/naissance/
            //    expiration -> l'ouverture de session garantit l'égalité (contrôle tautologique,
            //    on n'affiche rien -> null).
            val mrzMatchesScan: Boolean? = when (key) {
                is AccessKey.Can -> expectedMrz?.let { exp ->
                    normDoc(mrz.documentNumber) == normDoc(exp.documentNumber) &&
                        mrz.dateOfBirth == exp.dateOfBirth &&
                        mrz.dateOfExpiry == exp.dateOfExpiry
                }
                is AccessKey.Mrz -> null
            }

            // 5) Détection de clone (en dernier : Chip Authentication réétablit le SM).
            val (cloneCheck, cloneMethod) = detectClone(svc, dg14, dg15)

            return ReadResult(
                mrz = mrz.toString(),
                documentNumber = mrz.documentNumber,
                surname = mrz.primaryIdentifier,
                givenNames = mrz.secondaryIdentifier,
                dateOfBirth = mrz.dateOfBirth,
                dateOfExpiry = mrz.dateOfExpiry,
                nationality = mrz.nationality,
                photo = photo,
                dg13 = dg13,
                hashesMatchSod = integrityOk,
                sodSignatureVerified = signatureVerified,
                mrzMatchesScan = mrzMatchesScan,
                cloneCheck = cloneCheck,
                cloneMethod = cloneMethod,
            )
        } finally {
            runCatching { service?.close() }
            runCatching { cardService.close() }
        }
    }

    /** Lit un EF en octets bruts, ou null s'il est absent/illisible (fichiers optionnels). */
    private fun readEf(svc: PassportService, fid: Short): ByteArray? =
        runCatching {
            svc.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
        }.getOrNull()

    private fun extractPhoto(dg2: DG2File): Bitmap? {
        val imageInfo: ImageInfo? = dg2.subRecords.asSequence()
            .flatMap { block ->
                when (block) {
                    is FaceInfo -> block.faceImageInfos.asSequence()              // ISO 19794
                    is FaceImageDataBlock -> block.representationBlocks.asSequence() // ISO 39794
                    else -> emptySequence()
                }
            }
            .firstOrNull()
        return imageInfo?.let { info ->
            runCatching {
                val bytes = info.imageInputStream.readBytes()
                runCatching { JP2Decoder(bytes).decode() }.getOrNull()
                    ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    /** CNIe : PACE-CAN obligatoire. Le CAN = les 6 chiffres imprimés au recto. */
    private fun openWithCan(service: PassportService, can: String) {
        require(can.length == 6 && can.all { it.isDigit() }) { "Le CAN doit faire 6 chiffres." }
        val cardAccess = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        val paceInfo = cardAccess.securityInfos.filterIsInstance<PACEInfo>().first()
        service.doPACE(
            PACEKeySpec.createCANKey(can),
            paceInfo.objectIdentifier,
            PACEInfo.toParameterSpec(paceInfo.parameterId),
            null,
        )
        service.sendSelectApplet(true)
    }

    /** Passeport (ou carte acceptant PACE-MRZ) : clé dérivée de la MRZ, repli BAC si legacy. */
    private fun openWithMrz(service: PassportService, mrz: AccessKey.Mrz) {
        val bacKey = BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
        val cardAccess = runCatching {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        }.getOrNull()
        val paceInfo = cardAccess?.securityInfos?.filterIsInstance<PACEInfo>()?.firstOrNull()

        if (paceInfo != null) {
            service.doPACE(
                PACEKeySpec.createMRZKey(bacKey),
                paceInfo.objectIdentifier,
                PACEInfo.toParameterSpec(paceInfo.parameterId),
                null,
            )
            service.sendSelectApplet(true)
        } else {
            service.sendSelectApplet(false)
            service.doBAC(bacKey)
        }
    }

    /**
     * Détection de puce clonée. Une puce authentique détient une clé privée NON extractible
     * qu'un clone (copie des seuls data groups) ne peut pas reproduire.
     *
     * - **Chip Authentication** (DG14, EAC-CA) : mécanisme moderne. On établit le canal CA
     *   puis on force un échange sous le nouveau Secure Messaging : son MAC n'est valide que
     *   si la puce a dérivé la même clé — donc que si elle détient la clé privée.
     * - **Active Authentication** (DG15) : repli. La puce signe un défi aléatoire. Vérifié de
     *   façon fiable ici pour les clés EC (ECDSA) ; conservateur sinon (jamais de fausse
     *   accusation : on ne conclut « authentique » que sur preuve, sinon « non vérifié »).
     */
    private fun detectClone(svc: PassportService, dg14: DG14File?, dg15: DG15File?): Pair<CloneCheck, String?> {
        // securityInfos (non déprécié) plutôt que les getters chipAuthentication* dépréciés.
        val secInfos = dg14?.securityInfos.orEmpty()
        secInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().firstOrNull()?.let { capki ->
            val pubKey = capki.subjectPublicKey
            val caInfos = secInfos.filterIsInstance<ChipAuthenticationInfo>()
            val caInfo = caInfos.firstOrNull { it.keyId == capki.keyId } ?: caInfos.firstOrNull()
            // (a) Établissement du canal CA. Un échec ici est INCONCLUSIF (algorithme/courbe
            //     non supportés, aléa NFC…) — jamais une preuve de clone.
            val established = runCatching {
                val oid = caInfo?.objectIdentifier ?: Util.inferProtocolIdentifier(pubKey)
                svc.doEACCA(capki.keyId, oid, Util.inferKeyAgreementAlgorithm(pubKey), pubKey)
                true
            }.getOrDefault(false)
            if (!established) return CloneCheck.UNSUPPORTED to "Chip Authentication (non établie)"
            // (b) Confirmation : lecture sous le SM issu de la CA. Avec shouldCheckMAC=true, le
            //     MAC n'est valide que si la puce détient la clé privée -> un échec ici = clone.
            val confirmed = runCatching {
                svc.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE).read()
                true
            }.getOrDefault(false)
            return (if (confirmed) CloneCheck.AUTHENTIC else CloneCheck.FAILED) to "Chip Authentication"
        }

        dg15?.publicKey?.let { aaKey ->
            val authentic = runCatching {
                if (!aaKey.algorithm.uppercase().contains("EC")) return@runCatching false
                val challenge = ByteArray(8).also { SecureRandom().nextBytes(it) }
                // La réponse (signature) ne dépend pas du digest passé à doAA : on l'obtient
                // une fois, puis on tente plusieurs profils de vérification (le document peut
                // imposer SHA-1/256/384/512) pour ne pas conclure à tort à un non-support.
                val aa = svc.doAA(aaKey, "SHA-256", "SHA256withPLAIN-ECDSA", challenge)
                sequenceOf("SHA256withPLAIN-ECDSA", "SHA1withPLAIN-ECDSA", "SHA384withPLAIN-ECDSA", "SHA512withPLAIN-ECDSA")
                    .any { alg ->
                        runCatching {
                            val sig = Signature.getInstance(alg, "BC")
                            sig.initVerify(aaKey); sig.update(challenge); sig.verify(aa.response)
                        }.getOrDefault(false)
                    }
            }.getOrDefault(false)
            // Conservateur : AA réussie = authentique ; sinon "non conclu" (jamais FAILED,
            // pour ne pas accuser à tort un vrai document sur un profil AA non couvert).
            return (if (authentic) CloneCheck.AUTHENTIC else CloneCheck.UNSUPPORTED) to "Active Authentication"
        }

        return CloneCheck.UNSUPPORTED to null
    }

    /** Recalcule le hash de chaque DG (octets bruts) et le compare au hash signé du SOD. */
    private fun verifyDataGroupHashes(sod: SODFile, dgRaw: Map<Int, ByteArray>): Boolean {
        val md = MessageDigest.getInstance(sod.digestAlgorithm)
        val stored = sod.dataGroupHashes
        return dgRaw.isNotEmpty() && dgRaw.all { (num, bytes) ->
            md.reset()
            stored[num]?.contentEquals(md.digest(bytes)) == true
        }
    }

    /** Normalise un n° de document pour comparaison (majuscules, sans '<' ni séparateurs). */
    private fun normDoc(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }
}
