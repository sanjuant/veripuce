package fr.veripuce.app

import net.sf.scuba.smartcards.CardServiceException

/**
 * Classification des échecs d'ouverture de session PACE/BAC.
 *
 * Distinguer un REFUS de clé (mauvais mot de passe) d'un aléa TRANSITOIRE est décisif
 * pour l'UI : un refus avéré justifie de proposer le CAN, un glitch NFC non — il faut
 * juste re-présenter la carte, la clé MRZ restant valable. JMRTD perd parfois la chaîne
 * de causes (PACEException sans SW) ; seul un status word 6300/63Cx PROUVE le refus.
 */
object PaceError {

    /** Refus de clé avéré : SW 6300 (auth. échouée) ou 63Cx (compteur d'essais) dans les causes. */
    fun isKeyRefused(e: Throwable): Boolean =
        causes(e).filterIsInstance<CardServiceException>()
            .any { it.sw == 0x6300 || (it.sw and 0xFFF0) == 0x63C0 }

    /** 63Cx : la puce décompte des essais restants — ne jamais provoquer un second refus. */
    fun hasRetryCounter(e: Throwable): Boolean =
        causes(e).filterIsInstance<CardServiceException>().any { (it.sw and 0xFFF0) == 0x63C0 }

    /** Perte de contact NFC (transitoire) — à distinguer d'un refus de clé. */
    fun isTagLost(e: Throwable): Boolean =
        causes(e).any { it is android.nfc.TagLostException }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }
}
