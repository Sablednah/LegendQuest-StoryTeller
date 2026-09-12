#!/usr/bin/env bash
# Put the right jar into EVERY CurseForge instance that already has one --
# or, with --check, just say which ones are behind and change nothing.
#
# deploy.sh handles one instance from one build, which is what you want
# mid-loop. This is the other job: after a release, bring the whole estate up
# to it. You cannot build all three versions from one checkout, so this takes a
# directory that already holds the tagged jars:
#
#   ./deploy-all.sh /path/to/dir/with/storyteller-*+mc*.jar
#   ./deploy-all.sh --check /path/to/dir        # audit only, copies nothing
#
# Instances are chosen by "already has a storyteller jar", so this never
# installs the mod somewhere new -- it only updates what is already there.
#
# --check exists because the estate drifted for two days without anyone
# knowing: three 26.x instances sat on a build from 10 Sep while the question
# "is the new one everywhere?" could only be answered by hand, jar by jar. An
# audit nobody can run is an audit nobody runs.
set -euo pipefail

INSTANCES="${ST_INSTANCES:-/mnt/c/Users/darre/curseforge/minecraft/Instances}"

CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
BUILT="${1:-}"
[ -n "$BUILT" ] && [ -d "$BUILT" ] || {
    echo "usage: $0 [--check] <dir containing storyteller-*+mc*.jar>" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Route on the instance's OWN gameVersion, not its folder name.
#
# StoryTeller's 1.21.11 instance is called "MobHealth - Forge" -- named after a
# different mod entirely -- so guessing from the name gets it wrong. And "26.2"
# and "26.2.test" are both Minecraft 26.2, which the names only half say.
# minecraftinstance.json knows.
#
# That file is written UTF-8 WITH BOM, so it must be read as utf-8-sig; plain
# utf-8 fails on the very first character with a JSONDecodeError that looks
# like a corrupt file.
# ---------------------------------------------------------------------------
mc_version_of() {
    python3 -c "
import json,sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8-sig'))
    print(d.get('gameVersion') or d.get('baseModLoader', {}).get('minecraftVersion') or '')
except Exception:
    print('')
" "$1/minecraftinstance.json" 2>/dev/null || true
}

# See deploy.sh for why these are glob loops and `|| true` rather than
# pipelines: under `set -euo pipefail` a pipeline whose first element fails
# kills the script silently, and the cases that fail are a first deploy and a
# pre-stamp jar -- exactly the two this has something to say about.
stampof() {
    local out
    out=$(unzip -p "$1" storyteller/build.properties 2>/dev/null || true)
    printf '%s' "$out" | sed -n 's/^commit=//p' | head -1 || true
}

# One process query for the whole sweep rather than one per instance. A warning
# is not a guard; this has to stop the deploy.
RUNNING="$(powershell.exe -NoProfile -Command \
  "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' } | ForEach-Object { \
   \$m=[regex]::Match(\$_.CommandLine,'Instances\\\\([^\\\\\"]+)'); if (\$m.Success) { \$m.Groups[1].Value } }" \
  2>/dev/null | tr -d '\r' | sort -u || true)"
echo "Running instances: ${RUNNING:-(none)}"
[ "$CHECK" -eq 1 ] && echo "(--check: reporting only, nothing will be copied)"
echo

fail=0
behind=0
for dir in "$INSTANCES"/*/; do
    name="$(basename "$dir")"
    mods="$dir/mods"

    # Glob loop, not `ls ... >/dev/null`: quoting a path with spaces through ls
    # is one more thing to get wrong, and this needs the filename anyway.
    have=""
    for f in "$mods"/storyteller-*.jar; do
        if [ -f "$f" ]; then have="$f"; break; fi
    done
    [ -n "$have" ] || continue

    mc="$(mc_version_of "$dir")"
    if [ -z "$mc" ]; then
        echo "?? $name: could not read its Minecraft version -- SKIPPED"; fail=1; continue
    fi

    jar=""
    for f in "$BUILT"/storyteller-*+mc"$mc".jar; do
        if [ -f "$f" ]; then jar="$f"; break; fi
    done
    if [ -z "$jar" ]; then
        echo "?? $name: no jar for Minecraft $mc in $BUILT -- SKIPPED"; fail=1; continue
    fi

    oldstamp="$(stampof "$have")"
    newstamp="$(stampof "$jar")"

    if [ "$oldstamp" = "$newstamp" ] && [ -n "$oldstamp" ]; then
        printf "== %-22s mc %-8s already on %s\n" "$name" "$mc" "$newstamp"
        continue
    fi
    behind=$((behind + 1))

    if [ "$CHECK" -eq 1 ]; then
        printf ">> %-22s mc %-8s %s -> %s  BEHIND\n" \
            "$name" "$mc" "${oldstamp:-unstamped}" "${newstamp:-unstamped}"
        continue
    fi
    if echo "$RUNNING" | grep -qxF "$name"; then
        echo "!! $name is RUNNING -- refusing to overwrite underneath a live game"; fail=1; continue
    fi

    rm -f "$mods"/storyteller-*.jar
    cp "$jar" "$mods/"
    base="$(basename "$jar")"

    # A half-written copy looks identical to a good one in a directory listing.
    cmp -s "$jar" "$mods/$base"            || { echo "!! $name: copy does not match the source"; fail=1; continue; }
    unzip -t "$mods/$base" >/dev/null 2>&1 || { echo "!! $name: deployed jar is not a valid zip"; fail=1; continue; }
    printf ">> %-22s mc %-8s %s -> %s\n" "$name" "$mc" "${oldstamp:-unstamped}" "${newstamp:-unstamped}"

    case "$newstamp" in
        *-dirty) echo "   ^^ that jar was built from uncommitted changes. Fine mid-loop; for a"
                 echo "      release it means the estate is running code no commit describes." ;;
    esac
done

echo
if [ "$CHECK" -eq 1 ]; then
    # A skipped instance is NOT a current one. Reporting "everything is up to
    # date" while four instances were skipped for want of a jar is the failure
    # mode this whole script exists to prevent, and the first --check run did
    # exactly that against an empty directory.
    if [ "$fail" -ne 0 ]; then
        echo "Could not check every instance -- see ?? lines above. Nothing was copied."
    elif [ "$behind" -eq 0 ]; then
        echo "Every instance is already on these builds."
    else
        echo "$behind instance(s) behind. Re-run without --check to update them."
    fi
elif [ "$fail" -eq 0 ]; then
    echo "All instances deployed and verified."
else
    echo "Finished WITH PROBLEMS -- see above."
fi
exit "$fail"
