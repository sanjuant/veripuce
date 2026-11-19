package fr.veripuce.app.journal

import fr.veripuce.app.DebugReports
import fr.veripuce.app.ReadTrace
import net.sf.scuba.smartcards.APDUListener
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU

/**
 * Trace APDU d'une lecture (build debug). Enveloppe le [CardService] SCUBA : chaque
 * `transmit` est chronométré et réduit IMMÉDIATEMENT à son en-tête par
 * [JournalRedaction.apduLine] — aucun octet de données n'est conservé, même en mémoire.
 * Sous Secure Messaging, on voit les APDU protégées (CLA 0C), telles qu'envoyées à la puce.
 */
class ApduTracer : ReadTrace {

    private val t0 = System.nanoTime()
    private val lines = mutableListOf<JournalRedaction.TraceLine>()

    private fun nowMs() = (System.nanoTime() - t0) / 1_000_000

    fun snapshot(): List<JournalRedaction.TraceLine> = synchronized(lines) { lines.toList() }

    override fun event(name: String) {
        synchronized(lines) { lines += JournalRedaction.TraceLine.Event(nowMs(), name) }
    }

    override fun wrap(service: CardService): CardService = Tracing(service)

    private inner class Tracing(private val delegate: CardService) : CardService() {
        override fun open() {
            delegate.open(); event("ouverture")
        }

        override fun isOpen(): Boolean = delegate.isOpen

        override fun transmit(command: CommandAPDU): ResponseAPDU {
            val start = System.nanoTime()
            val at = nowMs()
            val header = command.bytes   // lu ici, réduit à l'en-tête ci-dessous, jamais stocké
            try {
                val resp = delegate.transmit(command)
                add(JournalRedaction.apduLine(at, header, resp.sw, resp.nr, null, ms(start)))
                return resp
            } catch (e: Exception) {
                add(JournalRedaction.apduLine(at, header, null, null, DebugReports.resultOf(e), ms(start)))
                throw e
            }
        }

        override fun getATR(): ByteArray? = delegate.atr
        override fun isExtendedAPDULengthSupported(): Boolean = delegate.isExtendedAPDULengthSupported
        override fun close() {
            delegate.close(); event("fermeture")
        }

        override fun isConnectionLost(e: Exception?): Boolean = delegate.isConnectionLost(e)
        override fun addAPDUListener(l: APDUListener?) = delegate.addAPDUListener(l)
        override fun removeAPDUListener(l: APDUListener?) = delegate.removeAPDUListener(l)
        override fun getAPDUListeners(): MutableCollection<APDUListener> = delegate.apduListeners

        private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
        private fun add(l: JournalRedaction.TraceLine) = synchronized(lines) { lines += l }
    }
}
