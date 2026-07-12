package fr.veripuce.app

/**
 * Décision de lecture MRZ par VOTE PAR POSITION, en remplacement de « 2 trames
 * consécutives identiques ».
 *
 * « 2 trames identiques » ne filtre pas une erreur OCR intermittente : un « G » lu « 6 »
 * une trame sur trois peut apparaître deux fois de suite et être accepté. Le vote fait
 * converger chaque position du numéro de document vers le glyphe majoritaire.
 *
 * Si deux glyphes restent à égalité sur une position ET forment une paire aveugle au
 * chiffre de contrôle (G/6, L/1…), l'OCR ne peut pas trancher : on remonte les deux
 * numéros comme candidats PACE ([Decision.BlindPairConflict]) plutôt que d'en élire un
 * au hasard — ce que le lecteur essaiera de toute façon (cf. [MrzKeyCandidates]).
 */
class MrzVote(private val historySize: Int = 8) {

    private val readings = ArrayDeque<MrzOcr.MrzData>()

    sealed interface Decision {
        /** Numéro voté avec marge : lecture fiable. */
        data class Confident(val mrz: MrzOcr.MrzData) : Decision

        /** Numéro voté + numéros alternatifs (paires aveugles non tranchées) à tenter aussi. */
        data class BlindPairConflict(val mrz: MrzOcr.MrzData, val alternates: List<String>) : Decision
    }

    fun reset() = readings.clear()

    /**
     * Enregistre une lecture MRZ validée (checksums ICAO OK) et rend une décision si elle
     * est mûre, sinon null (continuer à scanner).
     *
     * @param minVotes voix minimales du glyphe leader d'une position.
     * @param margin écart minimal leader − rival pour trancher une position.
     */
    fun offer(mrz: MrzOcr.MrzData, minVotes: Int = 3, margin: Int = 2): Decision? {
        readings.addLast(mrz)
        while (readings.size > historySize) readings.removeFirst()

        // On ne vote qu'entre lectures du MÊME document (mêmes dates, type, état, longueur
        // de numéro) : sinon on mélangerait deux cartes passées devant l'objectif.
        val cohort = readings.filter {
            it.dateOfBirth == mrz.dateOfBirth && it.dateOfExpiry == mrz.dateOfExpiry &&
                it.docType == mrz.docType && it.issuingState == mrz.issuingState &&
                it.documentNumber.length == mrz.documentNumber.length
        }
        if (cohort.size < minVotes) return null

        val leaders = CharArray(mrz.documentNumber.length)
        val conflicts = mutableListOf<Pair<Int, Char>>()  // (position, glyphe rival)
        for (i in leaders.indices) {
            val ranked = cohort.groupingBy { it.documentNumber[i] }.eachCount()
                .entries.sortedByDescending { it.value }
            val leader = ranked[0]
            if (leader.value < minVotes) return null
            leaders[i] = leader.key

            val rival = ranked.getOrNull(1) ?: continue
            if (leader.value - rival.value >= margin) continue  // position tranchée nettement

            // Position serrée : tolérée SEULEMENT si les deux glyphes forment une paire
            // aveugle bien votée (ils donnent le même chiffre de contrôle -> deux candidats).
            val isBlindPair = MrzKeyCandidates.BLIND_SUBSTITUTIONS[leader.key]?.contains(rival.key) == true
            if (!isBlindPair || rival.value < minVotes) return null
            conflicts += i to rival.key
        }

        val voted = String(leaders)
        val mrzVoted = mrz.copy(documentNumber = voted)
        if (conflicts.isEmpty()) return Decision.Confident(mrzVoted)

        val alts = conflicts.map { (pos, rivalChar) ->
            voted.substring(0, pos) + rivalChar + voted.substring(pos + 1)
        }
        return Decision.BlindPairConflict(mrzVoted, alts)
    }
}
