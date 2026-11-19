package fr.veripuce.app.journal

import java.io.File

/**
 * Fichier du journal des lectures (build debug), dans le stockage PRIVÉ de l'app
 * (`filesDir`, jamais le stockage externe ; `allowBackup=false`). Texte brut, entrées
 * séparées par une ligne `===== lecture #N =====`, plafonné aux [maxEntries] dernières.
 * Ne reçoit que du texte déjà caviardé par [JournalRedaction.render].
 */
class JournalStore(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {

    @Synchronized
    fun append(entry: String) {
        val entries = entries().toMutableList()
        val next = (entries.lastOrNull()?.first ?: 0) + 1
        entries += next to entry.trimEnd()
        write(entries.takeLast(maxEntries))
    }

    @Synchronized
    fun count(): Int = entries().size

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Contenu complet, prêt à partager (en-tête de schéma inclus), ou null si vide. */
    @Synchronized
    fun export(): String? = if (count() == 0) null else file.readText()

    private fun entries(): List<Pair<Int, String>> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        var seq: Int? = null
        val buf = StringBuilder()
        fun flush() { seq?.let { out += it to buf.toString().trimEnd() }; buf.setLength(0) }
        for (line in file.readLines()) {
            val m = SEP.matchEntire(line)
            if (m != null) { flush(); seq = m.groupValues[1].toInt() }
            else if (seq != null) buf.appendLine(line)
        }
        flush()
        return out
    }

    private fun write(entries: List<Pair<Int, String>>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildString {
            appendLine(JournalRedaction.SCHEMA)
            appendLine("# ${entries.size} lecture(s), $maxEntries max. Données caviardées : aucune MRZ, aucun numéro, aucune donnée APDU.")
            for ((n, text) in entries) {
                appendLine()
                appendLine("===== lecture #$n =====")
                appendLine(text)
            }
        })
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private val SEP = Regex("===== lecture #(\\d+) =====")
    }
}
