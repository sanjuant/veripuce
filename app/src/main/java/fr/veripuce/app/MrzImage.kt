package fr.veripuce.app

/**
 * Prétraitement image du crop MRZ pour fiabiliser l'OCR — 0 octet d'APK.
 *
 * Constat terrain : la MRZ de certaines CNIe est gravée laser en FAIBLE contraste. Sur un
 * crop couleur brut, le recognizer générique de ML Kit rate alors des glyphes, voire lit
 * « FRA » -> « LEF » et un numéro tout en lettres (plusieurs confusions d'un coup = image
 * d'entrée pauvre, PAS un défaut de connaissance de la police). D'autres cartes (mêmes
 * conditions) passent sans problème : la différence est le contraste physique de la bande.
 *
 * La parade la moins risquée : passer en niveaux de gris et ÉTIRER le contraste sur des
 * PERCENTILES robustes (insensibles aux quelques pixels de reflet spéculaire ou d'ombre qui
 * fausseraient un min/max brut). On ne binarise pas durement : ML Kit, entraîné sur des
 * scènes naturelles, lit mieux un gris franc qu'un noir/blanc dur (la binarisation dure sera
 * pertinente le jour où l'on passe à un moteur OCR-B type Tesseract).
 *
 * Cette classe ne contient QUE le calcul pur (LUT à partir d'un histogramme) -> testable sur
 * JVM sans Android (voir MrzImageTest). L'application de la LUT au Bitmap vit dans
 * [ScanActivity] (accès pixels Android).
 */
object MrzImage {

    /**
     * Construit une table de correspondance (LUT) `[0..255] -> [0..255]` qui étire le
     * contraste : la luminance au percentile bas [lowFraction] est mappée sur 0, celle au
     * percentile haut [highFraction] sur 255, linéairement entre les deux (saturé au-delà).
     *
     * Les percentiles (défaut 3 % / 97 %) écartent les pixels extrêmes — un reflet spéculaire
     * (quelques pixels à 255) ou une ombre (quelques pixels à 0) ne dilatent pas la plage et
     * n'écrasent donc pas le contraste des caractères.
     *
     * @param histogram histogramme de luminance à 256 classes (index = niveau de gris 0..255).
     * @return une LUT de 256 entrées. Identité si l'histogramme est vide ou quasi plat.
     */
    fun contrastLut(
        histogram: IntArray,
        lowFraction: Float = 0.03f,
        highFraction: Float = 0.97f,
    ): IntArray {
        require(histogram.size == 256) { "histogramme à 256 classes attendu" }
        var total = 0L
        for (c in histogram) total += c
        if (total <= 0L) return IntArray(256) { it }             // pas de données -> identité

        val lowTarget = (total * lowFraction).toLong()
        val highTarget = (total * highFraction).toLong()
        var lo = 0
        var hi = 255
        var cum = 0L
        var loSet = false
        for (v in 0..255) {
            cum += histogram[v]
            if (!loSet && cum > lowTarget) { lo = v; loSet = true }
            if (cum >= highTarget) { hi = v; break }
        }
        if (hi <= lo) return IntArray(256) { it }                // image plate -> identité

        val span = (hi - lo).toFloat()
        return IntArray(256) { v ->
            when {
                v <= lo -> 0
                v >= hi -> 255
                else -> (((v - lo) * 255f) / span + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }
}
