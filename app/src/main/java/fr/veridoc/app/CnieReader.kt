package fr.veridoc.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.tech.IsoDep
import com.gemalto.jp2.JP2Decoder
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import java.security.MessageDigest

/**
 * Lecture d'un document eMRTD (ICAO 9303) via NFC : CNIe française **ou** passeport.
 *
 * Flux : IsoDep -> CardService (SCUBA) -> PassportService (JMRTD) -> ouverture de session
 * (PACE-CAN pour la CNIe, PACE-MRZ / BAC pour le passeport) -> lecture des data groups
 * -> passive authentication (intégrité).
 *
 * NB : squelette d'architecture. Certaines signatures JMRTD/SCUBA varient selon la
 * version épinglée (voir README) — à réconcilier, l'ordre des appels étant stable.
 */
class CnieReader {

    fun read(isoDep: IsoDep, key: AccessKey): ReadResult {
        isoDep.timeout = 15_000

        // 1) Transport : envelopper l'IsoDep Android dans un CardService SCUBA.
        val cardService = CardService.getInstance(isoDep)
        var service: PassportService? = null
        try {
            cardService.open()

            val svc = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                /* isSFIEnabled = */ false,
                /* shouldCheckMAC = */ false,
            )
            service = svc
            svc.open()

            // 2) Ouverture de la session sécurisée selon le type de clé.
            when (key) {
                is AccessKey.Can -> openWithCan(svc, key.can)
                is AccessKey.Mrz -> openWithMrz(svc, key)
            }

            // 3) Lecture des data groups. On lit les octets BRUTS on-card une seule fois,
            //    puis on parse depuis ces mêmes octets. Important pour la passive auth :
            //    le SOD signe les octets bruts ; hacher une re-sérialisation (DGxFile.encoded)
            //    peut différer octet-pour-octet et faire échouer l'intégrité d'un vrai document.
            val dg1Bytes = svc.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            val dg2Bytes = svc.getInputStream(PassportService.EF_DG2, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            val dg1 = DG1File(dg1Bytes.inputStream())
            val dg2 = DG2File(dg2Bytes.inputStream())

            val mrz = dg1.mrzInfo

            // Photo : DG2 encode l'image en ISO 19794 (FaceInfo, documents actuels) ou
            // ISO 39794 (FaceImageDataBlock, passeports récents). Les deux exposent
            // l'interface ImageInfo -> extraction unifiée via subRecords (getFaceInfos()
            // est déprécié car aveugle au format 39794). Une photo absente/illisible ne
            // doit pas faire échouer la lecture -> firstOrNull + runCatching.
            val imageInfo: ImageInfo? = dg2.subRecords.asSequence()
                .flatMap { block ->
                    when (block) {
                        is FaceInfo -> block.faceImageInfos.asSequence()           // ISO 19794
                        is FaceImageDataBlock -> block.representationBlocks.asSequence() // ISO 39794
                        else -> emptySequence()
                    }
                }
                .firstOrNull()
            val photo: Bitmap? = imageInfo?.let { info ->
                runCatching {
                    val bytes = info.imageInputStream.readBytes()
                    // JPEG 2000 (cas usuel) via Gemalto, sinon repli JPEG/PNG natif.
                    runCatching { JP2Decoder(bytes).decode() }.getOrNull()
                        ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }

            // DG13 : spécifique France (adresse, taille, lieu de naissance). Absent d'un
            // passeport — le runCatching renverra alors simplement null.
            // NB : si EF_DG13 n'existe pas comme constante dans ta version, utilise 0x010D.
            val dg13Bytes: ByteArray? = runCatching {
                svc.getInputStream(PassportService.EF_DG13, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            }.getOrNull()
            val dg13: Dg13? = dg13Bytes?.let { runCatching { Dg13Parser.parse(it) }.getOrNull() }

            // 4) Passive authentication — le cœur anti-fraude.
            //    (a) intégrité : les hashs recalculés des DG (octets bruts) doivent
            //        correspondre à ceux, signés, stockés dans le SOD.
            val sod = SODFile(svc.getInputStream(PassportService.EF_SOD, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val integrityOk = verifyDataGroupHashes(
                sod,
                buildMap {
                    put(1, dg1Bytes)
                    put(2, dg2Bytes)
                    dg13Bytes?.let { put(13, it) }
                },
            )

            // (b) authenticité : vérifier la signature du SOD puis chaîner le DSC jusqu'à
            //     une CSCA de confiance (France : ANTS ; passeports : ICAO PKD / CSCA du
            //     pays émetteur). Nécessite un trust store CSCA.
            // TODO: sod.docSigningCertificate -> vérifier signature du SOD
            // TODO: valider la chaîne DSC -> CSCA depuis un KeyStore de CSCA de confiance
            val signatureVerified = false // tant que le trust store CSCA n'est pas branché

            return ReadResult(
                mrz = mrz.toString(),
                documentNumber = mrz.documentNumber,
                surname = mrz.primaryIdentifier,
                givenNames = mrz.secondaryIdentifier,
                dateOfBirth = mrz.dateOfBirth,
                nationality = mrz.nationality,
                photo = photo,
                dg13 = dg13,
                hashesMatchSod = integrityOk,
                sodSignatureVerified = signatureVerified,
            )
        } finally {
            // Fermer le canal dans tous les cas (succès comme échec) pour ne pas laisser
            // l'IsoDep/CardService ouverts — sinon les lectures suivantes peuvent se bloquer.
            // close() peut lui-même relever une exception -> on l'avale.
            runCatching { service?.close() }
            runCatching { cardService.close() }
        }
    }

    /** CNIe : PACE-CAN obligatoire. Le CAN = les 6 chiffres imprimés au recto. */
    private fun openWithCan(service: PassportService, can: String) {
        require(can.length == 6 && can.all { it.isDigit() }) { "Le CAN doit faire 6 chiffres." }

        // EF.CardAccess donne l'OID PACE et les paramètres de domaine (BrainpoolP256r1 sur la CNI).
        val cardAccess = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        val paceInfo = cardAccess.securityInfos.filterIsInstance<PACEInfo>().first()

        val canKey = PACEKeySpec.createCANKey(can)
        service.doPACE(
            canKey,
            paceInfo.objectIdentifier,
            PACEInfo.toParameterSpec(paceInfo.parameterId),
            null,
        )
        service.sendSelectApplet(true)
    }

    /**
     * Passeport : clé dérivée de la MRZ.
     *  - si la puce publie un PACEInfo (passeports récents) -> PACE-MRZ, pas de repli BAC
     *    (le BAC échouerait de toute façon et le message d'erreur PACE est plus parlant) ;
     *  - sinon (document réellement legacy, sans PACEInfo) -> BAC.
     */
    private fun openWithMrz(service: PassportService, mrz: AccessKey.Mrz) {
        val bacKey = BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)

        val cardAccess = runCatching {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        }.getOrNull()
        val paceInfo = cardAccess?.securityInfos?.filterIsInstance<PACEInfo>()?.firstOrNull()

        if (paceInfo != null) {
            val mrzKey = PACEKeySpec.createMRZKey(bacKey)
            service.doPACE(
                mrzKey,
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

    /** Recalcule le hash de chaque DG (octets bruts) et le compare au hash signé du SOD. */
    private fun verifyDataGroupHashes(sod: SODFile, dgRaw: Map<Int, ByteArray>): Boolean {
        val md = MessageDigest.getInstance(sod.digestAlgorithm) // ex. "SHA-256"
        val stored = sod.dataGroupHashes
        // isNotEmpty() : une map vide doit être un ÉCHEC (fail-closed), pas un "tout va bien".
        return dgRaw.isNotEmpty() && dgRaw.all { (num, bytes) ->
            md.reset()
            stored[num]?.contentEquals(md.digest(bytes)) == true
        }
    }
}
