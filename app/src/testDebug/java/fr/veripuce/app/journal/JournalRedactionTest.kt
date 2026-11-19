package fr.veripuce.app.journal

import fr.veripuce.app.CloneCheck
import fr.veripuce.app.DebugEnv
import fr.veripuce.app.DebugReports
import fr.veripuce.app.DiagnosticsCollector
import fr.veripuce.app.MrzOcr
import fr.veripuce.app.ReadResult
import fr.veripuce.app.SignatureCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caviardage du journal des lectures. Propriété centrale : on injecte des valeurs SPÉCIMENS
 * (fictives : document ICAO 9303 « ERIKSSON ANNA MARIA », CAN inventé) partout où une lecture
 * peut en produire — scan, puce, clé, étiquette, charges utiles APDU — puis on cherche chacune
 * dans le texte produit : aucune ne doit y figurer.
 */
class JournalRedactionTest {

    // ---- Spécimens fictifs (ICAO 9303 partie 4/5, jamais une vraie personne) ----
    private val doc = "L898902C3"
    private val dob = "740812"
    private val exp = "120415"
    private val can = "482913"
    private val surname = "ERIKSSON"
    private val given = "ANNA<MARIA"
    private val mrzLine1 = "I<UTOL898902C36<<<<<<<<<<<<<<<"
    private val mrzLine2 = "7408122F1204159UTO<<<<<<<<<<<6"
    private val mrzLine3 = "ERIKSSON<<ANNA<MARIA<<<<<<<<<<"

    /** Charge utile fictive : contient le n° de document en ASCII + des octets « clé/nonce ». */
    private val payload = doc.toByteArray() + byteArrayOf(0x7E, 0x13, 0x5A, 0x2F, 0x6D, 0x44, 0x19, 0x3B)

    private val env = DebugEnv(
        appVersion = "0.1", commit = "a1b2c3d", timestampIso = "2026-11-19T21:04:05+01:00",
        device = "Google Pixel 8", androidRelease = "15", apiLevel = 35,
        ocrEngine = "tesseract", analysisResolution = "1280x720", rotationDegrees = 90, libs = "JMRTD 0.8.6",
    )

    private fun hex(b: ByteArray, sep: String) = b.joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

    private fun input(label: String?): JournalRedaction.Input {
        // Scan avec une erreur OCR (C lu G) -> le rapport de diagnostic révèlerait des caractères.
        val scanned = MrzOcr.MrzData("L898902G3", dob, exp, MrzOcr.DocType.ID_CARD, "UTO")
        val chip = ReadResult(
            mrz = "$mrzLine1\n$mrzLine2\n$mrzLine3", documentNumber = doc, surname = surname,
            givenNames = given.replace('<', ' '), dateOfBirth = dob, dateOfExpiry = exp, nationality = "UTO",
            photo = null, dg13 = null, hashesMatchSod = true, signature = SignatureCheck.TRUSTED,
            mrzMatchesScan = false, cloneCheck = CloneCheck.AUTHENTIC, cloneMethod = "Chip Authentication",
        )
        val diag = DiagnosticsCollector().apply {
            nfcExtendedLength = true; nfcMaxTransceive = 65279; nfcTimeoutMs = 6000
            recordCardAccess(listOf(DiagnosticsCollector.PACEInfoDescriptor("0.4.0.127.0.7.2.2.4.2.4", 13)))
            recordAttempt("MRZ", "0.4.0.127.0.7.2.2.4.2.4", "GA4-auth", "SW=0x6300", 1800)
            recordAttempt("-", null, "reconnexion", "OK", 120)
            recordAttempt("CAN", "0.4.0.127.0.7.2.2.4.2.4", "GA", "OK", 900)
            classification = "succès"; finalAccess = "CAN"; dgPresent += listOf(1, 2, 14)
        }
        val report = DebugReports.build(env, scanned, chip, diag, salt = "sel", includeExpiryYear = true)
        val trace = listOf(
            JournalRedaction.TraceLine.Event(0, "ouverture"),
            // MSE:Set AT, GA avec données, READ BINARY sous SM, réponse avec données.
            JournalRedaction.apduLine(5, byteArrayOf(0x00, 0x22, 0xC1.toByte(), 0xA4.toByte(), payload.size.toByte()) + payload, 0x9000, 0, null, 12),
            JournalRedaction.apduLine(30, byteArrayOf(0x10, 0x86.toByte(), 0x00, 0x00, payload.size.toByte()) + payload + byteArrayOf(0x00), 0x6300, 0, null, 1800),
            JournalRedaction.TraceLine.Event(1900, "reconnexion OK"),
            JournalRedaction.apduLine(2100, byteArrayOf(0x0C, 0xB0.toByte(), 0x81.toByte(), 0x00, 0x00), null, null, "TagLost", 6000),
        )
        return JournalRedaction.Input(
            label = label, cardIndex = 3, tapIndex = 2, buildType = "debug", report = report,
            attemptsFrom = 0, trace = trace,
            secrets = listOf(scanned.documentNumber, dob, exp, can, doc, surname, given, chip.mrz),
        )
    }

