"""Régénère le magasin CSCA (app/src/main/assets/csca/) depuis les sources officielles.

Sortie AUDITABLE : un fichier PEM par certificat, nommé
    <pays>_<CN>_<sources>_<sha256 tronqué>.pem      (ex. FR_CSCA-FRANCE_icao-bsi-ants_d628b510.pem)
chaque fichier portant en tête sujet, émetteur, série, validité, empreinte SHA-256
complète et sources exactes (édition de masterlist / URL ANTS) ; plus un MANIFEST.tsv
récapitulatif — le diff git d'une mise à jour montre précisément quels certificats
entrent et sortent, et chaque certificat est justifiable individuellement.

Usage :  python tools/csca/update_csca.py            (depuis la racine du dépôt)
Prérequis : python 3.10+, `pip install cryptography requests`, openssl dans le PATH.

Sources (URLs pérennes, re-résolues à chaque exécution) :
  - Masterlist ICAO : le nom du fichier courant est scrapé depuis la page de
    consentement publique d'icao.int (il change à chaque édition, ~trimestrielle).
  - Masterlist BSI (Allemagne) : URL sans paramètre de version -> toujours la dernière
    édition (zip contenant un .ml CMS). GET uniquement (le WAF du BSI bloque HEAD).
  - ANTS (France) : les 4 générations CSCA-FRANCE + eID-FRANCE (CNIe). Les URLs UUID
    d'img.ants.gouv.fr peuvent changer si l'ANTS re-téléverse — si un lien 404,
    re-scraper https://ants.gouv.fr/csca et /eid dans un navigateur (pages JS-only).

Garde-fous :
  - `openssl cms -verify -binary` vérifie la signature CMS de chaque masterlist
    (l'option -binary est CRITIQUE : sans elle openssl corrompt l'eContent DER).
  - Contrôle croisé : les CSCA françaises de l'ANTS doivent être identiques
    octet-pour-octet à celles des masterlists ICAO/BSI (canaux indépendants).
  - Seuls les certificats parsables strictement sont gardés (un cert malformé
    ferait rejeter tout le bundle par CertificateFactory sur Android).
  - Les certificats de TEST de l'ANTS ne sont jamais inclus.
"""
import collections
import datetime
import hashlib
import io
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import requests
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives.serialization import Encoding

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "app/src/main/assets/csca"

ICAO_DISCOVERY = "https://www.icao.int/sites/default/files/Security/FAL/Consent_Google_ReCaptcha_new.html"
ICAO_BASE = "https://www.icao.int/sites/default/files/Security/FAL/"
BSI_ZIP = "https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.zip?__blob=publicationFile"
ANTS = {  # nom -> zip img.ants.gouv.fr (miroir direct, sans la protection anti-bot du site)
    "CSCA-FRANCE_2025": "https://img.ants.gouv.fr/78710fab-d613-43a6-9123-e740181f5550.zip",
    "CSCA-FRANCE_2020": "https://img.ants.gouv.fr/9fcbd13c-19a7-406d-ae0f-d4ff68ee247f.zip",
    "CSCA-FRANCE_2015": "https://img.ants.gouv.fr/6c2a08a3-719b-45c9-95b4-bc68b993a89f.zip",
    "CSCA-FRANCE_2010": "https://img.ants.gouv.fr/dc3345f5-3e91-44e7-9f62-47bcfd0e24fc.zip",
    "eID-FRANCE": "https://img.ants.gouv.fr/ba66b9a1-bb09-4c0b-ad27-ce39d038a6e6.zip",
}
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}


def fetch(url, what):
    r = requests.get(url, headers=UA, timeout=120)
    r.raise_for_status()
    if b"<html" in r.content[:500].lower():
        sys.exit(f"ERREUR: {what} a renvoyé une page HTML (protection anti-bot ?) : {url}")
    print(f"  {what}: {len(r.content)} octets")
    return r.content


# ---------- DER / CMS ----------

def der_len(buf, off):
    first = buf[off]
    if first < 0x80:
        return 1, first
    n = first & 0x7F
    return 1 + n, int.from_bytes(buf[off + 1:off + 1 + n], "big")


def iter_tlv(buf, start, end):
    off = start
    while off < end:
        tlv_start = off
        tag = buf[off]
        off += 1
        if tag & 0x1F == 0x1F:
            while buf[off] & 0x80:
                off += 1
            off += 1
        lhdr, clen = der_len(buf, off)
        off += lhdr
        yield tag, tlv_start, off, off + clen
        off += clen


