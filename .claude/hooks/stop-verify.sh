#!/usr/bin/env bash
# Hook Stop de Veripuce : tests JVM (compilation comprise) + lint Android avant de rendre la main.
#
# - Rien à faire si l'arbre n'a pas changé depuis la dernière vérification verte (signature
#   HEAD + diff + fichiers non suivis, stockée dans le .git du worktree) : coût nul en Q&R.
# - Rouge -> exit 2 : l'agent reçoit l'échec et doit corriger avant de s'arrêter.
# - 3 tours rouges d'affilée -> on laisse s'arrêter (règle « stop après 3 tours rouges »).
# - Dépassement de temps (build à froid d'un worktree neuf) -> avertissement, non bloquant.
set -u

input=$(cat)
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
cd "$root" 2>/dev/null || exit 0
[ -x ./gradlew ] || exit 0

field() { printf '%s' "$input" | grep -o "\"$1\" *: *[^,}]*" | head -1 | sed 's/^[^:]*: *//; s/"//g'; }
session=$(field session_id)
active=$(field stop_hook_active)

counter="$(git rev-parse --git-path veripuce-stop-reds 2>/dev/null)"
stamp="$(git rev-parse --git-path veripuce-verified 2>/dev/null)"
[ "$active" = "true" ] || rm -f "$counter"

# Signature de l'état à vérifier (sources, ressources, scripts Gradle).
paths=(app gradle build.gradle.kts settings.gradle.kts gradle.properties)
sig=$({
    git rev-parse HEAD
    git diff HEAD -- "${paths[@]}"
    git ls-files -o --exclude-standard -z -- "${paths[@]}" | xargs -0 -r git hash-object --
} 2>/dev/null | git hash-object --stdin)
if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$sig" ]; then exit 0; fi

# SDK Android : local.properties est ignoré par git, donc absent d'un worktree neuf.
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -n "${LOCALAPPDATA:-}" ]; then
    export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
fi

log="$(git rev-parse --git-path veripuce-stop-verify.log)"
start=$(date +%s)
timeout 170 ./gradlew --console=plain -q testDebugUnitTest lintDebug >"$log" 2>&1
rc=$?
elapsed=$(( $(date +%s) - start ))

if [ $rc -eq 0 ]; then
    printf '%s' "$sig" >"$stamp"
    rm -f "$counter"
    exit 0
fi

if [ $rc -eq 124 ]; then
    echo "verify: testDebugUnitTest + lintDebug > 170 s (build à froid ?), non bloquant. Relancer : ./gradlew testDebugUnitTest lintDebug" >&2
    exit 0
fi

reds=$(( $(cat "$counter" 2>/dev/null || echo 0) + 1 ))
echo "$reds" >"$counter"

{
    echo "verify ROUGE (tour $reds/3, ${elapsed}s) : ./gradlew testDebugUnitTest lintDebug"
    failed=$(grep -l '<failure' app/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | sed 's#.*/TEST-##; s#\.xml$##')
    [ -n "$failed" ] && echo "Classes de test en échec : $failed"
    grep -v '^\s*at ' "$log" | tail -40
} >&2

if [ "$reds" -ge 3 ]; then
    echo "3 tours rouges sur la vérification : arrête-toi, ne masque pas l'échec (ni sleep, ni retry, ni skip) et rends la tâche en échec avec le diagnostic." >&2
    rm -f "$counter"
    exit 0
fi
exit 2
