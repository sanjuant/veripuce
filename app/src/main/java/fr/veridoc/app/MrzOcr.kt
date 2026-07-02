package fr.veridoc.app

/**
 * Extraction OCR des clés d'accès depuis le texte reconnu par ML Kit.
 *
 * - MRZ (passeport TD3, CNIe 2021 TD1, anciens formats TD2) : la MRZ est CONÇUE pour
 *   l'OCR — chaque champ utile (n° document, naissance, expiration) porte un chiffre de
 *   contrôle ICAO 9303. On n'accepte une lecture que si les trois checks passent, ce qui
 *   élimine quasi totalement les faux positifs.
 * - CAN (CNIe) : 6 chiffres imprimés au recto, sans checksum -> on exige en plus une
 *   stabilité inter-images côté appelant ([ScanActivity]).
 */
object MrzOcr {

    data class MrzData(
        val documentNumber: String,
        val dateOfBirth: String,   // AAMMJJ
        val dateOfExpiry: String,  // AAMMJJ
    )

    // TD3 (2 lignes de 44) et TD2 (2 lignes de 36) : doc(9) ck nat(3) ddn(6) ck sexe exp(6) ck.
    private val TD3_LINE2 = Regex("^([A-Z0-9<]{9})([0-9O])([A-Z<]{3})([0-9OIZSB]{6})([0-9O])([MF<X])([0-9OIZSB]{6})([0-9O])")

    // TD1 (3 lignes de 30, CNIe 2021) : ligne 1 = code doc(2) état(3) doc(9) ck ; ligne 2 = ddn(6) ck sexe exp(6) ck.
    private val TD1_LINE1 = Regex("^[ACI][A-Z<][A-Z<]{3}([A-Z0-9<]{9})([0-9O])")
    private val TD1_LINE2 = Regex("^([0-9OIZSB]{6})([0-9O])([MF<X])([0-9OIZSB]{6})([0-9O])")

    // Lookarounds alphanumériques : exclut les runs de 6 chiffres à l'intérieur d'un
    // n° de document type "F6G123456" (pas seulement les runs bordés de chiffres).
    private val CAN_6_DIGITS = Regex("(?<![0-9A-Z])[0-9]{6}(?![0-9A-Z])")

    /**
     * Cherche une MRZ valide (checks ICAO OK) dans le texte reconnu.
     * Renvoie null tant qu'aucune lecture n'est certaine — l'appelant réessaie sur
     * l'image suivante.
     */
    fun findMrz(rawText: String): MrzData? {
        val lines = rawText.uppercase()
            .split('\n')
            .map { it.replace(" ", "").replace("«", "<") }
            .filter { it.length >= 15 }

        // TD3/TD2 : tout est sur une seule ligne.
        for (line in lines) {
            val m = TD3_LINE2.find(line) ?: continue
            val doc = m.groupValues[1]
            val dob = fixDigits(m.groupValues[4])
            val exp = fixDigits(m.groupValues[7])
            if (checkOk(doc, m.groupValues[2]) && checkOk(dob, m.groupValues[5]) && checkOk(exp, m.groupValues[8]) &&
                plausibleDate(dob) && plausibleDate(exp)
            ) {
                return MrzData(doc.trimEnd('<'), dob, exp)
            }
        }

        // TD1 : n° document ligne 1, dates ligne 2 — exiger des lignes ADJACENTES
        // (même carte), chacune validée par ses chiffres de contrôle.
        for (i in 0 until lines.size - 1) {
            val m1 = TD1_LINE1.find(lines[i]) ?: continue
            val m2 = TD1_LINE2.find(lines[i + 1]) ?: continue
            val doc = m1.groupValues[1]
            val dob = fixDigits(m2.groupValues[1])
            val exp = fixDigits(m2.groupValues[4])
            if (checkOk(doc, m1.groupValues[2]) && checkOk(dob, m2.groupValues[2]) && checkOk(exp, m2.groupValues[5]) &&
                plausibleDate(dob) && plausibleDate(exp)
            ) {
                return MrzData(doc.trimEnd('<'), dob, exp)
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