def certs_from_masterlist(ml_der):
    """CscaMasterList ::= SEQUENCE { version INTEGER, certList SET OF Certificate }"""
    out = []
    for tag, _s, c_s, c_e in iter_tlv(ml_der, 0, len(ml_der)):
        if tag != 0x30:
            continue
        for t2, _s2, cs2, ce2 in iter_tlv(ml_der, c_s, c_e):
            if t2 == 0x31:
                for t3, s3, _cs3, ce3 in iter_tlv(ml_der, cs2, ce2):
                    if t3 == 0x30:
                        out.append(bytes(ml_der[s3:ce3]))
        break
    return out


def cms_masterlist_certs(cms_bytes, name):
    """Extrait les certificats d'une masterlist CMS ; la signature CMS est vérifiée
    (contre le signataire embarqué ; -noverify ne saute que le chaînage du signataire)."""
    with tempfile.TemporaryDirectory() as td:
        inp, outp = Path(td) / "in.cms", Path(td) / "out.der"
        inp.write_bytes(cms_bytes)
        r = subprocess.run(
            ["openssl", "cms", "-verify", "-noverify", "-binary",
             "-inform", "DER", "-in", str(inp), "-out", str(outp)],
            capture_output=True,
        )
        if r.returncode != 0:
            sys.exit(f"ERREUR: signature CMS invalide pour {name}: {r.stderr.decode(errors='replace')[:300]}")
        certs = certs_from_masterlist(outp.read_bytes())
    print(f"  {name}: {len(certs)} certificats (signature CMS OK)")
    return certs


def zip_members(data, suffix):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {n: z.read(n) for n in z.namelist() if n.lower().endswith(suffix)}


def sanitize(s, maxlen=40):
    return re.sub(r"[^A-Za-z0-9._-]+", "-", s).strip("-.")[:maxlen] or "sans-nom"


def cn_of(name_obj):
    return next((a.value for a in name_obj.get_attributes_for_oid(NameOID.COMMON_NAME)), None) \
        or (name_obj.rdns and name_obj.rdns[0].rfc4514_string()) or "sans-CN"


