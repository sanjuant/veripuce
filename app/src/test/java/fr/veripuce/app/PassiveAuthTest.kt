package fr.veripuce.app

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.jmrtd.lds.SODFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Passive authentication de bout en bout sur des données SIGNÉES par une PKI de TEST
 * générée à la volée (CSCA de test -> DSC de test -> SOD signé par JMRTD) — le même
 * chemin de code que pour un vrai document, sans puce NFC.
 *
 * Ces certificats de test n'existent QUE dans la JVM du test : rien ne doit jamais
 * rejoindre le magasin embarqué assets/csca/ (cf. son README).
 */
class PassiveAuthTest {

    companion object {
        private lateinit var cscaKeys: KeyPair
        private lateinit var csca: X509Certificate
        private lateinit var dscKeys: KeyPair
        private lateinit var dsc: X509Certificate
        private lateinit var otherCsca: X509Certificate

        private val dg1 = ByteArray(93) { it.toByte() }        // contenus arbitraires :
        private val dg2 = ByteArray(4096) { (it * 7).toByte() } // seule l'empreinte compte

        @JvmStatic
        @BeforeClass
        fun setUpPki() {
            Security.addProvider(BouncyCastleProvider())
            cscaKeys = rsa()
            csca = certificate(
                subject = "CN=CSCA-TEST,O=Veripuce,C=UT",
                issuer = "CN=CSCA-TEST,O=Veripuce,C=UT",
                publicKey = cscaKeys.public,
                signingKey = cscaKeys.private,
                isCa = true,
            )
            dscKeys = rsa()
            dsc = certificate(
                subject = "CN=DS-TEST,O=Veripuce,C=UT",
                issuer = "CN=CSCA-TEST,O=Veripuce,C=UT",
                publicKey = dscKeys.public,
                signingKey = cscaKeys.private,
                isCa = false,
            )
            val other = rsa()
            otherCsca = certificate(
                subject = "CN=CSCA-AUTRE,O=Ailleurs,C=XX",
                issuer = "CN=CSCA-AUTRE,O=Ailleurs,C=XX",
                publicKey = other.public,
                signingKey = other.private,
                isCa = true,
            )
        }

        private fun rsa(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun certificate(
            subject: String,
            issuer: String,
            publicKey: java.security.PublicKey,
            signingKey: PrivateKey,
            isCa: Boolean,
        ): X509Certificate {
            val builder = JcaX509v3CertificateBuilder(
                X500Name(issuer),
                BigInteger.valueOf(System.nanoTime()),
                Date(1577836800000L),  // 2020-01-01
                Date(2208988800000L),  // 2040-01-01
                X500Name(subject),
                publicKey,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signingKey)
            return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
        }

        /** SOD signé par JMRTD (signedAttrs inclus), comme le ferait un État. */
        private fun buildSod(signingKey: PrivateKey = dscKeys.private): SODFile {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val hashes = mapOf(1 to sha256.digest(dg1), 2 to sha256.digest(dg2))
            return SODFile("SHA-256", "SHA256withRSA", hashes, signingKey, dsc)
        }
    }

    // ---- Intégrité (étape 2 de la passive authentication) ----

    @Test
    fun `integrite - les DG lus correspondent aux empreintes signees`() {
        assertTrue(PassiveAuth.verifyDataGroupHashes(buildSod(), mapOf(1 to dg1, 2 to dg2)))
    }

    @Test
    fun `integrite - un DG altere est detecte`() {
        val altered = dg1.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(PassiveAuth.verifyDataGroupHashes(buildSod(), mapOf(1 to altered, 2 to dg2)))
    }

    @Test
    fun `integrite - aucun DG fourni n'est jamais OK`() {
        assertFalse(PassiveAuth.verifyDataGroupHashes(buildSod(), emptyMap()))
    }

    // ---- Signature + chaîne CSCA (étape 3) ----

    @Test
    fun `signature - TRUSTED quand le DSC chaine vers une CSCA de confiance`() {
        val sod = buildSod()
        assertEquals(
            SignatureCheck.TRUSTED,
            PassiveAuth.verifySodSignature(sod.encoded, sod, listOf(csca)),
        )
    }

    @Test
    fun `signature - VALID_UNTRUSTED sans magasin CSCA`() {
        val sod = buildSod()
        assertEquals(
            SignatureCheck.VALID_UNTRUSTED,
            PassiveAuth.verifySodSignature(sod.encoded, sod, emptyList()),
        )
    }

    @Test
    fun `signature - VALID_UNTRUSTED avec une CSCA etrangere au document`() {
        val sod = buildSod()
        assertEquals(
            SignatureCheck.VALID_UNTRUSTED,
            PassiveAuth.verifySodSignature(sod.encoded, sod, listOf(otherCsca)),
        )
    }

    @Test
    fun `signature - INVALID quand la signature ne vient pas de la cle du DSC`() {
        // Un faussaire réutilise le certificat DSC légitime mais signe avec SA clé :
        // la signature CMS ne se vérifie pas contre la clé publique du DSC.
        val forged = buildSod(signingKey = cscaKeys.private)
        assertEquals(
            SignatureCheck.INVALID,
            PassiveAuth.verifySodSignature(forged.encoded, forged, listOf(csca)),
        )
    }

    @Test
    fun `signature - un SOD de confiance dont un DG est falsifie reste TRUSTED mais l'integrite echoue`() {
        // Les deux contrôles sont indépendants : un clone partiel (SOD authentique
        // rejoué avec des DG modifiés) est attrapé par l'INTÉGRITÉ, pas la signature.
        val sod = buildSod()
        val altered = dg2.copyOf().also { it[0] = 42 }
        assertEquals(SignatureCheck.TRUSTED, PassiveAuth.verifySodSignature(sod.encoded, sod, listOf(csca)))
        assertFalse(PassiveAuth.verifyDataGroupHashes(sod, mapOf(1 to dg1, 2 to altered)))
    }
}
