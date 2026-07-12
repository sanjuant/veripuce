package fr.veripuce.app

import net.sf.scuba.smartcards.CardServiceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caviardage et sérialisation du rapport de diagnostic. La propriété centrale : le
 * rapport sérialisé ne laisse fuir AUCUNE donnée personnelle brute.
 */
class DebugReportTest {

    private val env = DebugEnv(
        appVersion = "0.4.2", commit = "a1b2c3d",
        timestampIso = "2026-07-12T14:03:21+02:00",
        device = "Samsung SM-G991B", androidRelease = "14", apiLevel = 34,
        ocrEngine = "mlkit-text-recognition 16.0.1 (latin, bundled)",
        analysisResolution = "1920x1080", rotationDegrees = 90,
        libs = "JMRTD 0.8.6, scuba 0.0.26",
    )

    private fun chip(doc: String, coherent: Boolean) = ReadResult(
        mrz = "", documentNumber = doc, surname = "", givenNames = "",
        dateOfBirth = "", dateOfExpiry = "", nationality = "FRA",
        photo = null, dg13 = null, hashesMatchSod = true,
        signature = SignatureCheck.TRUSTED, mrzMatchesScan = coherent,
        cloneCheck = CloneCheck.AUTHENTIC, cloneMethod = "Chip Authentication",
    )

    private fun collector() = DiagnosticsCollector().apply {
        nfcExtendedLength = true; nfcMaxTransceive = 65279; nfcTimeoutMs = 15000
        recordCardAccess(listOf(DiagnosticsCollector.PACEInfoDescriptor("0.4.0.127.0.7.2.2.4.2.4", 14)))
        recordAttempt("MRZ", "0.4.0.127.0.7.2.2.4.2.4", "GA", "SW=0x6300", 1200)
        bacResult = "SW=0x6982"
        classification = "refus avéré (6300)"
        finalAccess = "CAN"
        dgPresent.addAll(listOf(1, 2, 13, 14))
    }

    // ---- Propriété de caviardage ----

    @Test
    fun `aucune donnee personnelle brute dans le rapport serialise`() {
        val rawDoc = "X4RT6PFW4"      // n° scanné (avec un 6, mal lu)
        val rawDob = "900713"
        val rawExp = "300211"
        val can = "482913"
        val scanned = MrzOcr.MrzData(rawDoc, rawDob, rawExp, MrzOcr.DocType.ID_CARD, "FRA")
        val report = DebugReports.build(
            env, scanned, chip("X4RTGPFW4", coherent = false), collector(),
            salt = "sel-de-test", includeExpiryYear = true,
        )
        val out = report.serialize()

        // Aucune sous-chaîne de >= 3 caractères consécutifs du n° brut.
        for (i in 0..rawDoc.length - 3) {
            val tri = rawDoc.substring(i, i + 3)
            assertFalse("fuite n° : '$tri' présent dans le rapport", out.contains(tri))
        }
        // Dates brutes et CAN totalement absents (seule l'année d'expiration est permise).
        assertFalse("date de naissance présente", out.contains(rawDob))
        assertFalse("date d'expiration présente", out.contains(rawExp))
        assertFalse("CAN présent", out.contains(can))
        // L'année d'expiration seule est autorisée.
        assertTrue("année d'expiration attendue", out.contains("annee_exp=2030"))
    }

    @Test
    fun `un numero entierement en paires aveugles ne reconstitue pas le numero`() {
        val scanned = MrzOcr.MrzData("GGGGGGGGG", "900101", "300101", MrzOcr.DocType.ID_CARD, "FRA")
        val out = DebugReports.build(env, scanned, null, null, "s", true).serialize()
        // Bornage : au plus quelques caractères révélés (jamais le numéro contigu).
        assertFalse(out.contains("GGGGGGGGG"))
        assertFalse(out.contains("GGG"))
        assertTrue("bornage des ambiguïtés attendu", out.contains("autres"))
    }

