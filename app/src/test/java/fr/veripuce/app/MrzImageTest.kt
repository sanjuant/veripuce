package fr.veripuce.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrzImageTest {

    @Test
    fun `étire une plage étroite sur toute la dynamique`() {
        // Luminances concentrées entre 100 et 150 : avec des percentiles extrêmes (0/1),
        // 100 -> 0, 150 -> 255, milieu au centre.
        val hist = IntArray(256)
        for (v in 100..150) hist[v] = 10
        val lut = MrzImage.contrastLut(hist, lowFraction = 0.0f, highFraction = 1.0f)
        assertEquals(0, lut[100])
        assertEquals(255, lut[150])
        assertTrue(lut[125] in 120..135, "milieu attendu ~128, obtenu ${lut[125]}")
    }

    @Test
    fun `histogramme vide donne une identité (pas de division par zéro)`() {
        val lut = MrzImage.contrastLut(IntArray(256))
        assertEquals(0, lut[0])
        assertEquals(128, lut[128])
        assertEquals(255, lut[255])
    }

    @Test
    fun `image plate (une seule luminance) donne une identité`() {
        val hist = IntArray(256)
        hist[128] = 1000
        val lut = MrzImage.contrastLut(hist)
        assertEquals(128, lut[128])
        assertEquals(64, lut[64])
    }

    @Test
    fun `les percentiles robustes ignorent reflets et ombres`() {
        // Masse principale entre 80 et 120 ; quelques pixels de reflet (255) et d'ombre (0)
        // ne doivent PAS dilater la plage : le blanc de la MRZ reste calé sur la masse haute.
        val hist = IntArray(256)
        for (v in 80..120) hist[v] = 100
        hist[0] = 5
        hist[255] = 5
        val lut = MrzImage.contrastLut(hist, lowFraction = 0.03f, highFraction = 0.97f)
        assertEquals(255, lut[120], "le haut de la masse doit saturer à 255 malgré le reflet")
        assertEquals(0, lut[80], "le bas de la masse doit tomber à 0 malgré l'ombre")
        // Un pixel de reflet reste blanc, pas d'inversion.
        assertEquals(255, lut[255])
    }

    @Test
    fun `la LUT est monotone croissante`() {
        val hist = IntArray(256)
        for (v in 40..210) hist[v] = 7
        val lut = MrzImage.contrastLut(hist)
        for (v in 1..255) {
            assertTrue(lut[v] >= lut[v - 1], "LUT non monotone en $v : ${lut[v-1]} -> ${lut[v]}")
        }
    }
}
