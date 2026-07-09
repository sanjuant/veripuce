package fr.veripuce.app

/**
 * Clé d'accès à la puce eMRTD (ICAO 9303).
 *
 * - [Mrz] : cas nominal, TOUS documents. Clé dérivée de la MRZ — n° document, date de
 *   naissance et date d'expiration (AAMMJJ) — utilisée en PACE-MRZ ou, à défaut, en BAC.
 *   ICAO 9303-11 impose l'acceptation du mot de passe MRZ ; les CNIe (française : applet
 *   IDEMIA « PACE passwords: MRZ, CAN, PIN, PUK » ; marocaine 2020+) l'acceptent aussi.
 * - [Can] : repli pour les cartes d'identité (les 6 chiffres du recto, PACE-CAN), si la
 *   puce refuse la clé MRZ ou en saisie manuelle sans MRZ.
 *
 * DG1/DG2/EF.SOD se lisent ensuite de façon identique quel que soit le mode ; seul le
 * DG13 (propriétaire France) n'existe que sur la CNIe.
 */
sealed class AccessKey {
    data class Can(val can: String) : AccessKey()

    data class Mrz(
        val documentNumber: String,
        val dateOfBirth: String,   // AAMMJJ
        val dateOfExpiry: String,  // AAMMJJ
    ) : AccessKey()
}

/**
 * La puce a refusé la clé d'accès (échec PACE/BAC), par opposition à une perte de
 * contact NFC. Permet à l'UI de proposer un repli — ex. saisir le CAN quand la clé
 * MRZ est rejetée par une carte d'identité.
 */
class ChipAccessException(cause: Throwable) : Exception("Accès refusé par la puce", cause)
