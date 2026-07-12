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
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * Lecture d'un document eMRTD (ICAO 9303) via NFC : CNIe française **ou** passeport.
 *
 * Flux : IsoDep -> CardService (SCUBA) -> PassportService (JMRTD) -> ouverture de session
 * (PACE-CAN pour la CNIe, PACE-MRZ / BAC pour le passeport) -> lecture des data groups
 * -> passive authentication (intégrité), cohérence MRZ optique/puce, détection de clone.
 */
/**
 * Étapes d'une lecture, émises au fil de l'eau pour l'indicateur de progression.
 * [percent] : progression approximative (la photo domine le temps de lecture).
 */
enum class ReadStep(val percent: Int) {
    CONNECT(8),    // ouverture de session sécurisée (PACE/BAC)
    IDENTITY(22),  // DG1 (état civil / MRZ)
    PHOTO(38),     // DG2 (photo, le plus long)
    SECURITY(78),  // DG13/14/15 + EF.SOD
    VERIFY(92),    // passive authentication + anti-clone
}

class CnieReader {

    private companion object {
        /** Racine des OIDs PACE (BSI TR-03110) : .1/.2 = GM, .3/.4 = IM, .6 = CAM. */
        const val OID_PACE = "0.4.0.127.0.7.2.2.4"
    }

