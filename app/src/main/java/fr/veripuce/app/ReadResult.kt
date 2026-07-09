package fr.veripuce.app

import android.graphics.Bitmap

/**
 * Résultat de la détection de puce clonée.
 *
 * - [AUTHENTIC] : la puce a prouvé qu'elle détient une clé privée non extractible
 *   (Chip Authentication ou Active Authentication réussie) -> pas un clone.
 * - [FAILED] : le document annonce ce mécanisme mais la puce a échoué à le prouver
 *   -> possible clone (ou puce défaillante).
 * - [UNSUPPORTED] : le document n'expose pas de mécanisme anti-clone exploitable.
 */
enum class CloneCheck { AUTHENTIC, FAILED, UNSUPPORTED }

/**
 * Résultat de la vérification de signature du SOD (passive authentication, étape 3).
 *
 * - [TRUSTED] : signature du SOD valide ET le certificat signataire (DSC) chaîne jusqu'à
 *   une CSCA de confiance -> document émis par l'État (preuve d'origine).
 * - [VALID_UNTRUSTED] : signature du SOD cryptographiquement valide, mais le DSC ne remonte
 *   à aucune CSCA de confiance chargée -> intégrité prouvée, origine non prouvée.
 * - [INVALID] : la signature du SOD ne se vérifie pas -> document falsifié / suspect.
 * - [NOT_CHECKED] : vérification impossible (SOD illisible, erreur).
 */
enum class SignatureCheck { TRUSTED, VALID_UNTRUSTED, INVALID, NOT_CHECKED }

/**
 * Résultat d'une lecture eMRTD.
 *
 * Les vérifications sont ce qui distingue une simple lecture d'un contrôle anti-fraude :
 * - [hashesMatchSod] : les données de la puce ne sont pas altérées (intégrité) ;
 * - [signature] : la signature du SOD, et si le document a été émis par l'État (chaîne CSCA) ;
 * - [mrzMatchesScan] : la MRZ imprimée (scan optique) correspond à la puce (DG1) ;
 * - [cloneCheck] : la puce n'est pas un clone (Chip/Active Authentication).
 */
data class ReadResult(
    val mrz: String,
    val documentNumber: String,
    val surname: String,
    val givenNames: String,
    val dateOfBirth: String,   // AAMMJJ
    val dateOfExpiry: String,  // AAMMJJ
    val nationality: String,
    val photo: Bitmap?,
    val dg13: Dg13?,
    val hashesMatchSod: Boolean,
    val signature: SignatureCheck,
    /** null si aucune MRZ scannée n'a été fournie pour comparaison. */
    val mrzMatchesScan: Boolean?,
    val cloneCheck: CloneCheck,
    /** Mécanisme utilisé/détecté (ex. "Chip Authentication"), pour l'affichage. */
    val cloneMethod: String?,
)
