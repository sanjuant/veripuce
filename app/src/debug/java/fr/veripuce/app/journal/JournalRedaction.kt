package fr.veripuce.app.journal

import fr.veripuce.app.DebugReport
import fr.veripuce.app.PaceMapping
import java.util.Locale

/**
 * Caviardage du JOURNAL DES LECTURES (build debug uniquement). Point de passage UNIQUE entre
 * ce qui a été lu sur un document d'un TIERS et le texte écrit sur le téléphone.
 *
 * Anonyme par construction, en trois couches :
 *  1. **Liste blanche** : [render] n'écrit que des champs techniques choisis un à un
 *     (environnement, EF.CardAccess, tentatives, SW, durées, classification, verdicts
 *     booléens). Le rapport de diagnostic sait révéler jusqu'à 3 caractères du numéro
 *     (ambiguïtés/divergences), un motif et une empreinte : le journal n'en reprend AUCUN.
 *  2. **APDU sans charge utile** : [apduLine] ne garde que l'en-tête (CLA INS P1 P2), les
 *     LONGUEURS Lc/Le, le SW et la durée. Les octets de données (clés, nonces, MRZ, DG) ne
 *     sont jamais copiés : la fonction ne les lit même pas.
 *  3. **Filet de sécurité** : [scrub] remplace dans le texte final toute valeur sensible
 *     connue (numéro, dates, CAN, noms, MRZ) et, hors en-tête d'environnement, toute suite
 *     de 6+ chiffres, toute suite hexadécimale de 8+ caractères et toute ligne MRZ (`<<`).
 *     L'étiquette libre saisie par l'utilisateur passe par [label] (même filet).
 */
object JournalRedaction {

    const val SCHEMA = "veripuce-journal v1"

    /** Marque de remplacement d'une valeur caviardée. */
    const val MASK = "[x]"

    /** Longueur maximale de l'étiquette libre. */
    const val LABEL_MAX = 32

    /** Plafond de lignes APDU par entrée (une lecture complète en fait ~150). */
    const val MAX_TRACE_LINES = 400

    /** Une ligne de trace : APDU (en-tête seul) ou événement du tag. */
    sealed class TraceLine {
        abstract val atMs: Long

        data class Apdu(
            override val atMs: Long,
            val header: String,   // "0C B0 81 00 Lc=0 Le=256" — jamais un octet de données
            val result: String,   // "9000 rlen=223" / "TagLost" / "SW=0x6300"
            val durationMs: Long,
        ) : TraceLine()

        data class Event(override val atMs: Long, val name: String) : TraceLine()
    }

    /** Ce qu'une entrée du journal reçoit. Les [secrets] ne servent qu'à être effacés. */
    class Input(
        val label: String?,
        val cardIndex: Int,
        val tapIndex: Int,
        val buildType: String,
        val report: DebugReport,
        val attemptsFrom: Int,
        val trace: List<TraceLine>,
        val secrets: Collection<String?>,
    )

    /**
     * En-tête d'une commande APDU (ISO 7816-4, formes courte et étendue) SANS ses données,
     * et résultat de la réponse SANS ses données (SW + longueur).
     */
    fun apduLine(
        atMs: Long,
        command: ByteArray,
        sw: Int?,
        responseDataLength: Int?,
        error: String?,
        durationMs: Long,
    ): TraceLine.Apdu {
        val header = if (command.size < 4) "APDU(${command.size} o)" else buildString {
            append((0 until 4).joinToString(" ") { "%02X".format(Locale.ROOT, command[it].toInt() and 0xFF) })
            lengths(command)?.let { (lc, le) ->
                if (lc != null) append(" Lc=").append(lc)
                if (le != null) append(" Le=").append(le)
            } ?: append(" len=").append(command.size)
        }
        val result = when {
            error != null -> error
            sw != null -> "%04X".format(Locale.ROOT, sw and 0xFFFF) + (responseDataLength?.let { " rlen=$it" } ?: "")
            else -> "?"
        }
        return TraceLine.Apdu(atMs, header, result, durationMs)
    }

    /** (Lc, Le) d'une commande, ou null si l'encodage est incohérent. Le = 256/65536 si codé 0. */
    internal fun lengths(c: ByteArray): Pair<Int?, Int?>? {
        val n = c.size
        if (n == 4) return null to null                                     // cas 1
        val b4 = c[4].toInt() and 0xFF
        if (n == 5) return null to (if (b4 == 0) 256 else b4)               // cas 2S
        if (b4 != 0) {
            if (n == 5 + b4) return b4 to null                              // cas 3S
            if (n == 6 + b4) return b4 to ((c[n - 1].toInt() and 0xFF).let { if (it == 0) 256 else it }) // cas 4S
            return null
        }
        if (n < 7) return null
        val x = ((c[5].toInt() and 0xFF) shl 8) or (c[6].toInt() and 0xFF)
        if (n == 7) return null to (if (x == 0) 65536 else x)               // cas 2E
        if (n == 7 + x) return x to null                                    // cas 3E
        if (n == 9 + x) {                                                   // cas 4E
            val le = ((c[n - 2].toInt() and 0xFF) shl 8) or (c[n - 1].toInt() and 0xFF)
            return x to (if (le == 0) 65536 else le)
        }
        return null
    }

