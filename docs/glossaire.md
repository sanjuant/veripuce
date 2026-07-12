# Glossaire

Le domaine des documents d'identité électroniques (eMRTD) est truffé de sigles. Ce glossaire
les explique **en français simple**, avec des analogies. À lire en premier si tu découvres le
projet.

## La carte, ce qu'on lit dessus

**MRZ** *(Machine Readable Zone)* — la bande de caractères en `<<<<` au bas du document. C'est
la version « lisible par machine » de l'état civil. Analogie : le code-barres d'un produit, mais
en lettres.

```
Exemple FICTIF (format TD1, valeurs inventées) :

IDFRASPEC00000X<<<<<<<<<<<<<<<   ← ligne 1 : type, pays, n° de document
9001010M3001019FRA<<<<<<<<<<X   ← ligne 2 : naissance, sexe, expiration, nationalité
MARTIN<<CAMILLE<<<<<<<<<<<<<<<   ← ligne 3 : nom et prénoms
```

**TD1 / TD3** — deux formats de MRZ définis par la norme :
- **TD1** = carte (3 lignes de 30 caractères). Cartes d'identité, titres de séjour.
- **TD3** = passeport (2 lignes de 44 caractères).

**Chiffre de contrôle** *(check digit)* — un chiffre calculé à partir des autres, pour détecter
une erreur de saisie/lecture. Exactement comme la clé d'un RIB ou le dernier chiffre d'un code-
barres. Formule ICAO : pondération **7‑3‑1** (voir [algorithmes.md](algorithmes.md#3-parsing-mrz-et-chiffres-de-contrôle-icao)).

**CAN** *(Card Access Number)* — 6 chiffres imprimés au **recto** de la carte, à côté de la
photo. C'est un **second mot de passe** pour ouvrir la puce, indépendant de la MRZ. Analogie : le
cryptogramme au dos d'une carte bancaire.

**OCR-B** — la police de caractères normée des MRZ (chiffres et lettres à chasse fixe, dessinés
pour être lus par machine). ML Kit n'est pas spécifiquement entraîné dessus, d'où certaines
confusions (voir « paire aveugle »).

## La puce et son contenu

**eMRTD** *(electronic Machine Readable Travel Document)* — le nom générique d'un document
d'identité **à puce** conforme à la norme internationale. Une CNIe, un passeport biométrique et
un titre de séjour à puce sont tous des eMRTD.

**ICAO 9303** — la norme internationale (aviation civile) qui définit tout : structure de la MRZ,
protocoles d'accès à la puce, format des données, signatures. C'est la « bible » du projet.

**DG** *(Data Group)* — un « fichier » dans la puce. Les principaux :

| Fichier | Contenu | Accessible ? |
|---|---|---|
| **DG1** | La MRZ (état civil) | Oui |
| **DG2** | La photo du visage | Oui |
| **DG3** | Empreintes digitales | **Non** (protégé EAC) |
| **DG4** | Iris | **Non** (protégé EAC) |
| **DG13** | Données propres à la France (adresse…) | Oui |
| **DG14** | Clé publique pour l'anti-clone (Chip Authentication) | Oui |
| **DG15** | Clé publique pour l'anti-clone (Active Authentication) | Oui |

**SOD** *(Document Security Object, fichier EF.SOD)* — le « sceau » de la puce : il contient les
**empreintes** (hash) de tous les DG, le tout **signé** par l'État émetteur. C'est la pièce
maîtresse de la vérification anti-fraude.

**LDS** *(Logical Data Structure)* — le nom officiel de l'organisation des fichiers (DG + SOD)
dans la puce.

## Ouvrir la puce (les protocoles)

**IsoDep** — la couche de transport NFC (norme ISO 14443-4). C'est le « tuyau » brut par lequel
on envoie des commandes à la puce.

**APDU** — une commande envoyée à la puce (et sa réponse). L'unité d'échange de base. Chaque
réponse se termine par un **status word** (SW) de 2 octets (ex. `0x9000` = OK, `0x6300` = échec
d'authentification).

**PACE** *(Password Authenticated Connection Establishment)* — le protocole moderne qui ouvre une
session sécurisée avec la puce, à partir d'un mot de passe (la MRZ ou le CAN). Analogie : une
poignée de main secrète — les deux parties prouvent qu'elles connaissent le secret **sans jamais
le transmettre en clair**, et en dérivent une clé de session. Résistant au piratage hors ligne.

**BAC** *(Basic Access Control)* — l'ancêtre de PACE (documents d'avant ~2015). Plus faible.
Utilisé seulement en **repli** quand la puce n'annonce pas de PACE. La CNIe française n'a pas de BAC.

**GM / IM / CAM** — trois **variantes** de PACE (façons de faire la poignée de main) :
- **GM** *(Generic Mapping)* — la plus répandue ; fait un échange Diffie-Hellman supplémentaire.
- **IM** *(Integrated Mapping)* — plus courte (moins d'allers-retours radio) ; utile quand GM
  « gèle » sur une carte au couplage NFC marginal.
- **CAM** *(Chip Authentication Mapping)* — combine PACE et l'anti-clone.

**Secure Messaging** — une fois PACE réussi, **tous** les échanges avec la puce sont chiffrés et
authentifiés (MAC) avec la clé de session. Un espion NFC ne voit plus rien.

**Mot de passe PACE** — soit dérivé de la MRZ (SHA-1 du numéro + dates), soit le CAN utilisé
directement. Voir [cles-acces.md](cles-acces.md).

## Prouver que le document est authentique

**Passive Authentication** — la vérification cryptographique en 3 temps : (1) les données n'ont
pas été altérées, (2) le SOD est bien signé, (3) la signature remonte à un État de confiance. Voir
[algorithmes.md](algorithmes.md#6-passive-authentication).

**CSCA** *(Country Signing Certification Authority)* — l'autorité **racine** d'un État pour ses
documents. Analogie : le « tampon officiel » du pays. Veripuce embarque ~770 certificats CSCA du
monde entier comme **ancres de confiance**.

**DSC** *(Document Signer Certificate)* — le certificat qui **signe concrètement** chaque document,
lui-même signé par la CSCA de son pays. Analogie : le préfet (DSC) signe ta carte, et sa
nomination vient du ministère (CSCA).

**Chaîne de confiance** — `document → signé par DSC → DSC signé par CSCA → CSCA de confiance`. Si
la chaîne remonte jusqu'à une CSCA qu'on connaît, le document est prouvé « émis par l'État ».

**CMS / PKCS#7** — le format standard de signature électronique utilisé par le SOD.

**Chip Authentication / Active Authentication** — les deux mécanismes **anti-clone** : la puce
prouve qu'elle détient une **clé privée non extractible**, qu'un clone (simple copie des données)
ne peut pas reproduire. Voir [algorithmes.md](algorithmes.md#7-détection-de-puce-clonée).

**EAC** *(Extended Access Control)* — la protection **renforcée** des données biométriques
sensibles (DG3 empreintes, DG4 iris). Elle exige un certificat de terminal délivré par l'État,
qu'une app publique n'a pas. Ces données sont donc **hors de portée** — par conception.

## Cryptographie (les briques)

**SHA-1 / SHA-256** — des fonctions de **hachage** : elles transforment n'importe quelle donnée en
une empreinte de taille fixe. Un seul bit changé → empreinte totalement différente (« effet
avalanche »). Servent aux empreintes des DG et à la dérivation de clé.

**RSA / ECDSA** — deux familles d'algorithmes de **signature**. RSA (historique), ECDSA (courbes
elliptiques, plus moderne et compact).

**brainpool / secp256r1 (NIST P-256)** — des **courbes elliptiques** (paramètres mathématiques
pour ECDSA/ECDH). Les documents français/allemands utilisent souvent **brainpool**, que les
bibliothèques crypto d'Android ne gèrent pas toutes — d'où le recours à BouncyCastle.

**BER-TLV** — le format d'encodage binaire des données de la puce : chaque champ = `Tag` (quel
champ) + `Length` (sa taille) + `Value` (le contenu). Un parseur maison (`BerTlv.kt`) navigue
dedans **sans reconstruire** les octets (crucial pour l'intégrité).

**BouncyCastle** — la bibliothèque cryptographique de référence (Java). Veripuce remplace au
démarrage la version partielle d'Android par la version complète.

## Composants Android du projet

**JMRTD** — la bibliothèque Java qui implémente la lecture eMRTD (PACE, BAC, Secure Messaging,
parsing LDS). Le cœur bas niveau.

**SCUBA** — la couche de transport carte à puce sur Android, utilisée par JMRTD.

**ML Kit** — la bibliothèque d'OCR de Google, embarquée (offline), qui lit la MRZ à la caméra.

**CameraX** — l'API caméra Android moderne, pour l'aperçu et l'analyse d'images.

## À retenir en une phrase

> On **scanne** la MRZ à la caméra (ML Kit), on en dérive un **mot de passe** qui ouvre la puce
> (**PACE**), on lit les **DG** (identité, photo), et on **prouve** que tout est authentique et
> émis par l'État (**Passive Authentication** + chaîne **CSCA**), sans jamais rien envoyer sur
> le réseau.