    private fun assertNoLeak(out: String) {
        val forbidden = listOf(doc, "L898902G3", dob, exp, can, surname, "ANNA", "MARIA", "UTOL898", "<<",
            "740812", "120415", "898902")
        for (f in forbidden) assertFalse("fuite '$f' dans :\n$out", out.contains(f, ignoreCase = true))
        // Aucun fragment de 3 caractères du n° de document (lu ou corrigé).
        for (d in listOf(doc, "L898902G3")) for (i in 0..d.length - 3) {
            assertFalse("fuite fragment '${d.substring(i, i + 3)}'", out.contains(d.substring(i, i + 3)))
        }
        // Aucune fenêtre de 3 octets de charge utile, en hexadécimal espacé ou collé.
        for (i in 0..payload.size - 3) {
            val w = payload.copyOfRange(i, i + 3)
            assertFalse("octets de charge utile ${hex(w, " ")}", out.contains(hex(w, " ")))
            assertFalse("octets de charge utile ${hex(w, "")}", out.contains(hex(w, "")))
        }
        // Les révélations du rapport de diagnostic (ambiguïtés, divergences, motif, empreinte).
        for (f in listOf("lu='", "scan='", "puce='", "motif=", "empreinte=", "annee_exp", "2012")) {
            assertFalse("champ hors liste blanche '$f'", out.contains(f))
        }
    }

    @Test
    fun `aucune valeur specimen ni charge utile dans une entree`() {
        val out = JournalRedaction.render(input(label = "carte 3"))
        assertNoLeak(out)
        // Le contenu technique attendu, lui, est bien là.
        assertTrue(out.contains("etiquette: carte 3"))
        assertTrue(out.contains("appareil: Google Pixel 8 — Android 15 (API 35)"))
        assertTrue(out.contains("app: 0.1 (a1b2c3d) debug"))
        assertTrue(out.contains("cardaccess: [PACE-ECDH-GM-AES-CBC-CMAC-256 param=13]"))
        assertTrue(out.contains("etape=GA4-auth resultat=SW=0x6300 duree=1.8s"))
        assertTrue(out.contains("etape=reconnexion resultat=OK"))
        assertTrue(out.contains("classification: succès"))
        assertTrue(out.contains("coherence_scan_puce: KO"))
        assertTrue(out.contains("00 22 C1 A4 Lc=17 -> 9000 rlen=0 12ms"))
        assertTrue(out.contains("10 86 00 00 Lc=17 Le=256 -> 6300"))
        assertTrue(out.contains("0C B0 81 00 Le=256 -> TagLost 6000ms"))
        assertTrue(out.contains("-- reconnexion OK"))
    }

    @Test
    fun `une etiquette qui contient des donnees personnelles est caviardee`() {
        for (label in listOf(
            "Eriksson anna 740812", "carte $doc", "can $can", mrzLine1, "né le 12/08/1974 ok",
        )) {
            val out = JournalRedaction.render(input(label))
            assertNoLeak(out)
            assertFalse("date en clair : $out", out.contains("1974"))
        }
    }

    @Test
    fun `etiquette - une ligne, longueur bornee, vide = absente`() {
        assertNull(JournalRedaction.label("  \n ", emptyList()))
        assertEquals("carte 3 bis", JournalRedaction.label("carte\n3   bis", emptyList()))
        assertEquals(JournalRedaction.LABEL_MAX, JournalRedaction.label("a".repeat(100), emptyList())!!.length)
    }

    @Test
    fun `filet generique - chiffres, hexadecimal, MRZ inconnus`() {
        val s = JournalRedaction.scrubPatterns("dob 900713 key 3F00A1B2C3D4 line X4RT<<ABC ok GA4 6300 0x9000")
        assertFalse(s.contains("900713")); assertFalse(s.contains("3F00A1B2C3D4")); assertFalse(s.contains("<<"))
        assertTrue(s.endsWith("ok GA4 6300 0x9000"))
    }

    @Test
    fun `en-tete APDU - formes courtes et etendues, jamais les donnees`() {
        fun h(vararg b: Int) = JournalRedaction.apduLine(0, b.map { it.toByte() }.toByteArray(), 0x9000, 4, null, 1).header
        assertEquals("00 84 00 00", h(0x00, 0x84, 0x00, 0x00))                                  // cas 1
        assertEquals("00 84 00 00 Le=8", h(0x00, 0x84, 0x00, 0x00, 0x08))                       // cas 2S
        assertEquals("00 B0 00 00 Le=256", h(0x00, 0xB0, 0x00, 0x00, 0x00))
        assertEquals("00 A4 02 0C Lc=2", h(0x00, 0xA4, 0x02, 0x0C, 0x02, 0x01, 0x1C))           // cas 3S
        assertEquals("00 88 00 00 Lc=2 Le=256", h(0x00, 0x88, 0x00, 0x00, 0x02, 0xAA, 0xBB, 0x00)) // cas 4S
        assertEquals("00 B1 00 00 Le=1024", h(0x00, 0xB1, 0x00, 0x00, 0x00, 0x04, 0x00))       // cas 2E
        assertEquals("00 2A 00 00 Lc=2", h(0x00, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB)) // cas 3E
        assertEquals("00 2A 00 00 Lc=2 Le=65536", h(0x00, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB, 0x00, 0x00)) // cas 4E
        assertEquals("00 2A 00 00 len=6", h(0x00, 0x2A, 0x00, 0x00, 0x05, 0xAA))                // incohérent
        assertEquals("APDU(2 o)", h(0x00, 0x2A))
        assertEquals("9000 rlen=4", JournalRedaction.apduLine(0, ByteArray(4), 0x9000, 4, null, 1).result)
    }

    @Test
    fun `seules les tentatives du tap courant sont ecrites`() {
        val base = input("x")
        val out = JournalRedaction.render(
            JournalRedaction.Input(base.label, 1, 2, "debug", base.report, attemptsFrom = 2, base.trace, base.secrets)
        )
        assertFalse(out.contains("tentative 1:")); assertFalse(out.contains("tentative 2:"))
        assertTrue(out.contains("tentative 3: cle=CAN"))
    }
}
