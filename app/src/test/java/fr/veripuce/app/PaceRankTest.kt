package fr.veripuce.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordonnancement des protocoles PACE. `tryPace` abandonne dès un TagLost : c'est donc le
 * protocole classé EN TÊTE qui est réellement tenté à chaque tap. La rotation `preferIm`
 * doit donc faire passer IM devant GM — c'est ce qui donne sa chance à une carte qui gèle
 * en GM (voir le repli après échec côté UI).
 */
class PaceRankTest {

    private val root = CnieReader.OID_PACE
    private val ecdhGm = "$root.2.4"   // id-PACE-ECDH-GM-AES-CBC-CMAC-256
    private val dhGm = "$root.1.2"     // id-PACE-DH-GM-...
    private val ecdhIm = "$root.4.4"   // id-PACE-ECDH-IM-AES-CBC-CMAC-256
    private val ecdhCam = "$root.6.4"  // id-PACE-ECDH-CAM-...

    private fun order(oids: List<String>, preferIm: Boolean) =
        oids.sortedBy { CnieReader.paceRank(it, preferIm) }

    @Test
    fun `par defaut GM passe avant IM`() {
        assertTrue(CnieReader.paceRank(ecdhGm, preferIm = false) < CnieReader.paceRank(ecdhIm, preferIm = false))
        assertEquals(listOf(ecdhGm, ecdhIm), order(listOf(ecdhIm, ecdhGm), preferIm = false))
    }

    @Test
    fun `preferIm fait passer IM en tete`() {
        assertTrue(CnieReader.paceRank(ecdhIm, preferIm = true) < CnieReader.paceRank(ecdhGm, preferIm = true))
        assertEquals(listOf(ecdhIm, ecdhGm), order(listOf(ecdhGm, ecdhIm), preferIm = true))
    }

    @Test
    fun `CAM et inconnus restent apres GM et IM`() {
        assertTrue(CnieReader.paceRank(ecdhCam, preferIm = false) > CnieReader.paceRank(ecdhIm, preferIm = false))
        assertTrue(CnieReader.paceRank("1.2.3.4", preferIm = false) >= 3)
        // Le cas de la carte du terrain : GM et IM annoncés (param brainpool).
        assertEquals(listOf(ecdhIm, ecdhGm, ecdhCam), order(listOf(ecdhGm, ecdhCam, ecdhIm), preferIm = true))
        assertEquals(listOf(ecdhGm, ecdhIm, ecdhCam), order(listOf(ecdhCam, ecdhIm, ecdhGm), preferIm = false))
    }

    @Test
    fun `DH-GM compte comme GM`() {
        assertTrue(CnieReader.paceRank(dhGm, preferIm = false) < CnieReader.paceRank(ecdhIm, preferIm = false))
    }
}
