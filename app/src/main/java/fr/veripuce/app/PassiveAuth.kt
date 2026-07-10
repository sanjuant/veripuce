package fr.veripuce.app

import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.lds.SODFile
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Passive authentication (ICAO 9303 partie 11) : ce qui prouve qu'un document est
 * intègre et émis par un État, indépendamment du transport NFC.
 *
 * Extrait de [CnieReader] pour être testable unitairement avec des données signées
 * par une PKI de test (cf. PassiveAuthTest) — la lecture NFC, elle, exige une puce.
 */
object PassiveAuth {

    /**
     * Vérifie la signature du SOD (CMS/PKCS#7) puis chaîne le DSC jusqu'à une CSCA de confiance.
     *
     * - Signature CMS invalide -> INVALID (document falsifié).
     * - Signature valide mais DSC ne remonte à aucune CSCA chargée -> VALID_UNTRUSTED.
     * - Signature valide + DSC signé par une CSCA de confiance -> TRUSTED (émis par l'État).
     */
    fun verifySodSignature(
        sodBytes: ByteArray,
        sod: SODFile,
        cscaCerts: Collection<X509Certificate>,
    ): SignatureCheck {
        val dsc = sod.docSigningCertificate ?: return SignatureCheck.NOT_CHECKED
        val sigOk = runCatching {
            // EF.SOD = tag 0x77 { ContentInfo CMS }. CMSSignedData attend la ContentInfo.
            val contentInfo = BerTlv.parse(sodBytes).firstOrNull { it.tag == 0x77 }?.value ?: sodBytes
            val cms = CMSSignedData(contentInfo)
            val signer = cms.signerInfos.signers.firstOrNull() ?: return SignatureCheck.NOT_CHECKED
            val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(dsc)
            // verify() contrôle la signature sur les signedAttrs ET que le message-digest
            // signé correspond au contenu (le LDSSecurityObject = les hashs des DG).
            signer.verify(verifier)
        }.getOrElse { return SignatureCheck.NOT_CHECKED }
        if (!sigOk) return SignatureCheck.INVALID
        return if (chainToTrustedCsca(dsc, cscaCerts)) SignatureCheck.TRUSTED else SignatureCheck.VALID_UNTRUSTED
    }

    /** Le DSC est-il signé par une CSCA de confiance (émetteur correspondant + signature valide) ? */
    private fun chainToTrustedCsca(dsc: X509Certificate, cscas: Collection<X509Certificate>): Boolean {
        val issuer = dsc.issuerX500Principal
        return cscas.any { csca ->
            csca.subjectX500Principal == issuer &&
                runCatching { dsc.verify(csca.publicKey) }
                    // Repli BouncyCastle : le provider Android par défaut ne gère pas
                    // certaines courbes (brainpool) utilisées par des CSCA.
                    .recoverCatching { dsc.verify(csca.publicKey, "BC") }
                    .isSuccess
        }
    }

    /** Recalcule le hash de chaque DG (octets bruts) et le compare au hash signé du SOD. */
    fun verifyDataGroupHashes(sod: SODFile, dgRaw: Map<Int, ByteArray>): Boolean {
        val md = MessageDigest.getInstance(sod.digestAlgorithm)
        val stored = sod.dataGroupHashes
        return dgRaw.isNotEmpty() && dgRaw.all { (num, bytes) ->
            md.reset()
            stored[num]?.contentEquals(md.digest(bytes)) == true
        }
    }
}
