package fr.veripuce.app

import net.sf.scuba.smartcards.CardServiceException
import java.security.MessageDigest

/**
 * Rapport de diagnostic technique CAVIARDÉ pour le debug des replis CAN.
 *
 * Principe non négociable : le rapport est caviardé À LA CONSTRUCTION. [DebugReport] ne
 * stocke QUE des dérivés masqués (motifs, booléens, empreinte, SW…), jamais les valeurs
 * brutes (numéro complet, dates, CAN, nom, MRZ, texte OCR, photo, DG13). Il n'existe
 * aucune fonction de dé-caviardage. Seule exception, assumée et documentée : les
 * caractères aux positions AMBIGUËS ou DIVERGENTES (quelques-uns au maximum), révélés
 * car indispensables au debug des paires aveugles au chiffre de contrôle ICAO.
 */

const val DEBUG_SCHEMA = "veripuce-debug v1"

/** Caractère ambigu du numéro (paire aveugle au chiffre de contrôle) — position + glyphe révélés. */
data class AmbiguousChar(val position: Int, val read: Char, val alternative: Char?, val confidence: Float?)

/** Divergence scan↔puce à une position : preuve directe d'une erreur OCR (cause 1). */
data class Divergence(val position: Int, val scanChar: Char, val chipChar: Char, val blindPair: Boolean)

/** Une tentative d'ouverture de session. Jamais de valeur brute — que des dérivés. */
data class PaceAttemptReport(
    val index: Int,
    val keyLabel: String,   // "MRZ" / "MRZ-candidat(pos=3)" / "CAN" — position seule, jamais un caractère
    val oid: String?,
    val step: String,       // "CardAccess" / "MSE:Set AT" / "GA" / "select applet" / "BAC"
    val result: String,     // "OK" / "SW=0x6300" / "TagLost" / "IOException"
    val durationMs: Long,
)

/** Contexte d'environnement (aucune donnée personnelle). */
data class DebugEnv(
    val appVersion: String,
    val commit: String,
    val timestampIso: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val ocrEngine: String,
    val analysisResolution: String?,
    val rotationDegrees: Int?,
    val libs: String,
)

data class DebugReport(
    val env: DebugEnv,
    // NFC
    val nfcExtendedLength: Boolean?,
    val nfcMaxTransceive: Int?,
    val nfcTimeoutMs: Int?,
    // scan
    val docType: String?,
    val issuingState: String?,
    val format: String?,
    val docPattern: String?,
    val docCheckOk: Boolean?,
    val fingerprint: String?,
    val ambiguities: List<AmbiguousChar>,
    val ambiguitiesTruncated: Int,
    val dobCheckOk: Boolean?,
    val expCheckOk: Boolean?,
    val datesPlausible: Boolean?,
    val expiryYear: String?,
    // accès puce
    val cardAccessInfos: List<String>,
    val attempts: List<PaceAttemptReport>,
    val bacResult: String?,
    val classification: String?,
    val finalAccess: String?,
    // post-lecture
    val dgPresent: List<Int>,
    val integrityOk: Boolean?,
    val signature: String?,
    val coherenceOk: Boolean?,
    val divergences: List<Divergence>,
    val divergencesTruncated: Int,
) {
    /** Sérialisation texte STABLE (ordre et format fixes -> diff-able entre deux rapports). */
    fun serialize(): String = buildString {
        appendLine(DEBUG_SCHEMA)
        appendLine("horodatage: ${env.timestampIso}")
        appendLine("app: ${env.appVersion} (${env.commit})")
        appendLine("appareil: ${env.device} — Android ${env.androidRelease} (API ${env.apiLevel})")
        appendLine("nfc: extendedLength=${nfcExtendedLength ?: "?"} maxTransceive=${nfcMaxTransceive ?: "?"} timeout=${nfcTimeoutMs ?: "?"}")
        appendLine("ocr: ${env.ocrEngine}, analyse ${env.analysisResolution ?: "?"}, rot=${env.rotationDegrees ?: "?"}")
        appendLine("libs: ${env.libs}")
        if (docType != null) {
            appendLine("scan: type=$docType etat=${issuingState ?: "?"} format=${format ?: "?"}")
            appendLine("doc: motif=${docPattern ?: "?"} cd=${ok(docCheckOk)} empreinte=${fingerprint ?: "?"}")
            append("ambigus: ")
            if (ambiguities.isEmpty()) appendLine("aucun")
            else appendLine(ambiguities.joinToString(" ") {
                "[pos=${it.position} lu='${it.read}' alt='${it.alternative ?: '?'}'" +
                    (it.confidence?.let { c -> " conf=%.2f".format(c) } ?: "") + "]"
            } + if (ambiguitiesTruncated > 0) " (+$ambiguitiesTruncated autres)" else "")
            appendLine(
                "dates: cd_naissance=${ok(dobCheckOk)} cd_expiration=${ok(expCheckOk)} " +
                    "plausibles=${ok(datesPlausible)} annee_exp=${expiryYear ?: "masquée"}"
            )
        }
        if (cardAccessInfos.isNotEmpty()) {
            appendLine("cardaccess: [${cardAccessInfos.joinToString("] [")}]")
        }
        for (a in attempts) {
            appendLine(
                "tentative ${a.index}: cle=${a.keyLabel} oid=${a.oid ?: "?"} " +
                    "etape=${a.step} resultat=${a.result} duree=${"%.1f".format(a.durationMs / 1000.0)}s"
            )
        }
        if (bacResult != null) appendLine("bac: tenté resultat=$bacResult")
        if (classification != null) appendLine("classification: $classification")
        if (finalAccess != null) appendLine("acces_final: $finalAccess")
        if (dgPresent.isNotEmpty()) appendLine("dg_presents: ${dgPresent.joinToString(",")}")
        if (integrityOk != null) appendLine("integrite: ${ok(integrityOk)}")
        if (signature != null) appendLine("signature: $signature")
        if (coherenceOk != null) {
            append("coherence_scan_puce: ")
            if (coherenceOk) appendLine("OK")
            else {
                val detail = divergences.joinToString(" ; ") {
                    "doc pos ${it.position} : scan='${it.scanChar}' puce='${it.chipChar}'" +
                        if (it.blindPair) " (paire aveugle)" else ""
                }
                appendLine("KO — " + detail.ifEmpty { "écart hors n° de document" } +
                    if (divergencesTruncated > 0) " (+$divergencesTruncated autres positions)" else "")
            }
        }
    }.trimEnd()

    private fun ok(b: Boolean?): String = when (b) { true -> "OK"; false -> "KO"; null -> "?" }
}

