package fr.veripuce.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests du parseur MRZ/CAN.
 *
 * Valeurs de référence : spécimens officiels ICAO Doc 9303 (état fictif « Utopia ») —
 * doc L898902C3 (check 6), naissance 740812 (check 2), expiration 120415 (check 9),
 * TD1 : doc D23145890 (check 7).
 */
class MrzOcrTest {

    private val P = MrzOcr.DocType.PASSPORT
    private val ID = MrzOcr.DocType.ID_CARD

    // ---------- TD3 (passeport) ----------

    @Test
    fun `TD1 - donnees optionnelles imitant n° + cle ne font pas lire un passeport`() {
        // Ligne 1 fictive : optionnel « <<AB1234567 1 <<< » (clé de AB1234567 = 1) ; collé à
        // la ligne 2, il forme une séquence TD3 valide. Attendu : la carte, pas le passeport.
        val text = "IDUTOD231458907<<AB12345671<<<\n7408122F1204159UTO<<<<<<<<<<<6\nERIKSSON<<ANNA<MARIA<<<<<<<<<<"
        val mrz = MrzOcr.MrzData("D23145890", "740812", "120415", ID, "UTO")
        assertEquals(mrz, MrzOcr.findMrz(text))
        assertEquals(mrz, MrzOcr.findMrz(text.replace("\n", " ")))
    }

