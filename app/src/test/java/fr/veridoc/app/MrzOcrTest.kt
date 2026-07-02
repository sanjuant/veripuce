package fr.veridoc.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests du parseur MRZ/CAN.
 *
 * Valeurs de référence : spécimens officiels ICAO Doc 9303 (état fictif « Utopia ») —
 * doc L898902C3 (check 6), naissance 740812 (check 2), expiration 120415 (check 9),
 * TD1 : doc D23145890 (check 7).
 */
class MrzOcrTest {

    // ---------- TD3 (passeport) ----------

    @Test
    fun `TD3 - specimen ICAO propre`() {
        val text = """
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            L898902C36UTO7408122F1204159ZE184226B<<<<<10
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415"), mrz)
    }

    @Test
    fun `TD3 - ligne 2 fragmentee par ML Kit (espaces et retours ligne)`() {
        // ML Kit découpe fréquemment la ligne MRZ en plusieurs "lignes"/éléments.
        val text = "P<UTOERIKSSON<<ANNA<MARIA<<<<\nL898902C36UTO 7408122F\n1204159ZE184226B<< 10"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415"), mrz)
    }

    @Test
    fun `TD3 - confusions OCR en champ numerique (O pour 0, I pour 1)`() {
        val text = "L898902C36UTO74O8I22F12O4159ZE184226B<<<<<10"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("L898902C3", "740812", "120415"), mrz)
    }

    @Test
    fun `TD3 - numero de document type passeport francais`() {
        // Checks calculés : doc 12AB34567 -> 3, ddn 900112 -> 5, exp 300112 -> 3.
        val text = "12AB345673FRA9001125M3001123<<<<<<<<<<<<<<04"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("12AB34567", "900112", "300112"), mrz)
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

    @Test
    fun `TD1 - specimen ICAO sur 3 lignes`() {
        val text = """
            I<UTOD231458907<<<<<<<<<<<<<<<
            7408122F1204159UTO<<<<<<<<<<<6
            ERIKSSON<<ANNA<MARIA<<<<<<<<<<
        """.trimIndent()
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("D23145890", "740812", "120415"), mrz)
    }

    @Test
    fun `TD1 - fragmentee par ML Kit`() {
        val text = "I<UTOD231458907<<<<< <<<<<<<<<<\n7408122F 1204159UTO<<<<<<<<<<<6"
        val mrz = MrzOcr.findMrz(text)
        assertEquals(MrzOcr.MrzData("D23145890", "740812", "120415"), mrz)
    }

    @Test
    fun `TD1 - dates hors fenetre de proximite rejetees`() {
        // Un doc TD1 valide mais des dates valides trop loin (autre document/frame).
        val filler = "<".repeat(80)
        assertNull(MrzOcr.findMrz("I<UTOD231458907$filler 7408122F1204159UTO<6"))
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
}
