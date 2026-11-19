package fr.veripuce.app

import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEException
import org.jmrtd.PACEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Chaîne MRZ scannée -> clé PACE-MRZ, sur des cartes TD1 de type CNIe FICTIVES (aucune vraie
 * donnée). Vérifie que la graine envoyée à la puce est exactement SHA-1 des caractères
 * IMPRIMÉS (n° + clé, naissance + clé, expiration + clé), y compris le remplissage '<'.
 */
class CnieMrzKeyTest {

    private fun cd(s: String) = MrzKeyCandidates.icaoCheckDigit(s).toString()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.US_ASCII))

    /** MRZ TD1 fictive de 3 lignes de 30 caractères (format CNIe 2021). */
    private fun td1(docField9: String, dob: String, exp: String): List<String> {
        require(docField9.length == 9)
        val l1 = ("IDFRA" + docField9 + cd(docField9)).padEnd(30, '<')
        val l2head = dob + cd(dob) + "F" + exp + cd(exp) + "FRA"
        val l2body = l2head.padEnd(29, '<')
        val composite = cd(l1.substring(5, 30) + l2body.substring(0, 7) + l2body.substring(8, 15) + l2body.substring(18, 29))
        val l3 = "SPECIMEN<<ALICE".padEnd(30, '<')
        return listOf(l1, l2body + composite, l3)
    }

    /** Graine attendue : relue directement aux positions ICAO 9303-5 de la MRZ imprimée. */
    private fun printedSeed(lines: List<String>): ByteArray =
        sha1(lines[0].substring(5, 15) + lines[1].substring(0, 7) + lines[1].substring(8, 15))

    private fun keyFromScan(text: String): PACEKeySpec {
        val mrz = MrzOcr.findMrz(text)!!
        return PACEKeySpec.createMRZKey(BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
    }

    @Test
    fun `ICAO 9303-11 worked example - seed is SHA-1 of number, dates and check digits`() {
        val seed = PACEKeySpec.createMRZKey(BACKey("T22000129", "640812", "101031")).key
        assertArrayEquals(sha1("T22000129364081251010318"), seed)
        assertEquals("7E2D2A41C74EA0B38CD36F863939BFA8E9032AAD", seed.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `TD1 CNIe - scanned key equals the printed MRZ characters, password ref MRZ`() {
        val lines = td1("T8MC4RW91", "900713", "310620")
        val key = keyFromScan(lines.joinToString("\n"))
        assertArrayEquals(printedSeed(lines), key.key)
        assertEquals(1.toByte(), key.keyReference)   // MRZ = 1 (CAN = 2)
    }

    @Test
    fun `TD1 CNIe - OCR noise (spaces, compressed filler) does not change the key`() {
        val lines = td1("T8MC4RW91", "900713", "310620")
        val noisy = lines[0].substring(0, 15) + "<<<\n" + lines[1].chunked(7).joinToString(" ") + "\n" + lines[2]
        assertArrayEquals(printedSeed(lines), keyFromScan(noisy).key)
    }

    @Test
    fun `TD1 - short number keeps its filler padding in the seed`() {
        val lines = td1("AB12<<<<<", "851130", "290228")
        val mrz = MrzOcr.findMrz(lines.joinToString("\n"))!!
        assertEquals("AB12", mrz.documentNumber)   // '<' retirés à l'affichage…
        assertArrayEquals(printedSeed(lines), keyFromScan(lines.joinToString("\n")).key)  // …re-paddés par JMRTD
    }

    @Test
    fun `diagnostic reports the exact JMRTD PACE step`() {
        // Mauvais mot de passe : la puce refuse le jeton d'authentification (GA étape 4).
        val wrongKey = PACEException("GA", 4, CardServiceException("GA", 0x6300))
        assertEquals("GA4-auth", DebugReports.stepOf(wrongKey))
        assertEquals("SW=0x6300", DebugReports.resultOf(wrongKey))
        // Mot de passe MRZ non accepté par la puce : refus dès MSE:Set AT.
        val noMrzPassword = PACEException("MSE", 0, CardServiceException("MSE", 0x6A88))
        assertEquals("MSE:Set AT", DebugReports.stepOf(noMrzPassword))
        assertEquals("SW=0x6A88", DebugReports.resultOf(noMrzPassword))
        // Sans étape JMRTD : déduction par le SW, comme avant.
        assertEquals("GA", DebugReports.stepOf(CardServiceException("x", 0x6300)))
        assertEquals("MSE:Set AT", DebugReports.stepOf(CardServiceException("x", 0x6A80)))
    }
}