/**
 * Fabrique et fonctions PURES du caviardage. C'est ici que les valeurs brutes sont
 * transformées en dérivés masqués — elles n'entrent jamais dans [DebugReport].
 */
object DebugReports {

    /**
     * Budget GLOBAL de positions du numéro dont un caractère réel peut être révélé, TOUTES
     * sources confondues (ambiguïtés + divergences). Fixé bien en dessous de la longueur du
     * numéro (9 en TD1/TD3) : au plus 3 positions révélées -> au moins 6 inconnues -> le
     * numéro ne peut PAS être reconstitué. Priorité aux divergences (preuve directe de
     * l'erreur OCR). C'est ce plafond unique qui garantit la règle « ne pas permettre de
     * reconstituer le numéro complet » (un plafond par source ne suffit pas).
     */
    private const val MAX_REVEALED_POSITIONS = 3

    /** Motif du numéro par classes : L=lettre, 9=chiffre, '<'=remplissage, ?=autre. */
    fun maskDocPattern(doc: String): String = doc.map {
        when {
            it in 'A'..'Z' || it in 'a'..'z' -> 'L'
            it in '0'..'9' -> '9'
            it == '<' -> '<'
            else -> '?'
        }
    }.joinToString("")

    /** Alternative de paire aveugle d'un glyphe (le premier substitut connu), sinon null. */
    fun blindPairAlternative(c: Char): Char? = MrzKeyCandidates.BLIND_SUBSTITUTIONS[c]?.firstOrNull()

    /** Positions où deux chaînes diffèrent (les positions au-delà de la longueur commune incluses). */
    fun diffPositions(a: String, b: String): List<Int> {
        val common = minOf(a.length, b.length)
        val diffs = (0 until common).filter { a[it] != b[it] }.toMutableList()
        for (i in common until maxOf(a.length, b.length)) diffs += i
        return diffs
    }

    /** Status word extrait de la chaîne de causes (première CardServiceException), sinon -1. */
    fun swOf(e: Throwable?): Int {
        var cur = e
        while (cur != null) {
            if (cur is CardServiceException && cur.sw in 1..0xFFFF) return cur.sw
            cur = cur.cause
        }
        return -1
    }

    /** Étape PACE probable, déduite du SW (jamais du message brut d'exception). */
    fun stepOf(e: Throwable): String = when {
        PaceError.isTagLost(e) -> "GA"
        swOf(e).let { it == 0x6300 || (it and 0xFFF0) == 0x63C0 } -> "GA"
        swOf(e).let { it == 0x6A80 || it == 0x6A88 || it == 0x6A86 } -> "MSE:Set AT"
        else -> "PACE"
    }

    /** Résultat d'une tentative : SW, TagLost, ou classe d'exception — jamais le message. */
    fun resultOf(e: Throwable): String {
        if (PaceError.isTagLost(e)) return "TagLost"
        val sw = swOf(e)
        if (sw >= 0) return "SW=0x%04X".format(sw)
        return e::class.java.simpleName
    }

