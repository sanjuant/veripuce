package fr.veripuce.app

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.lds.SODFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * Interopérabilité : passive authentication sur le jeu de référence OFFICIEL
 * BSI TR-03105-5 — des structures produites par un tiers (SOD signé RSASSA-PSS
 * par un DSC de test, DG réels dont un vrai DG2 de 15 Ko), pas par notre code.
 * Complète PassiveAuthTest, qui teste sur des données que nous générons.
 */
class BsiReferenceDataTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun bc() {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/bsi-tr03105/$name")!!.readBytes()

    private val sodBytes by lazy { res("EF_SOD.bin") }
    private val sod by lazy { SODFile(sodBytes.inputStream()) }

    @Test
    fun `le SOD de reference se parse (tag 0x77, RSASSA-PSS, DSC de test embarque)`() {
        assertEquals(0x77, sodBytes[0].toInt() and 0xFF)
        assertTrue(sod.docSigningCertificate.subjectX500Principal.name.contains("HJP"))
        assertEquals("SHA-256", sod.digestAlgorithm)
    }

    @Test
    fun `integrite - DG1, DG2 et DG14 officiels correspondent aux empreintes signees`() {
        val ok = PassiveAuth.verifyDataGroupHashes(
            sod,
            mapOf(
                1 to res("Datagroup1.bin"),
                2 to res("Datagroup2.bin"),
                14 to res("Datagroup14.bin"),
            ),
        )
        assertTrue(ok)
    }

    @Test
    fun `integrite - le DG15 du jeu BSI ne correspond pas au SOD (cas negatif reel)`() {
        val ok = PassiveAuth.verifyDataGroupHashes(
            sod,
            mapOf(1 to res("Datagroup1.bin"), 15 to res("Datagroup15.bin")),
        )
        assertFalse(ok)
    }

    @Test
    fun `integrite - un octet altere dans le DG2 officiel est detecte`() {
        val dg2 = res("Datagroup2.bin").also { it[5000] = (it[5000] + 1).toByte() }
        assertFalse(PassiveAuth.verifyDataGroupHashes(sod, mapOf(2 to dg2)))
    }

    @Test
    fun `signature - RSASSA-PSS verifiee, autorite non reconnue sans sa CSCA de test`() {
        // Le BSI ne publie pas la CSCA de test : la signature du SOD doit se vérifier
        // (RSASSA-PSS contre le DSC embarqué) mais la chaîne s'arrête là.
        assertEquals(
            SignatureCheck.VALID_UNTRUSTED,
            PassiveAuth.verifySodSignature(sodBytes, sod, emptyList()),
        )
    }

    @Test
    fun `signature - le magasin de production ne doit JAMAIS reconnaitre un DSC de test`() {
        // Garde-fou : si ce test échoue, un certificat de test a rejoint assets/csca/.
        val dir = java.io.File("src/main/assets/csca")
            .takeIf { it.isDirectory } ?: java.io.File("app/src/main/assets/csca")
        val store = dir.listFiles().orEmpty()
            .filter { it.extension == "pem" }
            .flatMap { CscaStore.parse(it.readBytes()) }
        assertTrue("magasin de production introuvable ou vide", store.size > 700)
        assertEquals(
            SignatureCheck.VALID_UNTRUSTED,
            PassiveAuth.verifySodSignature(sodBytes, sod, store),
        )
    }
}
