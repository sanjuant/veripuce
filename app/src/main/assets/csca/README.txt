Magasin de certificats CSCA de confiance (ancre de confiance de la passive authentication).

Déposez ici les certificats CSCA (Country Signing CA) au format :
  - DER  : *.cer, *.der, *.crt   (un certificat par fichier)
  - PEM  : *.pem                 (un fichier peut contenir plusieurs certificats)

Sources :
  - ICAO PKD (Public Key Directory) : masterlist des CSCA de tous les pays.
  - France : certificats CSCA publiés par l'ANTS.

Sans certificat ici, la signature du SOD reste vérifiée cryptographiquement, mais
l'origine étatique du document ne peut pas être prouvée (« Signature valide, autorité
non reconnue » au lieu de « Émis par l'État »).

Ces certificats sont PUBLICS (clés publiques d'autorités) — ce ne sont pas des secrets.
Ce fichier README est ignoré au chargement (seules les extensions ci-dessus sont lues).