def main():
    print("== 1/4 Téléchargements ==")
    consent = requests.get(ICAO_DISCOVERY, headers=UA, timeout=60).text
    m = re.search(r"MasterList/ICAO_ML_\d+\.ml", consent)
    if not m:
        sys.exit("ERREUR: nom de la masterlist ICAO introuvable sur la page de consentement")
    icao_ml = fetch(ICAO_BASE + m.group(0), f"ICAO {m.group(0)}")

    bsi_zip = fetch(BSI_ZIP, "BSI GermanMasterList.zip")
    mls = zip_members(bsi_zip, ".ml")
    if len(mls) != 1:
        sys.exit(f"ERREUR: {len(mls)} fichiers .ml dans le zip BSI (1 attendu): {list(mls)}")
    (bsi_name, bsi_ml), = mls.items()

    ants_ders = {}
    for name, url in ANTS.items():
        crts = zip_members(fetch(url, f"ANTS {name}"), ".crt")
        crts = {n: b for n, b in crts.items() if "test" not in n.lower()}
        if len(crts) != 1:
            sys.exit(f"ERREUR: {len(crts)} .crt dans le zip ANTS {name} (1 attendu): {list(crts)}")
        ants_ders[name] = next(iter(crts.values()))

    print("== 2/4 Extraction des masterlists ==")
    icao_certs = cms_masterlist_certs(icao_ml, "ICAO")
    bsi_certs = cms_masterlist_certs(bsi_ml, f"BSI {bsi_name}")
    icao_h = {hashlib.sha256(c).hexdigest() for c in icao_certs}
    bsi_h = {hashlib.sha256(c).hexdigest() for c in bsi_certs}

    print("== 3/4 Contrôle croisé des CSCA françaises (canaux indépendants) ==")
    for name, der in ants_ders.items():
        h = hashlib.sha256(der).hexdigest()
        where = [s for s, hs in (("ICAO", icao_h), ("BSI", bsi_h)) if h in hs]
        print(f"  {name}: présent dans {where or 'ANTS uniquement'}")
        # eID-FRANCE (CSCA e-ID) n'est pas un cert « document de voyage » : absent des
        # masterlists, c'est attendu. Les CSCA passeport récentes doivent recouper.
        if name.startswith("CSCA-FRANCE") and name >= "CSCA-FRANCE_2015" and not where:
            sys.exit(f"ERREUR: {name} absent des masterlists ICAO et BSI — canal ANTS suspect, dépôt refusé")

    print("== 4/4 Dédup, validation stricte, écriture (1 fichier PEM par certificat) ==")
    today = datetime.date.today().isoformat()
    icao_name = m.group(0).split("/")[-1]
    source_detail = {
        "icao": f"masterlist ICAO {icao_name} ({ICAO_BASE}{m.group(0)})",
        "bsi": f"masterlist BSI {bsi_name} ({BSI_ZIP})",
    }
    ants_by_hash = {hashlib.sha256(der).hexdigest(): name for name, der in ants_ders.items()}

    seen, kept, bad = {}, [], 0
    countries = collections.Counter()
    for der in icao_certs + bsi_certs + list(ants_ders.values()):
        h = hashlib.sha256(der).hexdigest()
        sources = [s for s, hs in (("icao", icao_h), ("bsi", bsi_h)) if h in hs]
        if h in ants_by_hash:
            sources.append("ants")
        if h in seen:
            continue
        seen[h] = True
        try:
            cert = x509.load_der_x509_certificate(der)
            c = next((a.value for a in cert.subject.get_attributes_for_oid(NameOID.COUNTRY_NAME)), "XX")
        except Exception:
            bad += 1
            continue
        countries[c] += 1
        kept.append((c, cert, h, sources))

    if countries["FR"] < 5:
        sys.exit(f"ERREUR: seulement {countries['FR']} certs FR (>=5 attendus) — dépôt refusé")

    # Le script possède les .pem et le manifest du dossier (README.txt est préservé).
    for old in list(OUT_DIR.glob("*.pem")) + [OUT_DIR / "MANIFEST.tsv"]:
        if old.exists():
            old.unlink()

    kept.sort(key=lambda t: (t[0], cn_of(t[1].subject), t[1].not_valid_before_utc, t[2]))
    manifest = [
        f"# Magasin CSCA — généré le {today} par tools/csca/update_csca.py",
        f"# Éditions : {source_detail['icao']}",
        f"#            {source_detail['bsi']}",
        f"#            ANTS : {', '.join(f'{n} ({u})' for n, u in ANTS.items())}",
        "fichier\tpays\tsujet\tvalide_du\tvalide_au\tsha256\tsources",
    ]
    used_names = set()
    for country, cert, h, sources in kept:
        fname = f"{sanitize(country, 8)}_{sanitize(cn_of(cert.subject))}_{'-'.join(sources)}_{h[:8]}.pem"
        if fname.lower() in used_names:  # collision de préfixe SHA (improbable) -> empreinte longue
            fname = fname.replace(f"_{h[:8]}.pem", f"_{h[:16]}.pem")
        used_names.add(fname.lower())
        detail = [source_detail.get(s) or f"ANTS {ants_by_hash[h]} ({ANTS[ants_by_hash[h]]})" for s in sources]
        header = (
            f"# Certificat CSCA — ancre de confiance passive authentication (ICAO 9303-11)\n"
            f"# Sujet      : {cert.subject.rfc4514_string()}\n"
            f"# Émetteur   : {cert.issuer.rfc4514_string()}\n"
            f"# Série      : {cert.serial_number:#x}\n"
            f"# Validité   : {cert.not_valid_before_utc:%Y-%m-%d} -> {cert.not_valid_after_utc:%Y-%m-%d}\n"
            f"# SHA-256    : {h}\n"
            + "".join(f"# Source     : {d}\n" for d in detail)
            + f"# Téléchargé : {today} (voir MANIFEST.tsv et README.txt)\n"
        )
        (OUT_DIR / fname).write_bytes(header.encode() + cert.public_bytes(Encoding.PEM))
        manifest.append(
            f"{fname}\t{country}\t{cert.subject.rfc4514_string()}\t"
            f"{cert.not_valid_before_utc:%Y-%m-%d}\t{cert.not_valid_after_utc:%Y-%m-%d}\t{h}\t{'+'.join(sources)}"
        )
    (OUT_DIR / "MANIFEST.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8", newline="\n")

    print(f"\n{OUT_DIR}")
    print(f"{len(kept)} certificats uniques ({bad} illisibles écartés), FR={countries['FR']}, "
          f"{len(list(OUT_DIR.glob('*.pem')))} fichiers .pem + MANIFEST.tsv")
    print("Pensez à mettre à jour la date et les éditions dans assets/csca/README.txt,")
    print("puis lancez les tests : ./gradlew testDebugUnitTest")


if __name__ == "__main__":
    main()
