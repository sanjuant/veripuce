#!/usr/bin/env bash
# Preuve de fonctionnement de Veripuce, à coller dans le compte rendu d'une tâche.
#   bash .claude/skills/verify/verify.sh          # JVM + lint + APK debug/release + garde-fous
#   bash .claude/skills/verify/verify.sh --rerun  # force la ré-exécution des tests (preuve anti-flaky)
#   bash .claude/skills/verify/verify.sh --device # + installation et lancement via adb
# Sortie : un bloc « VERIFY » lisible, code 0 si tout est vert, 1 sinon.
set -u
cd "$(git rev-parse --show-toplevel)" || exit 1

if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -n "${LOCALAPPDATA:-}" ]; then
    export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # worktree neuf : local.properties absent
fi
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
ADB="$SDK/platform-tools/adb"

device=0; rerun=""
for a in "$@"; do
    case "$a" in
        --device) device=1 ;;
        --rerun) rerun="--rerun" ;;
        *) echo "option inconnue : $a" >&2; exit 2 ;;
    esac
done

ok=1
fail() { echo "  ✗ $*"; ok=0; }
pass() { echo "  ✓ $*"; }

echo "VERIFY veripuce @ $(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo ' +modifs')"

# 1. Tests JVM (parsing MRZ/TLV, propriétés, passive auth sur jeux de référence publics BSI/LOES
#    = notre « fausse puce »), lint Android, APK debug.
start=$(date +%s)
log=$(mktemp)
if ./gradlew --console=plain -q testDebugUnitTest $rerun lintDebug assembleDebug >"$log" 2>&1; then
    pass "gradle testDebugUnitTest${rerun:+ $rerun} lintDebug assembleDebug ($(( $(date +%s) - start )) s)"
else
    fail "gradle en échec ($(( $(date +%s) - start )) s) :"; grep -v '^\s*at ' "$log" | tail -30 | sed 's/^/      /'
fi
rm -f "$log"

