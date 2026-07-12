package fr.veripuce.app

/**
 * Collecteur mutable de diagnostic, passé en paramètre OPTIONNEL à [CnieReader.read]
 * (défaut null -> zéro impact fonctionnel, aucune collecte). Alimenté au fil de la
 * lecture (tentatives PACE, étapes, SW, durées, EF.CardAccess, DG présents).
 *
 * Ne reçoit et ne stocke JAMAIS de valeur brute : les libellés de clé sont déjà masqués
 * (« MRZ », « MRZ-candidat(pos=3) » — position seule, jamais un caractère —, « CAN ») et
 * les résultats sont des SW ou des
 * classes d'exception (jamais un message). Le rapport final est assemblé par
 * [DebugReports.build] à partir de ce collecteur.
 */
class DiagnosticsCollector {

    var nfcExtendedLength: Boolean? = null
    var nfcMaxTransceive: Int? = null
    var nfcTimeoutMs: Int? = null

    val cardAccessInfos = mutableListOf<String>()
    val attempts = mutableListOf<PaceAttemptReport>()
    var bacResult: String? = null
    var classification: String? = null
    var finalAccess: String? = null
    val dgPresent = mutableListOf<Int>()

    private var nextIndex = 1

    fun recordCardAccess(infos: List<PACEInfoDescriptor>) {
        cardAccessInfos.clear()
        cardAccessInfos += infos.map { "${DebugReports.paceName(it.oid)} param=${it.parameterId}" }
    }

    /** Enregistre une tentative d'ouverture (succès ou échec) — que des dérivés masqués. */
    fun recordAttempt(keyLabel: String, oid: String?, step: String, result: String, durationMs: Long) {
        attempts += PaceAttemptReport(nextIndex++, keyLabel, oid, step, result, durationMs)
    }

    /** Étape/résultat d'un échec dérivés de l'exception (jamais du message brut). */
    fun recordFailure(keyLabel: String, oid: String?, e: Throwable, durationMs: Long) {
        recordAttempt(keyLabel, oid, DebugReports.stepOf(e), DebugReports.resultOf(e), durationMs)
    }

    /** Description minimale d'un PACEInfo pour le rapport (OID + parameterId). */
    data class PACEInfoDescriptor(val oid: String, val parameterId: Any?)
}
