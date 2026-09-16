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
# The trailing backslash is not decoration. A running instance's own command
# line carries "--gameDir C:\...\Instances\26.2 --assetsDir C:\..." with NO
# separator after the folder, so a pattern that stops at the next backslash
# swallows the rest of the argument and yields "26.2 --assetsDir C:". That never
# equals the folder name, the guard misses, and the script tries to replace a jar
# under a live game (which fails with "Permission denied" on the rm, mid-run --
# seen 2026-09-16). Requiring the trailing backslash matches the deeper paths in
# the same command line instead (natives, libraries), which DO have one, and
# keeps names with spaces whole.
RUNNING_CMDS="$(powershell.exe -NoProfile -Command \
  "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' } | ForEach-Object { \$_.CommandLine }" \
  2>/dev/null | tr -d '\r' || true)"

# Ask the question the right way round: we KNOW the folder name, so look for it
# in the running command lines rather than parsing a name out of them. Every
# attempt to READ the name failed the same way -- the launcher's own argument is
#   --gameDir C:\...\Instances\26.2 --assetsDir C:\...
# with no separator after the folder, so the pattern ran on into the next
# argument and produced "26.2 --assetsDir C:", which matches no instance. The
# guard then missed and a deploy started under a live game (2026-09-16): the rm
# failed with "Permission denied" halfway through the estate.
#
# The boundary matters as much as the name: without it "26.2" also matches
# "26.2.test". Names with spaces ("MobHealth - Forge") stay whole either way,
# because nothing here splits on whitespace.
instance_running() {
    local name="$1" padded
    # Fixed strings, no regex: an instance name can hold dots ("26.2") and
    # spaces ("MobHealth - Forge"), and escaping them for grep -E is how the
    # previous attempt died -- its bracket expression opened with "[." , which
    # POSIX reads as a collating symbol, so sed failed and EVERY instance came
    # back "not running". A guard that errors must never read as "safe".
    #
    # Three boundaries are all the launcher can put after the folder name: a
    # deeper path, a closing quote, or the end of the argument. The padding
    # gives that last one something to match.
    padded="$(printf '%s' "$RUNNING_CMDS" | sed 's/$/ /')"
    printf '%s' "$padded" | grep -qF -- "Instances\\$name\\" && return 0
    printf '%s' "$padded" | grep -qF -- "Instances\\$name\"" && return 0
    printf '%s' "$padded" | grep -qF -- "Instances\\$name " && return 0
    return 1
}
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
    if instance_running "$name"; then
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