# 2. Bilan des tests (compte réel lu dans les rapports JUnit, pas dans la sortie console).
res=app/build/test-results/testDebugUnitTest
if ls "$res"/*.xml >/dev/null 2>&1; then
    read -r t s f e < <(grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$res"/*.xml |
        awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print t, s, f, e}')
    msg="tests JVM : $t exécutés, $f échecs, $e erreurs, $s ignorés"
    if [ "$f" -eq 0 ] && [ "$e" -eq 0 ] && [ "$s" -eq 0 ] && [ "$t" -gt 0 ]; then pass "$msg"; else fail "$msg"; fi
    prop=$(grep -o 'tests="[0-9]*"' "$res/TEST-fr.veripuce.app.MrzPropertyTest.xml" 2>/dev/null | tr -dc 0-9)
    [ -n "$prop" ] && pass "dont $prop propriétés MRZ / ICAO 9303 / BER-TLV (MrzPropertyTest)" || fail "MrzPropertyTest absent"
else
    fail "aucun rapport de test"
fi

# 3. APK + garde-fous de confidentialité sur le manifeste fusionné (ce qui part vraiment sur le téléphone).
apk=app/build/outputs/apk/debug/veripuce-debug.apk
[ -f "$apk" ] && pass "APK $apk ($(( $(wc -c <"$apk") / 1024 / 1024 )) Mo)" || fail "APK debug absent"
manifest=app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
if [ -f "$manifest" ]; then
    grep -q 'android.permission.INTERNET' "$manifest" && fail "permission INTERNET présente dans le manifeste fusionné" ||
        pass "aucune permission INTERNET (manifeste fusionné)"
    grep -q 'android:allowBackup="true"' "$manifest" && fail "allowBackup=true" || pass "pas de sauvegarde système (allowBackup)"
else
    fail "manifeste fusionné introuvable"
fi

# 3b. Build release : le journal des lectures (debug uniquement) ne doit y laisser AUCUNE trace —
#     ni classe, ni chaîne, ni FileProvider, ni INTERNET. Contrôle positif sur l'APK debug pour
#     prouver que la recherche n'est pas vide de sens.
dexgrep() { unzip -p "$1" 'classes*.dex' 2>/dev/null | grep -a -c "$2"; }
start=$(date +%s)
log=$(mktemp)
if ./gradlew --console=plain -q assembleRelease >"$log" 2>&1; then
    pass "gradle assembleRelease ($(( $(date +%s) - start )) s)"
    rel=$(ls app/build/outputs/apk/release/veripuce-release*.apk 2>/dev/null | head -1)
    relman=app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
    if [ -n "$rel" ] && [ -f "$relman" ]; then
        [ "$(dexgrep "$apk" 'Lfr/veripuce/app/journal/')" -gt 0 ] && pass "contrôle positif : journal présent dans l'APK debug" ||
            fail "contrôle positif : journal absent de l'APK debug ?"
        [ "$(dexgrep "$rel" 'Lfr/veripuce/app/journal/')" -eq 0 ] && pass "release : aucune classe du journal (dex)" ||
            fail "release : classes fr.veripuce.app.journal présentes"
        [ "$(dexgrep "$rel" 'lectures.txt')" -eq 0 ] && pass "release : aucun chemin de fichier du journal (dex)" ||
            fail "release : chaîne 'lectures.txt' présente"
        unzip -p "$rel" resources.arsc | grep -a -q 'Journal des lectures' && fail "release : ressources du journal présentes" ||
            pass "release : aucune ressource du journal (resources.arsc)"
        grep -q 'FileProvider' "$relman" && fail "release : FileProvider dans le manifeste" ||
            pass "release : pas de FileProvider (manifeste fusionné)"
        grep -q 'android.permission.INTERNET' "$relman" && fail "release : permission INTERNET" ||
            pass "release : aucune permission INTERNET"
    else
        fail "APK ou manifeste release introuvable"
    fi
else
    fail "assembleRelease en échec :"; grep -v '^\s*at ' "$log" | tail -30 | sed 's/^/      /'
fi
rm -f "$log"

# 4. Optionnel : appareil ou émulateur déjà démarré (ce script n'en démarre jamais).
if [ $device -eq 1 ]; then
    serial=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [ -z "$serial" ]; then
        fail "--device : aucun appareil adb connecté (démarrer l'émulateur ou brancher un téléphone)"
    else
        A=("$ADB" -s "$serial")
        "${A[@]}" install -r "$apk" >/dev/null 2>&1 && pass "installé sur $serial ($("${A[@]}" shell getprop ro.product.model | tr -d '\r'))" ||
            fail "installation refusée sur $serial (émulateur x86 sans traduction ARM ? l'APK n'embarque que arm64/armv7)"
        "${A[@]}" logcat -c
        "${A[@]}" shell am start -W -n fr.veripuce.app/.MainActivity >/dev/null 2>&1
        "${A[@]}" shell pidof fr.veripuce.app >/dev/null && pass "MainActivity démarrée" || fail "l'app ne tourne pas après am start"
        shot="$(git rev-parse --git-path verify-main.png)"
        "${A[@]}" exec-out screencap -p >"$shot" && pass "capture : $shot"
        "${A[@]}" shell pm list features | grep -q 'feature:android.hardware.nfc$' && pass "NFC présent sur l'appareil" ||
            echo "  · pas de NFC sur cet appareil : écran attendu « NFC indisponible », lecture puce NON testée"
        "${A[@]}" logcat -d -b crash | grep -q 'fr.veripuce.app' && fail "crash dans logcat -b crash" || pass "aucun crash (logcat -b crash)"
    fi
fi

[ $ok -eq 1 ] && echo "VERIFY: VERT" || echo "VERIFY: ROUGE"
[ $ok -eq 1 ]