    /**
     * @param expectedMrz MRZ lue optiquement (scan), pour la comparer à la puce (DG1).
     *                    null si aucune comparaison n'est demandée.
     * @param onStep      callback de progression (appelé depuis le thread de lecture).
     * @param diag        collecteur de diagnostic OPTIONNEL (défaut null -> aucune collecte,
     *                    comportement de lecture strictement inchangé).
     */
    fun read(
        isoDep: IsoDep,
        key: AccessKey,
        expectedMrz: MrzOcr.MrzData? = null,
        cscaCerts: Collection<X509Certificate> = emptyList(),
        onStep: ((ReadStep) -> Unit)? = null,
        diag: DiagnosticsCollector? = null,
    ): ReadResult {
        isoDep.timeout = 15_000
        diag?.apply {
            nfcExtendedLength = runCatching { isoDep.isExtendedLengthApduSupported }.getOrNull()
            nfcMaxTransceive = runCatching { isoDep.maxTransceiveLength }.getOrNull()
            nfcTimeoutMs = 15_000
        }
        onStep?.invoke(ReadStep.CONNECT)

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

            // 1) Ouverture de la session sécurisée selon le type de clé. openWith* ne lève
            //    ChipAccessException QUE sur un refus de clé avéré (SW 6300) : un aléa
            //    transitoire (perte de contact, glitch NFC) remonte tel quel -> l'UI invite
            //    à re-présenter la carte sans proposer le CAN.
            when (key) {
                is AccessKey.Can -> openWithCan(svc, key.can, diag)
                is AccessKey.Mrz -> openWithMrz(svc, key, expectedMrz, diag)
            }

            // 2) Lecture des data groups sur octets BRUTS on-card (cf. passive auth).
            onStep?.invoke(ReadStep.IDENTITY)
            val dg1Bytes = readEf(svc, PassportService.EF_DG1)!!
            onStep?.invoke(ReadStep.PHOTO)
            val dg2Bytes = readEf(svc, PassportService.EF_DG2)!!
            val dg1 = DG1File(dg1Bytes.inputStream())
            val dg2 = DG2File(dg2Bytes.inputStream())
            val mrz = dg1.mrzInfo

            val photo = extractPhoto(dg2)

            // DG13 (France), DG14 (Chip Authentication), DG15 (Active Authentication) :
            // optionnels selon le document. Lus en brut, inclus dans l'intégrité s'ils
            // existent (le SOD les référence).
            onStep?.invoke(ReadStep.SECURITY)
            val dg13Bytes = readEf(svc, PassportService.EF_DG13)
            val dg13 = dg13Bytes?.let { runCatching { Dg13Parser.parse(it) }.getOrNull() }
            val dg14Bytes = readEf(svc, PassportService.EF_DG14)
            val dg15Bytes = readEf(svc, PassportService.EF_DG15)
            val dg14 = dg14Bytes?.let { runCatching { DG14File(it.inputStream()) }.getOrNull() }
            val dg15 = dg15Bytes?.let { runCatching { DG15File(it.inputStream()) }.getOrNull() }

            diag?.dgPresent?.apply {
                clear(); add(1); add(2)
                if (dg13Bytes != null) add(13)
                if (dg14Bytes != null) add(14)
                if (dg15Bytes != null) add(15)
            }

            // 3) Passive authentication.
            //    (a) intégrité : hashs recalculés == hashs signés du SOD.
            val sodBytes = svc.getInputStream(PassportService.EF_SOD, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            onStep?.invoke(ReadStep.VERIFY)
            val sod = SODFile(sodBytes.inputStream())
            val integrityOk = PassiveAuth.verifyDataGroupHashes(
                sod,
                buildMap {
                    put(1, dg1Bytes)
                    put(2, dg2Bytes)
                    dg13Bytes?.let { put(13, it) }
                    dg14Bytes?.let { put(14, it) }
                    dg15Bytes?.let { put(15, it) }
                },
            )
            //    (b) authenticité : signature du SOD par le DSC, DSC -> CSCA de confiance.
            val signature = PassiveAuth.verifySodSignature(sodBytes, sod, cscaCerts)

            // 4) Cohérence MRZ imprimée (scan) <-> puce (DG1), quel que soit le mode d'accès.
            //    Accès CAN : la clé est indépendante de la MRZ, la comparaison est le seul
            //    contrôle. Accès MRZ : l'ouverture de session prouve déjà la correspondance
            //    (une mauvaise clé serait refusée) — on la vérifie quand même contre DG1 et
            //    on l'AFFICHE : le conseiller doit voir cette garantie aussi sur un passeport.
            //    null uniquement si aucune MRZ de référence n'a été fournie.
            val mrzMatchesScan: Boolean? = expectedMrz?.let { exp ->
                normDoc(mrz.documentNumber) == normDoc(exp.documentNumber) &&
                    mrz.dateOfBirth == exp.dateOfBirth &&
                    mrz.dateOfExpiry == exp.dateOfExpiry
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
                signature = signature,
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

    /** CNIe : PACE-CAN. Le CAN = les 6 chiffres imprimés au recto. */
    private fun openWithCan(service: PassportService, can: String, diag: DiagnosticsCollector?) {
        require(can.length == 6 && can.all { it.isDigit() }) { "Le CAN doit faire 6 chiffres." }
        val paceInfos = readPaceInfos(service, diag)
        when (val outcome = tryPace(service, PACEKeySpec.createCANKey(can), "CAN", paceInfos, diag)) {
            PaceOutcome.Success -> { service.sendSelectApplet(true); diag?.finalAccess = "CAN" }
            is PaceOutcome.KeyRefused -> throw ChipAccessException(outcome.error)  // mauvais CAN
            is PaceOutcome.Failed -> throw outcome.error ?: IllegalStateException("PACE-CAN impossible")
        }
    }

    /**
     * Passeport / carte acceptant PACE-MRZ : clé dérivée de la MRZ.
     *
     * Pour une carte d'identité TD1, le numéro de document a pu être mal lu sur une paire
     * de glyphes aveugle au chiffre de contrôle (G/6, L/1…) — on retente donc PACE avec
     * des variantes du numéro avant de conclure au refus. BAC en dernier recours (jamais
     * pour une CNIe française, qui n'a pas de BAC).
     */
    private fun openWithMrz(
        service: PassportService,
        mrz: AccessKey.Mrz,
        expectedMrz: MrzOcr.MrzData?,
        diag: DiagnosticsCollector?,
    ) {
        val paceInfos = readPaceInfos(service, diag)
        // État émetteur si (et seulement si) le document est une carte d'identité TD1.
        val idCardState = expectedMrz?.takeIf { it.docType == MrzOcr.DocType.ID_CARD }?.issuingState
        val isIdCard = idCardState != null
        val isFrenchIdCard = idCardState.equals("FRA", ignoreCase = true)

        if (paceInfos.isEmpty()) {
            // Aucun PACE annoncé -> document ancien en BAC (jamais une CNIe FRA).
            service.sendSelectApplet(false)
            service.doBAC(BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
            diag?.finalAccess = "BAC"
            return
        }

        val candidates = if (isIdCard) {
            MrzKeyCandidates.documentNumberCandidates(mrz.documentNumber)
        } else {
            listOf(mrz.documentNumber)
        }

        var refusal: Throwable? = null
        for (docNumber in candidates) {
            // Libellé masqué : n° original -> "MRZ" ; variante -> "MRZ-candidat(pos=X)".
            val label = candidateLabel(docNumber, mrz.documentNumber)
            val key = PACEKeySpec.createMRZKey(BACKey(docNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
            when (val outcome = tryPace(service, key, label, paceInfos, diag)) {
                PaceOutcome.Success -> {
                    service.sendSelectApplet(true)
                    diag?.finalAccess = if (docNumber == mrz.documentNumber) "MRZ" else "MRZ-candidat"
                    return
                }
                is PaceOutcome.KeyRefused -> refusal = outcome.error   // clé fausse -> candidat suivant
                // Échec non-6300 (protocole/aléa) : les variantes n'y changeront rien,
                // on remonte tel quel -> l'UI invite à re-présenter la carte.
                is PaceOutcome.Failed -> throw outcome.error ?: IllegalStateException("PACE-MRZ impossible")
            }
        }

        // Tous les candidats MRZ refusés (6300). BAC en dernier recours, sauf CNIe FRA.
        if (!isFrenchIdCard) {
            val start = System.nanoTime()
            val bacError = runCatching {
                service.sendSelectApplet(false)
                service.doBAC(BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
                diag?.bacResult = "OK"
                diag?.finalAccess = "BAC"
                return
            }.exceptionOrNull()
            diag?.bacResult = bacError?.let { DebugReports.resultOf(it) }
        }
        throw ChipAccessException(refusal ?: IllegalStateException("PACE-MRZ refusé"))
    }

    /**
     * Libellé de tentative masqué : « MRZ » ou « MRZ-candidat(pos=X) ». On révèle la
     * POSITION retentée, jamais le caractère : sur une réussite par candidat, ce caractère
     * serait la valeur RÉELLE de la puce — canal de fuite à ne pas ouvrir.
     */
    private fun candidateLabel(docNumber: String, original: String): String {
        if (docNumber == original) return "MRZ"
        val i = docNumber.indices.firstOrNull { docNumber[it] != original.getOrNull(it) } ?: return "MRZ-candidat"
        return "MRZ-candidat(pos=$i)"
    }

    /** Issue d'une tentative PACE (multi-protocoles) pour une clé donnée. */
    private sealed interface PaceOutcome {
        object Success : PaceOutcome
        /** Clé refusée (SW 6300) : essayer un autre PROTOCOLE ne sert à rien, la clé est fausse. */
        data class KeyRefused(val error: Throwable) : PaceOutcome
        /** Aucun protocole n'a abouti sans que ce soit un refus de clé (protocole rejeté, aléa). */
        data class Failed(val error: Throwable?) : PaceOutcome
    }

    /**
     * Tente PACE sur chaque protocole annoncé (GM d'abord) pour UNE clé.
     * - succès -> [PaceOutcome.Success] ;
     * - 6300/63Cx -> [PaceOutcome.KeyRefused] immédiat (inutile de changer de protocole) ;
     * - rejet de protocole (MSE:Set AT) -> protocole suivant ;
     * - perte de contact NFC -> exception relancée telle quelle (transitoire).
     */
    private fun tryPace(
        service: PassportService,
        key: PACEKeySpec,
        keyLabel: String,
        paceInfos: List<PACEInfo>,
        diag: DiagnosticsCollector?,
    ): PaceOutcome {
        var lastError: Throwable? = null
        for (info in paceInfos) {
            val start = System.nanoTime()
            try {
                service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), null)
                diag?.recordAttempt(keyLabel, info.objectIdentifier, "GA", "OK", elapsedMs(start))
                return PaceOutcome.Success
            } catch (e: Exception) {
                diag?.recordFailure(keyLabel, info.objectIdentifier, e, elapsedMs(start))
                if (PaceError.isTagLost(e)) throw e
                if (PaceError.isKeyRefused(e)) return PaceOutcome.KeyRefused(e)
                lastError = e   // protocole rejeté ou aléa -> essayer le protocole suivant
            }
        }
        return PaceOutcome.Failed(lastError)
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun readPaceInfos(service: PassportService, diag: DiagnosticsCollector?): List<PACEInfo> {
        val cardAccess = runCatching {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        }.getOrNull() ?: return emptyList()
        // EF.CardAccess peut annoncer PLUSIEURS protocoles PACE, et JMRTD les stocke
        // dans un HashSet : « prendre le premier » est NON DÉTERMINISTE. On trie
        // explicitement — GM d'abord (le Generic Mapping est le mieux éprouvé dans
        // JMRTD ; ReadID et NFCPassportReader, GM-only, lisent la CNIe) — et on les
        // essaiera TOUTES en cas de refus.
        val infos = cardAccess.securityInfos.filterIsInstance<PACEInfo>().sortedBy { info ->
            when {
                info.objectIdentifier.startsWith("$OID_PACE.2.") -> 0 // ECDH-GM
                info.objectIdentifier.startsWith("$OID_PACE.1.") -> 1 // DH-GM
                info.objectIdentifier.startsWith("$OID_PACE.6.") -> 2 // ECDH-CAM
                else -> 3                                             // IM et autres
            }
        }
        diag?.recordCardAccess(
            infos.map { DiagnosticsCollector.PACEInfoDescriptor(it.objectIdentifier, it.parameterId) }
        )
        return infos
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

    /** Normalise un n° de document pour comparaison (majuscules, sans '<' ni séparateurs). */
    private fun normDoc(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }
}
