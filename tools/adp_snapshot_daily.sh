#!/bin/zsh
# Daily ADP + projection snapshot, meant for launchd (tools/launchd/*.plist).
# KNOWN BLOCKER (2026-09-02): a launchd agent cannot READ files under ~/Documents -
# macOS privacy protection; `ls` works, `head`/`zsh script` get "Operation not
# permitted". Until Justin grants /bin/zsh access to Documents (System Settings >
# Privacy & Security > Files and Folders, or Full Disk Access), the snapshot runs
# from the life repo's /today brief in draft season instead.
# Appends today's rows to data/adp-snapshots.csv and data/projection-snapshots.csv
# and commits ONLY those two files. Idempotent: AdpSnapshot records the ADP once a
# day and each projection feed once a day, so a rerun after a fix fills in only
# the feeds that failed. A failed feed makes the run exit non-zero; its status is
# logged, and whatever did land is still committed (TRAPS #147: the archive sat
# dead for three weeks behind an exit nobody read). Log:
# data/logs/adp-snapshot.log. Install / remove:
#   cp tools/launchd/com.jbondurant.fantasyFootball.adpsnapshot.plist ~/Library/LaunchAgents/
#   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jbondurant.fantasyFootball.adpsnapshot.plist
#   launchctl bootout   gui/$(id -u)/com.jbondurant.fantasyFootball.adpsnapshot
set -u -o pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO" || exit 1
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
export PATH="$JAVA_HOME/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"
LOG="$REPO/data/logs/adp-snapshot.log"; mkdir -p "$(dirname "$LOG")"
{
  echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
  ./gradlew run -Pmain=AdpSnapshot -q 2>&1 | grep -v '^SLF4J\|^WARNING'
  run_status=${pipestatus[1]}
  if [ "$run_status" -ne 0 ]; then
    echo "AdpSnapshot EXIT $run_status - a feed failed to archive; see FAILED lines above"
  fi
  if ! git diff --quiet -- data/adp-snapshots.csv data/projection-snapshots.csv; then
    git add data/adp-snapshots.csv data/projection-snapshots.csv
    git commit -q -m "adp snapshot $(date +%F) (daily job)" && echo "committed"
  else
    echo "nothing new to commit"
  fi
} >> "$LOG" 2>&1