    /** Empreinte de corrélation : SHA-256(numéro + sel d'installation), tronquée à 8 hex. */
    fun fingerprint(documentNumber: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((documentNumber + salt).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    private val PACE_NAMES = mapOf(
        "0.4.0.127.0.7.2.2.4.2.4" to "PACE-ECDH-GM-AES-CBC-CMAC-256",
        "0.4.0.127.0.7.2.2.4.2.2" to "PACE-ECDH-GM-AES-CBC-CMAC-128",
        "0.4.0.127.0.7.2.2.4.2.3" to "PACE-ECDH-GM-AES-CBC-CMAC-192",
        "0.4.0.127.0.7.2.2.4.4.4" to "PACE-ECDH-IM-AES-CBC-CMAC-256",
        "0.4.0.127.0.7.2.2.4.6.4" to "PACE-ECDH-CAM-AES-CBC-CMAC-256",
    )

    fun paceName(oid: String): String = PACE_NAMES[oid] ?: oid

    /**
     * Assemble un rapport CAVIARDÉ. Les entrées brutes ([scanned], [chip]) servent
     * uniquement à calculer des dérivés masqués — rien de brut n'est conservé.
     */
    fun build(
        env: DebugEnv,
        scanned: MrzOcr.MrzData?,
        chip: ReadResult?,
        diag: DiagnosticsCollector?,
        salt: String,
        includeExpiryYear: Boolean,
    ): DebugReport {
        val format = when (scanned?.docType) {
            MrzOcr.DocType.PASSPORT -> "TD3"
            MrzOcr.DocType.ID_CARD, MrzOcr.DocType.RESIDENCE_PERMIT -> "TD1"
            null -> null
        }

        // Divergences scan↔puce (numéro de document) : la preuve directe de la cause OCR.
        // Elles consomment le budget global EN PREMIER (les plus utiles au debug).
        val divAll = if (scanned != null && chip != null)
            diffPositions(scanned.documentNumber, chip.documentNumber) else emptyList()
        val shownDivPos = divAll.take(MAX_REVEALED_POSITIONS)
        val divergences = shownDivPos.mapNotNull { pos ->
            val s = scanned!!.documentNumber.getOrNull(pos)
            val c = chip!!.documentNumber.getOrNull(pos)
            if (s != null && c != null) {
                Divergence(pos, s, c, MrzKeyCandidates.BLIND_SUBSTITUTIONS[s]?.contains(c) == true)
            } else null
        }

        // Ambiguïtés : positions à glyphe de paire aveugle, avec le budget RESTANT, et
        // jamais sur une position déjà révélée par une divergence (pas de double compte).
        val totalBlind = scanned?.documentNumber?.count {
            MrzKeyCandidates.BLIND_SUBSTITUTIONS.containsKey(it)
        } ?: 0
        val remaining = (MAX_REVEALED_POSITIONS - divergences.size).coerceAtLeast(0)
        val ambiguities = scanned?.documentNumber?.withIndex()
            ?.filter { MrzKeyCandidates.BLIND_SUBSTITUTIONS.containsKey(it.value) && it.index !in shownDivPos }
            ?.take(remaining)
            ?.map { AmbiguousChar(it.index, it.value, blindPairAlternative(it.value), confidence = null) }
            .orEmpty()

        val expiryYear = if (includeExpiryYear) scanned?.dateOfExpiry?.takeIf { it.length >= 2 }
            ?.let { "20" + it.substring(0, 2) } else null

        return DebugReport(
            env = env,
            nfcExtendedLength = diag?.nfcExtendedLength,
            nfcMaxTransceive = diag?.nfcMaxTransceive,
            nfcTimeoutMs = diag?.nfcTimeoutMs,
            docType = scanned?.docType?.name,
            issuingState = scanned?.issuingState,
            format = format,
            docPattern = scanned?.let { maskDocPattern(it.documentNumber) },
            docCheckOk = scanned?.let { true },  // un scan validé implique des chiffres de contrôle OK
            fingerprint = scanned?.let { fingerprint(it.documentNumber, salt) },
            ambiguities = ambiguities,
            ambiguitiesTruncated = (totalBlind - ambiguities.size).coerceAtLeast(0),
            dobCheckOk = scanned?.let { true },
            expCheckOk = scanned?.let { true },
            datesPlausible = scanned?.let { true },
            expiryYear = expiryYear,
            cardAccessInfos = diag?.cardAccessInfos.orEmpty(),
            attempts = diag?.attempts.orEmpty(),
            bacResult = diag?.bacResult,
            classification = diag?.classification,
            finalAccess = diag?.finalAccess,
            dgPresent = diag?.dgPresent.orEmpty(),
            integrityOk = chip?.hashesMatchSod,
            signature = chip?.signature?.name,
            coherenceOk = chip?.mrzMatchesScan,
            divergences = divergences,
            divergencesTruncated = (divAll.size - divergences.size).coerceAtLeast(0),
        )
    }
}
