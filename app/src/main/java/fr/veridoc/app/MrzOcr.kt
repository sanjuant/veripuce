package fr.veridoc.app

/**
 * Extraction OCR des clés d'accès depuis le texte reconnu par ML Kit.
 *
 * - MRZ (passeport TD3, CNIe 2021 TD1, anciens formats TD2) : la MRZ est CONÇUE pour
 *   l'OCR — chaque champ utile (n° document, naissance, expiration) porte un chiffre de
 *   contrôle ICAO 9303. On n'accepte une lecture que si tous les checks passent, ce qui
 *   élimine quasi totalement les faux positifs.
 *
 *   ML Kit fragmente fréquemment une ligne MRZ en plusieurs « lignes » OCR (les blocs
 *   de `<` créent des coupures) : parser ligne à ligne ne matche alors jamais. On
 *   travaille donc sur le TEXTE JOINT (tous blocs concaténés, seuls [A-Z0-9<]
 *   conservés) avec une recherche NON ancrée — les chiffres de contrôle font office
 *   de filtre anti-faux-positif (≈1/1000 par champ, x3 champs, + plausibilité de date
 *   + stabilité inter-images côté [ScanActivity]).
 *
 * - CAN (CNIe) : 6 chiffres imprimés au recto, sans checksum -> détection ligne à
 *   ligne (valeur isolée) + stabilité inter-images côté appelant.
 */
object MrzOcr {

    data class MrzData(
        val documentNumber: String,
        val dateOfBirth: String,   // AAMMJJ
        val dateOfExpiry: String,  // AAMMJJ
    )

    // Classes tolérantes aux confusions OCR en position numérique (re-normalisées par
    // fixDigits avant validation) : O/Q->0, I->1, Z->2, S->5, B->8.
    private const val D = "[0-9OQIZSB]"

    // TD3 (passeport, ligne 2) et TD2 : doc(9) ck nat(3) ddn(6) ck sexe exp(6) ck.
    // Non ancré : recherché n'importe où dans le texte joint.
    private val TD3_SEQ = Regex("([A-Z0-9<]{9})($D)([A-Z<]{3})($D{6})($D)([MF<XK])($D{6})($D)")

    // TD1 (CNIe 2021) : « ligne 1 » = code doc(2) état(3) doc(9) ck ; « ligne 2 » =
    // ddn(6) ck sexe exp(6) ck. Appariés par fenêtre de proximité dans le texte joint.
    private val TD1_DOC = Regex("[ACI][A-Z<][A-Z<]{3}([A-Z0-9<]{9})($D)")
    private val TD1_DATES = Regex("($D{6})($D)([MF<XK])($D{6})($D)")

    private val CAN_6_DIGITS = Regex("(?<![0-9A-Z])[0-9]{6}(?![0-9A-Z])")

    /**
     * Cherche une MRZ valide (checks ICAO OK) dans le texte reconnu.
     * Renvoie null tant qu'aucune lecture n'est certaine — l'appelant réessaie sur
     * l'image suivante.
     */
    fun findMrz(rawText: String): MrzData? {
        // Texte joint : seuls les caractères MRZ survivent (espaces, retours ligne et
        // bruit OCR retirés) -> immunisé contre la fragmentation ML Kit.
        val joined = rawText.uppercase()
            .replace('«', '<')
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }

        // NB : balayage position par position (matchAt) et non findAll — findAll est
        // non chevauchant : un faux départ dans les '<' de la ligne 1 consommerait le
        // début de la vraie séquence et la ferait rater.

        // TD3/TD2 : séquence complète doc..exp, où qu'elle soit.
        for (i in joined.indices) {
            val m = TD3_SEQ.matchAt(joined, i) ?: continue
            val doc = m.groupValues[1]
            val dob = fixDigits(m.groupValues[4])
            val exp = fixDigits(m.groupValues[7])
            if (checkOk(doc, m.groupValues[2]) && checkOk(dob, m.groupValues[5]) && checkOk(exp, m.groupValues[8]) &&
                plausibleDate(dob) && plausibleDate(exp)
            ) {
                return MrzData(doc.trimEnd('<'), dob, exp)
            }
        }

        // TD1 : doc et dates viennent de segments distincts -> exiger que les dates
        // suivent le doc à distance plausible (ligne 1 = 30 chars, doc en position 5 :
        // les dates démarrent ~25 chars après le doc ; fenêtre large pour le bruit OCR).
        for (i in joined.indices) {
            val m1 = TD1_DOC.matchAt(joined, i) ?: continue
            val doc = m1.groupValues[1]
            if (!checkOk(doc, m1.groupValues[2])) continue
            val windowEnd = minOf(joined.length, m1.range.last + 46)
            for (j in (m1.range.last + 1) until windowEnd) {
                val m2 = TD1_DATES.matchAt(joined, j) ?: continue
                val dob = fixDigits(m2.groupValues[1])
                val exp = fixDigits(m2.groupValues[4])
                if (checkOk(dob, m2.groupValues[2]) && checkOk(exp, m2.groupValues[5]) &&
                    plausibleDate(dob) && plausibleDate(exp)
                ) {
                    return MrzData(doc.trimEnd('<'), dob, exp)
                }
            }
        }
        return null
    }

    /**
     * Cherche le CAN (6 chiffres, recto CNIe). Sans checksum, on n'accepte que si le
     * texte contient UNE seule valeur distincte à 6 chiffres (sinon ambigu -> null).
     */
    fun findCan(rawText: String): String? {
        val candidates = rawText.uppercase().split('\n')
            // Rejette les lignes de dates (« 13 07 1990 » = 8 chiffres) et de n° de
            // document : le CAN est imprimé isolé, sa ligne porte exactement 6 chiffres.
            .filter { line -> line.count { it.isDigit() } == 6 }
            .flatMap { line -> CAN_6_DIGITS.findAll(line).map { it.value } }
            .distinct()
        return candidates.singleOrNull()
    }

    /** Plausibilité d'une date AAMMJJ (mois 1-12, jour 1-31) — barrière anti-faux-positif. */
    private fun plausibleDate(d: String): Boolean {
        if (d.length != 6 || d.any { !it.isDigit() }) return false
        val mm = d.substring(2, 4).toInt()
        val dd = d.substring(4, 6).toInt()
        return mm in 1..12 && dd in 1..31
    }

    /** Chiffre de contrôle ICAO 9303 (pondération 7-3-1 ; 0-9, A-Z=10..35, '<'=0). */
    private fun checkDigit(field: String): Char? {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        field.forEachIndexed { i, c ->
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                '<' -> 0
                else -> return null
            }
            sum += v * weights[i % 3]
        }
        return '0' + (sum % 10)
    }

    private fun checkOk(field: String, check: String): Boolean =
        checkDigit(field) == fixDigits(check).singleOrNull()

    /** Confusions OCR usuelles dans un champ attendu NUMÉRIQUE (O/0, I/1, Z/2, S/5, B/8). */
    private fun fixDigits(s: String): String =
        s.map {
            when (it) {
                'O', 'Q' -> '0'
                'I' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'B' -> '8'
                else -> it
            }
        }.joinToString("")
}
