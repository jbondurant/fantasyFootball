#!/bin/zsh
# Daily ADP + projection snapshot, meant for launchd (tools/launchd/*.plist).
# KNOWN BLOCKER (2026-09-02): a launchd agent cannot READ files under ~/Documents -
# macOS privacy protection; `ls` works, `head`/`zsh script` get "Operation not
# permitted". Until Justin grants /bin/zsh access to Documents (System Settings >
# Privacy & Security > Files and Folders, or Full Disk Access), the snapshot runs
# from the life repo's /today brief in draft season instead.
# Appends today's rows to data/adp-snapshots.csv and data/projection-snapshots.csv
# and commits ONLY those two files. Idempotent: AdpSnapshot refuses to record a
# day twice. Log: data/logs/adp-snapshot.log. Install / remove:
#   cp tools/launchd/com.jbondurant.fantasyFootball.adpsnapshot.plist ~/Library/LaunchAgents/
#   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jbondurant.fantasyFootball.adpsnapshot.plist
#   launchctl bootout   gui/$(id -u)/com.jbondurant.fantasyFootball.adpsnapshot
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO" || exit 1
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
export PATH="$JAVA_HOME/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"
LOG="$REPO/data/logs/adp-snapshot.log"; mkdir -p "$(dirname "$LOG")"
{
  echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
  ./gradlew run -Pmain=AdpSnapshot -q 2>&1 | grep -v '^SLF4J\|^WARNING'
  if ! git diff --quiet -- data/adp-snapshots.csv data/projection-snapshots.csv; then
    git add data/adp-snapshots.csv data/projection-snapshots.csv
    git commit -q -m "adp snapshot $(date +%F) (daily job)" && echo "committed"
  else
    echo "nothing new to commit"
  fi
} >> "$LOG" 2>&1
