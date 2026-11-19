package fr.veripuce.app

import net.sf.scuba.smartcards.CardService

/**
 * Trace bas niveau d'UNE lecture (en-têtes APDU, SW, durées, événements du tag), passée en
 * paramètre OPTIONNEL à [CnieReader.read] (défaut null -> aucune trace).
 *
 * Seul le build debug en fournit une implémentation (journal des lectures, `src/debug`) ; le
 * build release n'en a aucune et passe toujours null.
 */
interface ReadTrace {
    /** Enveloppe le service carte pour observer chaque échange APDU. */
    fun wrap(service: CardService): CardService

    /** Événement du tag hors APDU (reconnexion…). Libellé FIXE, jamais une donnée lue. */
    fun event(name: String)
}
