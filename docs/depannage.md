# Dépannage

Comment diagnostiquer une lecture qui échoue, à partir du **rapport de diagnostic caviardé**.
Ce document décortique ce rapport et donne un **arbre de décision**.

- [1. Activer et lire le rapport](#1-activer-et-lire-le-rapport)
- [2. Anatomie du rapport](#2-anatomie-du-rapport)
- [3. Arbre de décision](#3-arbre-de-décision)
- [4. Pannes classiques](#4-pannes-classiques)

---

## 1. Activer et lire le rapport

Le mode diagnostic est **désactivé par défaut**.

1. **Appui long sur le titre « Veripuce »** → « Mode diagnostic activé » (persistant).
2. Faire la lecture (réussie ou échouée) → une carte **« Détails techniques »** apparaît.
3. La déplier, puis **Copier** / **Partager**.

> Activer le mode **avant** de reproduire le problème : le collecteur ne tourne qu'en mode
> diagnostic (garantie « lecture inchangée » sinon). Pour obtenir la ligne `coherence_scan_puce`
> (la plus utile), il faut que la lecture **aboutisse** (par le CAN si besoin).

Le rapport ne contient **aucune donnée personnelle** en clair — il est conçu pour être partagé.
Voir [securite-confidentialite.md](securite-confidentialite.md#3-le-rapport-de-diagnostic-caviardé).

---

## 2. Anatomie du rapport

Exemple **FICTIF** commenté (valeurs inventées) — illustre un gel de GM rattrapé par le CAN en IM,
avec une erreur OCR :

```
veripuce-debug v1
horodatage: 2026-01-15T10:00:00+01:00
app: 0.3.0 (abc1234)                    ← version + commit git (pour situer les correctifs)
appareil: Marque Modèle — Android 14 (API 34)
nfc: extendedLength=true maxTransceive=65279 timeout=6000
ocr: mlkit-... analyse 1180x300 (ROI)   ← taille du crop MRZ analysé
scan: type=ID_CARD etat=XXX format=TD1  ← ⚠ etat devrait être FRA : OCR défaillant
doc:  motif=LLLLLLLLL cd=OK ...          ← motif du n° : L=lettre 9=chiffre. Tout lettres = suspect
ambigus: [pos=3 lu='6' alt='G']          ← caractères en "paire aveugle" (voir glossaire)
dates: cd_naissance=OK cd_expiration=OK plausibles=OK annee_exp=2030
cardaccess: [PACE-ECDH-IM-...] [PACE-ECDH-GM-...]   ← protocoles PACE annoncés par la puce
tentative 1: cle=MRZ oid=...2.4 mapping=GM etape=GA resultat=TagLost duree=6,0s ← GM a GELÉ
tentative 2: cle=- oid=? etape=reconnexion resultat=échec duree=0,1s   ← carte sortie du champ
tentative 3: cle=MRZ oid=...4.4 mapping=IM etape=GA resultat=TagLost duree=2,2s ← tap suivant : IM
tentative 4: cle=CAN oid=...4.4 mapping=IM etape=GA resultat=OK duree=0,4s      ← CAN en IM : OK !
classification: succès
acces_final: CAN
dg_presents: 1,2,13,14
integrite: OK
signature: TRUSTED
coherence_scan_puce: KO — doc pos 0 : scan='A' puce='B' ; ...           ← preuve OCR faux
```

### Les champs qui parlent

| Champ | Ce qu'il révèle |
|---|---|
| `resultat=TagLost` | La puce a **décroché** en plein échange (perte de contact / gel). Pas un refus de clé. |
| `resultat=SW=0x6300` | Le handshake est allé **au bout** mais le **mot de passe est faux** (donc n° ou dates mal lus). |
| `resultat=OK` | Cette tentative a **réussi**. |
| `etape=` | Étape PACE exacte donnée par JMRTD : `MSE:Set AT` (choix du mot de passe/protocole), `GA1-nonce`, `GA2-mapping`, `GA3-accord`, `GA4-auth` (jeton d'authentification). `GA` seul = étape déduite du SW, faute de mieux. |
| `duree=15,0s` | Le **timeout complet** → gel (la puce ne répondait plus). |
| `oid=…2.4` vs `…4.4` | Protocole utilisé : `2.4` = **GM**, `4.4` = **IM**, `6.4` = **CAM** (repris en clair dans `mapping=`). |
| `etape=reconnexion` | Après un gel, l'app tente de reconnecter la carte pour essayer le mapping suivant **dans le même tap** (`OK`) ; `échec` = carte sortie du champ, le mapping suivant attend le tap d'après. |
| `classification:` | `transitoire` (gel, un mapping reste à essayer), `tous les mappings en échec`, `refus explicite MSE:Set AT`, `refus avéré (6300)`. |
| `coherence_scan_puce` | Compare le n° **scanné** au n° **réel de la puce** — la preuve directe d'une erreur OCR. |
| `etat` / `motif` | Qualité de l'OCR : `FRA` attendu pour une CNI ; un motif tout en `L` (lettres) est louche. |

---

## 3. Arbre de décision

```
La lecture par la MRZ échoue. On lit le rapport :

┌─ Le résultat des tentatives MRZ est-il... ?
│
├─ TagLost, duree≈timeout (gel)
│     → problème de PROTOCOLE ou de COUPLAGE NFC.
│     → La rotation des mappings (GM → IM → CAM, une fois chacun) doit s'en occuper :
│       même tap si la reconnexion réussit, sinon tap suivant. Le CAN n'est demandé
│       qu'après l'échec de TOUS les mappings annoncés.
│     → Si IM réussit ailleurs (ex. CAN en IM = OK), la carte se lit en IM.
│
├─ etape=MSE:Set AT, SW=0x6A80 / 0x6A88 / 0x6985 (refus immédiat, toutes les tentatives MRZ)
│     → la puce n'accepte PAS le mot de passe MRZ sur ce protocole (pas une erreur d'OCR).
│     → L'app propose le CAN tout de suite (`classification: refus explicite MSE:Set AT`).
│     → Si le CAN passe sur le même oid : la carte n'ouvre l'eMRTD qu'au CAN.
│
├─ SW=0x6300 (refus de clé, rapide ; etape=GA4-auth quand JMRTD la donne)
│     → le NUMÉRO/les dates dérivés sont FAUX → problème d'OCR.
│     → Regarder coherence_scan_puce (après une lecture CAN) :
│         • 1-2 positions fausses, en "paire aveugle" → les CANDIDATS PACE
│           devraient l'avoir corrigé automatiquement.
│         • BEAUCOUP de positions fausses (etat ≠ pays réel, motif tout lettres)
│           → OCR globalement défaillant → voir "OCR défaillant" ci-dessous.
│
└─ TagLost, duree courte (ex. 2 s), non reproductible
      → aléa NFC transitoire (bougé, sortie de champ).
      → Action : re-présenter la carte, ne rien changer.
```

---

## 4. Pannes classiques

### La lecture tourne en boucle « Puce détectée » et ne propose jamais le CAN

**Symptôme** : `classification: transitoire`, `resultat=TagLost`, pas de champ CAN.
**Cause** : la puce gèle sur un protocole (souvent GM). Un `TagLost` isolé est traité comme un
aléa (on n'exige pas le CAN pour un simple bougé).
**Ce que fait l'app** : un gel sur un mapping (souvent GM) déclenche l'essai du mapping suivant
annoncé par EF.CardAccess (IM, puis CAM), chacun **une seule fois** — dans le même tap si la carte
se reconnecte, sinon au tap suivant (message « l'app essaie une autre méthode d'ouverture »). Le
champ CAN n'apparaît qu'après l'échec de **tous** les mappings, après un refus explicite à
`MSE:Set AT` (6A80/6A88/6985), après un refus de clé (6300/63Cx), ou au 3ᵉ gel cumulé. Voir
[cles-acces.md](cles-acces.md#6-stratégie-complète-douverture).

### La puce gèle sur GM mais lit en IM

**Symptôme** : `tentative 1: …oid=…2.4… TagLost duree≈timeout`, puis (CAN ou tap suivant)
`…oid=…4.4… OK`.
**Cause** : couplage NFC marginal entre cette carte et ce téléphone ; GM (échange plus long)
décroche, IM (plus court) passe.
**Ce que fait l'app** : timeout court (6 s) pendant le handshake pour échouer vite, puis
reconnexion et **essai en IM dans le même tap** si la carte est restée posée (sinon au tap
suivant), sans demander le CAN. Budget global du handshake : ~13 s par tap.

### La MRZ est lisible à l'œil mais l'app affiche « Reflet détecté » et ne scanne pas

**Symptôme** : message reflet permanent alors que les caractères sont nets.
**Cause historique** : la détection de reflet mesurait la trame entière (photo/hologramme
brillants), pas la bande MRZ.
**Ce que fait l'app** (v0.2.2+) : reflet/lumière mesurés **sur le crop MRZ** ; seuil relâché ;
l'indice se réinitialise quand le reflet se dissipe.

### OCR défaillant sur une carte (numéro lu complètement faux)

**Symptôme** : `etat` ≠ pays réel, `motif` tout en lettres, `coherence_scan_puce` fausse sur
**toutes** les positions.
**Causes** : MRZ petite dans une grande image, reflet, police OCR-B mal reconnue par le modèle
générique de ML Kit.
**Ce que fait l'app** : recadrage sur la zone MRZ (v0.2.2, densifie les caractères) + vote par
position + candidats de paires aveugles. Si l'OCR reste massivement faux malgré tout, le **repli
CAN** est la voie fiable — et la piste d'amélioration est un reconnaisseur **OCR-B spécialisé**.

### Verdict « signature valide, autorité non reconnue » (VALID_UNTRUSTED) au lieu de vert

**Symptôme** : `signature: VALID_UNTRUSTED`.
**Cause** : la signature du document est correcte, mais son autorité (CSCA) n'est **pas** dans le
magasin embarqué.
**Action** : rafraîchir le magasin CSCA (`python tools/csca/update_csca.py`) — voir
[sources.md](sources.md#4-rafraîchissement-automatique).

### Impossible de lire les empreintes / l'iris (DG3 / DG4)

**Ce n'est pas une panne** : ces données sont protégées par **EAC** et **inaccessibles** à toute
application publique, par conception. Voir
[securite-confidentialite.md](securite-confidentialite.md#5-limites--eac-dg3dg4).