    @Test
    fun `TD3 - specimen ICAO propre`() {
        val text = """
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            L898902C36UTO7408122F1204159ZE184226B<<<<<10
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415", P, "UTO"), mrz)
    }

    @Test
    fun `TD3 - ligne 2 fragmentee par ML Kit (espaces et retours ligne)`() {
        // ML Kit découpe fréquemment la ligne MRZ en plusieurs "lignes"/éléments.
        val text = "P<UTOERIKSSON<<ANNA<MARIA<<<<\nL898902C36UTO 7408122F\n1204159ZE184226B<< 10"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415", P, "UTO"), mrz)
    }

    @Test
    fun `TD3 - confusions OCR en champ numerique (O pour 0, I pour 1)`() {
        val text = "L898902C36UTO74O8I22F12O4159ZE184226B<<<<<10"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415", P, "UTO"), mrz)
    }

    @Test
    fun `TD3 - numero de document type passeport francais`() {
        // Checks calculés : doc 12AB34567 -> 3, ddn 900112 -> 5, exp 300112 -> 3.
        val text = "12AB345673FRA9001125M3001123<<<<<<<<<<<<<<04"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("12AB34567", "900112", "300112", P, "FRA"), mrz)
    }

    @Test
    fun `TD3 - check digit invalide rejete`() {
        // Check de naissance faux (3 au lieu de 2) -> aucune lecture.
        val text = "L898902C36UTO7408123F1204159ZE184226B<<<<<10"
        assertNull(MrzOcr.findMrz(text))
    }

    @Test
    fun `TD3 - date implausible rejetee malgre un check coherent`() {
        // ddn 741315 (mois 13) : check recalculé valide (1) mais mois impossible.
        assertNull(MrzOcr.findMrz("L898902C36UTO7413151F1204159ZE184226B<<<<<10"))
    }

    // ---------- TD1 (CNIe 2021) ----------

    private val RP = MrzOcr.DocType.RESIDENCE_PERMIT

    @Test
    fun `TD1 - carte nationale (code ID) sur 3 lignes`() {
        // Code document "ID" = carte nationale -> ID_CARD (PACE-CAN).
        val text = """
            IDUTOD231458907<<<<<<<<<<<<<<<
            7408122F1204159UTO<<<<<<<<<<<6
            ERIKSSON<<ANNA<MARIA<<<<<<<<<<
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("D23145890", "740812", "120415", ID, "UTO"), mrz)
    }

    @Test
    fun `TD1 - fragmentee par ML Kit`() {
        val text = "IDUTOD231458907<<<<< <<<<<<<<<<\n7408122F 1204159UTO<<<<<<<<<<<6"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("D23145890", "740812", "120415", ID, "UTO"), mrz)
    }

    @Test
    fun `TD1 - titre de sejour (code IR net) est un RESIDENCE_PERMIT`() {
        // Code document "IR" (titre de séjour) -> clé MRZ, pas de CAN.
        val text = "IRFRAD231458907<<<<<<<<<<<<<<<\n7408122F1204159FRA<<<<<<<<<<6"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("D23145890", "740812", "120415", RP, "FRA"), mrz)
    }

    @Test
    fun `TD1 - specimen PRADO de la CNIe francaise 2021`() {
        // FRA-BO-03001 (spécimen officiel ANTS, MRZ réelle).
        val text = """
            IDFRAX4RTBPFW46<<<<<<<<<<<<<<<
            9007138F3002119FRA<<<<<<<<<<<6
            MARTIN<<MAELYS<GAELLE<MARIE<<<
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("X4RTBPFW4", "900713", "300211", ID, "FRA"), mrz)
    }

    @Test
    fun `TD1 - specimen PRADO du titre de sejour francais`() {
        // FRA-HO-09001 : code « IR », n° AGDREF 10 chiffres en données optionnelles.
        val text = """
            IRFRAK682T8YLO0<1105040043<<<<
            7906123F1401073SEN<<<<<<<<<<<6
            MARTIN<<CHRISTELLE<HELENE<LAUR
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("K682T8YLO", "790612", "140107", RP, "FRA"), mrz)
    }

    @Test
    fun `TD1 - angle mort du checksum ICAO - G lu 6 passe checkOk (documente le probleme)`() {
        // Construit une ligne 1 TD1 valide pour un numéro donné (dob/exp du spécimen PRADO).
        fun td1(doc: String): String {
            val c = MrzKeyCandidates.icaoCheckDigit(doc)
            val line1 = "IDFRA$doc$c".padEnd(30, '<')
            val line2 = "9007138F3002119FRA".padEnd(29, '<') + "6"
            return "$line1\n$line2"
        }
        val withG = MrzOcr.findMrz(td1("X4RTGPFW4"))
        val with6 = MrzOcr.findMrz(td1("X4RT6PFW4"))
        // Les DEUX lectures passent tous les chiffres de contrôle ICAO : le checksum ne
        // peut PAS distinguer un « G » lu « 6 ». C'est l'angle mort que compensent les
        // candidats PACE (MrzKeyCandidates) et le vote par position (MrzVote).
        assertEquals("X4RTGPFW4", withG?.documentNumber)
        assertEquals("X4RT6PFW4", with6?.documentNumber)
        assertTrue(MrzKeyCandidates.differOnlyByBlindPairs(withG!!.documentNumber, with6!!.documentNumber))
    }

    @Test
    fun `TD1 - code document avec confusion OCR (ID lu IO) reste une carte d'identite`() {
        // Le code document n'a AUCUN chiffre de contrôle : « ID » mal lu (D -> O/B/Q)
        // ne doit pas basculer une CNIe en titre de séjour (perte du repli CAN).
        for (bad in listOf("IO", "IB", "IQ", "I<")) {
            val text = "${bad}FRAX4RTBPFW46<<<<<<<<<<<<<<<\n9007138F3002119FRA<<<<<<<<<<<6"
            val mrz = MrzOcr.findMrz(text)
            assertEquals(ID as MrzOcr.DocType?, mrz?.docType)
        }
    }

    @Test
    fun `TD1 - dates hors fenetre de proximite rejetees`() {
        // Un doc TD1 valide mais des dates valides trop loin (autre document/frame).
        val filler = "<".repeat(80)
        assertNull(MrzOcr.findMrz("I<UTOD231458907$filler 7408122F1204159UTO<6"))
    }

    // ---------- Détection du type de document ----------

    @Test
    fun `type de document - passeport vs carte vs titre de sejour`() {
        val td3 = MrzOcr.findMrz("L898902C36UTO7408122F1204159ZE184226B<<<<<10")
        val idCard = MrzOcr.findMrz("IDFRAD231458907<<<<<<<<<<<<<<<\n7408122F1204159FRA<<<<<<<<<<6")
        val permit = MrzOcr.findMrz("IRFRAD231458907<<<<<<<<<<<<<<<\n7408122F1204159FRA<<<<<<<<<<6")
        assertEquals(P, td3?.docType)
        assertEquals(ID, idCard?.docType)
        assertEquals(RP, permit?.docType)
        assertEquals("FRA", idCard?.issuingState)
    }

    // ---------- Bruit / faux positifs ----------

    @Test
    fun `texte quelconque - aucune MRZ`() {
        assertNull(MrzOcr.findMrz("REPUBLIQUE FRANCAISE\nCARTE NATIONALE D'IDENTITE\n13 07 1990"))
    }

    // ---------- CAN ----------

    @Test
    fun `CAN - valeur isolee detectee`() {
        assertEquals("123456", MrzOcr.findCan("CARTE NATIONALE\nCAN 123456\nDUPONT"))
    }

    @Test
    fun `CAN - ligne de date ignoree (8 chiffres)`() {
        assertNull(MrzOcr.findCan("13 07 1990"))
    }

    @Test
    fun `CAN - run de 6 chiffres dans un numero de document ignore`() {
        // Bordé de lettres -> exclu par les lookarounds alphanumériques.
        assertNull(MrzOcr.findCan("DOC F6G123456"))
    }

    @Test
    fun `CAN - deux candidats distincts = ambigu`() {
        assertNull(MrzOcr.findCan("111111\n222222"))
    }

    // ---------- Diagnostic (mode diagnostic caméra) ----------

    @Test
    fun `diagnose - une MRZ valide est reconnue`() {
        assertEquals("MRZ reconnue ✓", MrzOcr.diagnose("L898902C36UTO7408122F1204159ZE184226B<<<<<10"))
    }

    @Test
    fun `diagnose - signale le champ dont le chiffre de controle echoue`() {
        // Même ligne mais chiffre de contrôle de naissance faux (3 au lieu de 2).
        val d = MrzOcr.diagnose("L898902C36UTO7408123F1204159ZE184226B<<<<<10")
        assertTrue(d.startsWith("TD3"), d)
        assertTrue(d.contains("n° OK"), d)
        assertTrue(d.contains("naissance KO"), d)
        assertTrue(d.contains("exp OK"), d)
    }

    @Test
    fun `diagnose - texte sans structure MRZ`() {
        assertTrue(MrzOcr.diagnose("123456789012345678901234567890").contains("aucune séquence MRZ"))
    }

    @Test
    fun `diagnose - trop peu de caracteres`() {
        assertTrue(MrzOcr.diagnose("ABC12").contains("trop peu"))
    }
}
