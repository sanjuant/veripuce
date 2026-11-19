package fr.veripuce.app

/**
 * Famille de « mapping » PACE (BSI TR-03110), dérivée de l'OID annoncé dans EF.CardAccess.
 * C'est l'unité de la rotation : une carte qui gèle en GM peut ouvrir en IM (échange plus court).
 */
enum class PaceMapping {
    GM, IM, CAM, OTHER;

    companion object {
        fun of(oid: String): PaceMapping = when {
            oid.startsWith("${CnieReader.OID_PACE}.1.") || oid.startsWith("${CnieReader.OID_PACE}.2.") -> GM
            oid.startsWith("${CnieReader.OID_PACE}.3.") || oid.startsWith("${CnieReader.OID_PACE}.4.") -> IM
            oid.startsWith("${CnieReader.OID_PACE}.6.") -> CAM
            else -> OTHER
        }
    }
}

/**
 * État de la rotation de mapping pour la clé MRZ d'UNE carte, conservé par l'UI d'un tap à
 * l'autre (réinitialisé à chaque nouvelle carte). Ne contient aucune donnée d'identité.
 */
class MrzMappingState {
    /** Mappings annoncés par la carte (lus dans EF.CardAccess au dernier tap). */
    val advertised = linkedSetOf<PaceMapping>()
    /** Mappings déjà essayés avec la clé MRZ et en échec (gel, erreur) — jamais re-tentés. */
    val tried = linkedSetOf<PaceMapping>()
    /** Tous les mappings qui ont échoué un jour sur cette carte (conservé après [resetForNextKey]). */
    val failedEver = linkedSetOf<PaceMapping>()
    /** La puce a refusé explicitement le mot de passe MRZ dès MSE:Set AT (6A80/6A88/6985). */
    var explicitRefusal = false

    /** Tous les mappings annoncés ont échoué avec la clé MRZ -> place au CAN. */
    fun allTried(): Boolean = advertised.isNotEmpty() && tried.containsAll(advertised)

    /** Nouveau candidat de clé (variante du numéro) : la rotation repart de zéro. */
    fun resetForNextKey() = tried.clear()
}

/**
 * Rotation des mappings PACE pour une clé, sans dépendance NFC (testable en JVM avec une
 * « fausse puce »). Règles :
 *  - chaque mapping est tenté AU PLUS UNE FOIS par lecture (ceux de [MrzMappingState.tried]
 *    sont sautés : ils ont échoué à un tap précédent) — aucune boucle possible ;
 *  - succès -> fin ;
 *  - 6300 / 63Cx (clé fausse) -> arrêt immédiat : changer de mapping ne corrige pas une clé
 *    fausse, et un 63Cx décrémente un compteur d'essais : on ne le provoque jamais deux fois ;
 *  - 6A80 / 6A88 / 6985 à MSE:Set AT (si [stopOnExplicitRefusal]) -> refus explicite du mot
 *    de passe, arrêt immédiat ;
 *  - gel (TagLost) -> on tente de reconnecter le tag ; s'il répond, mapping suivant dans le
 *    MÊME tap, sinon on rend la main (le tap suivant reprendra aux mappings restants) ;
 *  - autre erreur (tag toujours là) -> mapping suivant ;
 *  - budget de temps global : pas de nouveau mapping au-delà de [budgetMs] (reste pour le
 *    tap suivant).
 */
object PaceMappingRetry {

    sealed interface Outcome {
        data class Success(val mapping: PaceMapping) : Outcome
        /** Clé fausse (6300/63Cx). */
        data class KeyRefused(val error: Throwable) : Outcome
        /** Mot de passe refusé explicitement dès MSE:Set AT. */
        data class ExplicitRefusal(val error: Throwable) : Outcome
        /** Gel sans reconnexion possible, ou budget épuisé : d'autres mappings restent à essayer au tap suivant. */
        data class Interrupted(val error: Throwable) : Outcome
        /** Tous les mappings (restants) ont échoué. */
        data class Exhausted(val error: Throwable?) : Outcome
    }

    /**
     * Décision UI après un échec MRZ sur une carte qui a un CAN : le proposer seulement si la
     * MRZ a vraiment échoué — refus de clé ou refus explicite à MSE:Set AT ([keyRefused], porté
     * par ChipAccessException), tous les mappings annoncés essayés, ou trop de gels cumulés.
     */
    fun shouldOfferCan(keyRefused: Boolean, mappingsExhausted: Boolean, freezeCount: Int, freezeLimit: Int): Boolean =
        keyRefused || mappingsExhausted || freezeCount >= freezeLimit

    /** SW d'un refus explicite du mot de passe à MSE:Set AT (hypothèse 2). */
    private val EXPLICIT_REFUSAL_SW = setOf(0x6A80, 0x6A88, 0x6985)

    /** Refus explicite : SW 6A80/6A88/6985 à l'étape MSE:Set AT (étape JMRTD 0, ou inconnue). */
    fun isExplicitRefusal(e: Throwable): Boolean {
        val sw = DebugReports.swOf(e)
        if (sw !in EXPLICIT_REFUSAL_SW) return false
        val step = DebugReports.protocolStep(e)
        return step == null || step == 0
    }

    /**
     * @param infos       protocoles annoncés, déjà triés par préférence.
     * @param state       état MRZ de la carte (null pour le CAN : pas de mémoire inter-taps).
     * @param attempt     lance PACE sur un protocole ; lève en cas d'échec.
     * @param reconnect   tente de reconnecter le tag après un gel ; true si le tag répond.
     * @param onResult    trace de chaque tentative (protocole, erreur ou null, durée).
     * @param clock       horloge en ms (injectable pour les tests).
     */
    fun <T> run(
        infos: List<T>,
        oidOf: (T) -> String,
        state: MrzMappingState?,
        stopOnExplicitRefusal: Boolean,
        budgetMs: Long,
        attempt: (T) -> Unit,
        reconnect: () -> Boolean,
        onResult: (T, Throwable?, Long) -> Unit = { _, _, _ -> },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Outcome {
        state?.advertised?.apply { clear(); addAll(infos.map { PaceMapping.of(oidOf(it)) }) }
        val skip = state?.tried?.toSet().orEmpty()
        val todo = infos.filter { PaceMapping.of(oidOf(it)) !in skip }
        val begin = clock()
        var lastError: Throwable? = null
        for ((i, info) in todo.withIndex()) {
            if (i > 0 && clock() - begin >= budgetMs) {
                return Outcome.Interrupted(lastError ?: IllegalStateException("budget PACE épuisé"))
            }
            val mapping = PaceMapping.of(oidOf(info))
            val start = clock()
            try {
                attempt(info)
                onResult(info, null, clock() - start)
                return Outcome.Success(mapping)
            } catch (e: Exception) {
                onResult(info, e, clock() - start)
                state?.tried?.add(mapping)
                state?.failedEver?.add(mapping)
                when {
                    PaceError.isKeyRefused(e) -> return Outcome.KeyRefused(e)
                    stopOnExplicitRefusal && isExplicitRefusal(e) -> {
                        state?.explicitRefusal = true
                        return Outcome.ExplicitRefusal(e)
                    }
                    PaceError.isTagLost(e) -> {
                        val more = i < todo.lastIndex
                        if (!more) return Outcome.Exhausted(e)
                        if (!reconnect()) return Outcome.Interrupted(e)
                    }
                }
                lastError = e
            }
        }
        return Outcome.Exhausted(lastError)
    }
}
