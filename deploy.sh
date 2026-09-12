#!/usr/bin/env bash
# Build StoryTeller and copy the jar into a CurseForge instance.
#
# The target is chosen from the jar's own Minecraft tag, so it follows whichever
# version branch is checked out:
#
#   storyteller-0.1.0+mc1.21.11.jar  -> "MobHealth - Forge"  (the 1.21.11 instance)
#   storyteller-0.1.0+mc26.1.2.jar   -> the "26.1.2" instance
#   storyteller-0.1.0+mc26.2.jar     -> the "26.2" instance
#
# This is the MID-LOOP tool: one build, one instance. After a release, use
# ./deploy-all.sh to bring every instance up at once.
#
# Usage:  ./deploy.sh
#         ST_INSTANCE="/path/to/instance" ./deploy.sh    # override the target
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTANCES="${ST_INSTANCES:-/mnt/c/Users/darre/curseforge/minecraft/Instances}"

# ---------------------------------------------------------------------------
# Pick the JDK from the branch, not from hope.
#
# 26.x ships java-runtime-epsilon to players and will not build on 21. There is
# no system Java on this machine, so JAVA_HOME must be set before gradle runs --
# an unset one does not fall back to something workable, it fails.
# ---------------------------------------------------------------------------
MC_WANT="$(sed -n 's/^minecraft_version=//p' "$ROOT/gradle.properties" | head -1)"
case "$MC_WANT" in
    26.*) JDKS=("/home/sable/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2") ;;
    *)    JDKS=("/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2") ;;
esac
for candidate in "${JDKS[@]}"; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
done
if [ -z "${JAVA_HOME:-}" ]; then
    echo "!! No JDK found for Minecraft $MC_WANT. There is no system Java here." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo ">> Building Minecraft $MC_WANT (JAVA_HOME=$(basename "$JAVA_HOME"))..."
"$ROOT/gradlew" build --console=plain -q

JAR="$(ls -t "$ROOT"/build/libs/storyteller-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)"
[ -n "$JAR" ] || { echo "!! No built jar in build/libs" >&2; exit 1; }
JARNAME="$(basename "$JAR")"

# The +mc tag exists so three same-named jars cannot be confused with each
# other, so it may as well do the routing too. StoryTeller has exactly one
# 1.21.11 instance, and the 26.x instances are named after their version.
MC_TAG=""
[[ "$JARNAME" =~ \+mc([0-9.]+)\.jar$ ]] && MC_TAG="${BASH_REMATCH[1]}"
case "$MC_TAG" in
    ""|1.21.11) TARGET="$INSTANCES/MobHealth - Forge" ;;
    *)          TARGET="$INSTANCES/$MC_TAG" ;;
esac
INSTANCE="${ST_INSTANCE:-$TARGET}"
MODS="$INSTANCE/mods"
NAME="$(basename "$INSTANCE")"

[ -d "$MODS" ] || { echo "!! Instance mods folder not found: $MODS" >&2; exit 1; }

# ---------------------------------------------------------------------------
# REFUSE if that instance is running.
#
# Windows does NOT lock the jar, so the copy silently succeeds and the running
# JVM then dies the moment it lazily loads a class it had not already touched:
# NoClassDefFoundError under a ZipException about an invalid LOC header, with a
# perfectly good jar on disk. It reads as a mod bug and is not one. A guard that
# only warns is not a guard.
#
# The character class must NOT exclude whitespace. The 1.21.11 instance is
# called "MobHealth - Forge", so a \s truncates the name at the first space,
# compares "MobHealth" against the folder, never matches, and the guard passes
# silently while the game is running. That exact bug lived in LegendQuest's copy
# of this script for weeks. Let the name run to the next backslash or quote.
# ---------------------------------------------------------------------------
RUNNING="$(powershell.exe -NoProfile -Command \
  "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' } | ForEach-Object { \
   \$m=[regex]::Match(\$_.CommandLine,'Instances\\\\([^\\\\\"]+)'); if (\$m.Success) { \$m.Groups[1].Value } }" \
  2>/dev/null | tr -d '\r' | sort -u || true)"

if echo "$RUNNING" | grep -qxF "$NAME"; then
    echo "!! '$NAME' is RUNNING. Refusing to overwrite a jar underneath a live game." >&2
    echo "!! Close Minecraft and run this again." >&2
    exit 1
fi

# Say which build is being replaced, and by which. Two jars can carry the same
# filename AND the same version and still differ. The script cannot get this
# wrong -- it removes and copies unconditionally -- so what this guards against
# is a PERSON, or an agent, deciding to skip a deploy because the version
# already reads right.
#
# Both helpers are written around `set -euo pipefail`, which is hostile to the
# two cases that matter most. A pipeline whose FIRST element fails fails the
# whole pipeline under pipefail, and a failed substitution then exits under -e
# with nothing printed. So `ls glob | head` dies on a first deploy, and
# `unzip -p | sed` dies on a pre-stamp jar -- both silently, and both pass every
# test that uses a populated instance and a current jar. A glob loop has no
# pipeline to fail; `|| true` keeps a missing entry from being fatal.
stampof() {
    local out
    out=$(unzip -p "$1" storyteller/build.properties 2>/dev/null || true)
    printf '%s' "$out" | sed -n 's/^commit=//p' | head -1 || true
}
OLDJAR=""
for f in "$MODS"/storyteller-*.jar; do
    if [ -f "$f" ]; then OLDJAR="$f"; break; fi
done
if [ -n "$OLDJAR" ]; then
    OLDSTAMP=$(stampof "$OLDJAR")
    echo ">> Replacing $(basename "$OLDJAR") [build ${OLDSTAMP:-none, predates stamps}]"
else
    echo ">> No existing StoryTeller jar in '$NAME' (first deploy here)"
fi

echo ">> Removing previous StoryTeller jars from '$NAME'..."
rm -f "$MODS"/storyteller-*.jar

cp "$JAR" "$MODS/"

# A half-written copy looks identical to a good one in a directory listing.
cmp -s "$JAR" "$MODS/$JARNAME" || { echo "!! Deployed jar does not match the build." >&2; exit 1; }
unzip -t "$MODS/$JARNAME" >/dev/null 2>&1 || { echo "!! Deployed jar is not a valid zip." >&2; exit 1; }

NEWSTAMP="$(stampof "$JAR")"
echo ">> Deployed $JARNAME ($(stat -c%s "$JAR") bytes) [build ${NEWSTAMP:-unstamped}] to '$NAME'"
case "$NEWSTAMP" in
    *-dirty) echo ">> (built from uncommitted changes -- expected mid-loop, wrong for a release)" ;;
esac
echo ">> Launch that instance in CurseForge to test."