    /** Étiquette libre : une ligne, [LABEL_MAX] caractères, filet de sécurité appliqué. */
    fun label(raw: String?, secrets: Collection<String?>): String? {
        val oneLine = raw.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(WS, " ").trim().take(LABEL_MAX)
        if (oneLine.isEmpty()) return null
        return scrubPatterns(scrubSecrets(oneLine, secrets)).replace(DIGITS_LABEL, MASK)
    }

    /** Le texte d'une entrée, caviardé. C'est la SEULE sortie écrite dans le journal. */
    fun render(input: Input): String {
        val r = input.report
        val env = r.env
        // En-tête d'environnement : valeurs du téléphone (Build), pas de la carte -> seul le
        // filet « valeurs connues » s'y applique (un modèle « 2201116SG » resterait lisible).
        val head = buildString {
            appendLine("etiquette: ${label(input.label, input.secrets) ?: "-"}")
            appendLine("horodatage: ${env.timestampIso}")
            appendLine("app: ${env.appVersion} (${env.commit}) ${input.buildType}")
            appendLine("appareil: ${env.device} — Android ${env.androidRelease} (API ${env.apiLevel})")
        }
        // Corps : uniquement des champs techniques en liste blanche.
        val body = buildString {
            appendLine("carte: #${input.cardIndex} essai ${input.tapIndex}")
            appendLine("nfc: extendedLength=${r.nfcExtendedLength ?: "?"} maxTransceive=${r.nfcMaxTransceive ?: "?"} timeout=${r.nfcTimeoutMs ?: "?"}")
            if (r.docType != null) appendLine("document: type=${r.docType} etat=${r.issuingState ?: "?"} format=${r.format ?: "?"}")
            if (r.cardAccessInfos.isNotEmpty()) appendLine("cardaccess: [${r.cardAccessInfos.joinToString("] [")}]")
            for (a in r.attempts.drop(input.attemptsFrom.coerceAtLeast(0))) {
                appendLine(
                    "tentative ${a.index}: cle=${a.keyLabel} " +
                        (a.oid?.let { "mapping=${PaceMapping.of(it)} " } ?: "") +
                        "etape=${a.step} resultat=${a.result} duree=${"%.1f".format(Locale.ROOT, a.durationMs / 1000.0)}s"
                )
            }
            r.bacResult?.let { appendLine("bac: $it") }
            r.classification?.let { appendLine("classification: $it") }
            r.finalAccess?.let { appendLine("acces_final: $it") }
            if (r.dgPresent.isNotEmpty()) appendLine("dg_presents: ${r.dgPresent.joinToString(",")}")
            r.integrityOk?.let { appendLine("integrite: ${ok(it)}") }
            r.signature?.let { appendLine("signature: $it") }
            appendLine("coherence_scan_puce: ${r.coherenceOk?.let { ok(it) } ?: "non comparée"}")
            val lines = input.trace.sortedBy { it.atMs }
            appendLine("apdu: ${lines.count { it is TraceLine.Apdu }} échanges")
            for (t in lines.take(MAX_TRACE_LINES)) {
                val at = "+%.3fs".format(Locale.ROOT, t.atMs / 1000.0)
                when (t) {
                    is TraceLine.Apdu -> appendLine("  $at ${t.header} -> ${t.result} ${t.durationMs}ms")
                    is TraceLine.Event -> appendLine("  $at -- ${t.name}")
                }
            }
            if (lines.size > MAX_TRACE_LINES) appendLine("  (+${lines.size - MAX_TRACE_LINES} lignes tronquées)")
        }
        return (scrubSecrets(head, input.secrets) + scrubPatterns(scrubSecrets(body, input.secrets))).trimEnd()
    }

    /**
     * Efface toute occurrence (casse ignorée) d'une valeur sensible connue, ainsi que de
     * chacun de ses morceaux de 3+ caractères (prénoms séparés par `<` ou espace, lignes MRZ).
     */
    fun scrubSecrets(text: String, secrets: Collection<String?>): String {
        val tokens = secrets.asSequence().filterNotNull()
            .flatMap { s -> sequenceOf(s.trim()) + s.split('<', ' ', '\n', '-', ',').asSequence() }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .sortedByDescending { it.length }   // la valeur entière avant ses morceaux
            .toList()
        var out = text
        for (t in tokens) out = out.replace(t, MASK, ignoreCase = true)
        return out
    }

    /** Motifs génériques (valeurs inconnues) : chiffres, hexadécimal, MRZ. */
    fun scrubPatterns(text: String): String = text
        .replace(MRZ_LIKE, MASK)
        .replace(HEX_RUN, MASK)
        .replace(DIGIT_RUN, MASK)

    private fun ok(b: Boolean) = if (b) "OK" else "KO"

    private val WS = Regex("\\s+")
    /** Ligne ou fragment MRZ : au moins deux `<` consécutifs, avec ce qui les entoure. */
    private val MRZ_LIKE = Regex("[A-Za-z0-9<]*<<[A-Za-z0-9<]*")
    /** Hexadécimal collé (octets de charge utile) : 8+ caractères dont au moins un chiffre. */
    private val HEX_RUN = Regex("\\b(?=[0-9A-Fa-f]*[0-9])[0-9A-Fa-f]{8,}\\b")
    /** Dates AAMMJJ, CAN, numéros : 6+ chiffres consécutifs. */
    private val DIGIT_RUN = Regex("[0-9]{6,}")
    /** Dans l'étiquette, plus strict : 3+ chiffres (« carte 12 » reste lisible). */
    private val DIGITS_LABEL = Regex("[0-9]{3,}")
}
