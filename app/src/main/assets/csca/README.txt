Magasin de certificats CSCA de confiance (ancre de confiance de la passive authentication).

FORMAT AUDITABLE : un fichier PEM par certificat — 773 certificats CSCA (Country Signing CA)
uniques, déposés le 2026-07-09. Nommage :

    <pays>_<CN>_<sources>_<sha256 tronqué>.pem
    ex. FR_CSCA-FRANCE_icao-bsi-ants_d628b510.pem
        -> présent dans la masterlist ICAO, la masterlist BSI ET publié par l'ANTS.

Chaque fichier porte en tête (commentaires ignorés au parsing) : sujet, émetteur, numéro
de série, validité, empreinte SHA-256 complète, source(s) exacte(s) avec édition/URL et
date de téléchargement. MANIFEST.tsv récapitule le tout — le diff git d'une mise à jour
montre précisément quels certificats entrent et sortent.

Sources officielles indépendantes (union dédupliquée) :

  1. Masterlist ICAO      ICAO_ML_20260708111336.ml (éd. 2026-06-09, 572 certs)
     https://www.icao.int/icao-pkd/icao-master-list
  2. Masterlist BSI (DE)  DE_ML_2026-05-28-08-28-45.ml (éd. 2026-05-28, 588 certs, 114 pays)
     https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html
  3. ANTS (France)        https://ants.gouv.fr/csca et https://ants.gouv.fr/eid :
     CSCA-FRANCE 2010/2015/2020/2025 (passeports, CNIe, titres de séjour)
     + eID-FRANCE (CSCA e-ID de la CNIe, absente des masterlists « voyage »)

Contrôle croisé effectué au dépôt : les CSCA-FRANCE 2015/2020/2025 sont identiques
octet-pour-octet dans les trois canaux ; la 2010 (expirée 03/2026, gardée pour les
documents encore en circulation) dans ANTS + ICAO ; l'empreinte SHA-256 d'eID-FRANCE
correspond au fichier d'empreinte publié par l'ANTS :
  eID-FRANCE  B3:3E:A6:3B:9B:E0:10:82:D9:80:71:A2:91:11:75:7C:72:25:7E:BA:80:D7:20:5D:21:FA:35:43:6C:29:FE:7C

Mise à jour : python tools/csca/update_csca.py (télécharge, vérifie les signatures CMS
des masterlists, croise les canaux, régénère les fichiers et le MANIFEST). Les masterlists
sont rééditées tous les 1 à 3 mois. Le test CscaBundleTest vérifie l'intégrité du magasin
(1 fichier = 1 certificat, empreintes des noms conformes, manifest cohérent).

Formats acceptés par l'application : DER (.cer/.der/.crt) et PEM (.pem, multi-certs).
Ces certificats sont PUBLICS (clés publiques d'autorités) — ce ne sont pas des secrets.
Ce README et MANIFEST.tsv sont ignorés au chargement (seules les extensions ci-dessus sont lues).

JAMAIS déposer ici les certificats de TEST publiés par l'ANTS (CSCA-FRANCE TEST,
CSCA-eID-FRANCE TEST) : ils rendraient « authentiques » des documents de test.