    @Test
    fun `budget global - le pire cas ne reconstitue pas le numero (audit anti-fuite)`() {
        // Scénario trouvé par l'audit adverse : numéro entièrement en paires aveugles +
        // scan très différent de la puce (repli CAN) -> divergences ET ambiguïtés partout.
        val scanned = MrzOcr.MrzData("G6L1S8C2D", "900101", "300101", MrzOcr.DocType.ID_CARD, "FRA")
        val out = DebugReports.build(env, scanned, chip("A7X3Y9Z5W", false), collector(), "s", true).serialize()
        // Un caractère RÉEL n'est révélé que par une ambiguïté (lu=) ou une divergence (puce=).
        val revealed = Regex("lu='").findAll(out).count() + Regex("puce='").findAll(out).count()
        assertTrue("trop de caractères révélés ($revealed) : reconstitution possible", revealed <= 3)
        // Au moins 6 des 9 positions restent inconnues.
        assertTrue(out.contains("autres"))
    }

    @Test
    fun `le motif du numero ne revele pas les caracteres`() {
        val out = DebugReports.build(
            env, MrzOcr.MrzData("X4RTBPFW4", "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA"),
            null, null, "s", false,
        ).serialize()
        assertTrue(out.contains("motif=L9LLLLLL9"))
        assertFalse(out.contains("X4RTBPFW4"))
    }

    // ---- Fonctions pures ----

    @Test
    fun `maskDocPattern classe lettres chiffres et remplissage`() {
        assertEquals("L9LLLLLL9", DebugReports.maskDocPattern("X4RTBPFW4"))
        assertEquals("LL<99", DebugReports.maskDocPattern("AB<12"))
        assertEquals("99999999", DebugReports.maskDocPattern("12345678"))
    }

    @Test
    fun `blindPairAlternative connait les paires aveugles`() {
        assertEquals('6', DebugReports.blindPairAlternative('G'))
        assertEquals('1', DebugReports.blindPairAlternative('L'))
        assertEquals(null, DebugReports.blindPairAlternative('X'))
    }

    @Test
    fun `diffPositions detecte ecarts multiples et longueurs differentes`() {
        assertEquals(listOf(2), DebugReports.diffPositions("ABCDE", "ABXDE"))
        assertEquals(listOf(0, 4), DebugReports.diffPositions("ABCDE", "XBCDX"))
        assertEquals(listOf(3, 4), DebugReports.diffPositions("ABC", "ABCDE"))
        assertEquals(listOf(3, 4), DebugReports.diffPositions("ABCDE", "ABC"))
        assertEquals(emptyList<Int>(), DebugReports.diffPositions("ABC", "ABC"))
    }

    @Test
    fun `swOf extrait le SW a travers la chaine de causes`() {
        assertEquals(0x6300, DebugReports.swOf(CardServiceException("GA", 0x6300)))
        assertEquals(0x6300, DebugReports.swOf(RuntimeException("x", CardServiceException("GA", 0x6300))))
        assertEquals(-1, DebugReports.swOf(RuntimeException("pas de SW")))
        assertEquals(-1, DebugReports.swOf(null))
    }

    // ---- Sérialisation ----

    @Test
    fun `serialisation stable et diff-able`() {
        val scanned = MrzOcr.MrzData("X4RT6PFW4", "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA")
        val a = DebugReports.build(env, scanned, chip("X4RTGPFW4", false), collector(), "s", true).serialize()
        val b = DebugReports.build(env, scanned, chip("X4RTGPFW4", false), collector(), "s", true).serialize()
        assertEquals(a, b)
        assertTrue(a.startsWith("veripuce-debug v1\n"))
    }

    @Test
    fun `le rapport tranche la cause OCR - divergence sur paire aveugle`() {
        val scanned = MrzOcr.MrzData("X4RT6PFW4", "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA")
        val out = DebugReports.build(env, scanned, chip("X4RTGPFW4", false), collector(), "s", true).serialize()
        assertTrue(out.contains("tentative 1: cle=MRZ"))
        assertTrue(out.contains("resultat=SW=0x6300"))
        assertTrue(out.contains("classification: refus avéré (6300)"))
        // La preuve directe de la cause 1 :
        assertTrue(out.contains("coherence_scan_puce: KO"))
        assertTrue(out.contains("scan='6' puce='G' (paire aveugle)"))
    }
}
