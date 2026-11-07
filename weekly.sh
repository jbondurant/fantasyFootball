#!/bin/bash
# THE WEEKLY LOOP IN ONE COMMAND (2026-09-29). Justin: "is there a program that
# can run, without an agent and find the trades?" Each piece already was one;
# this runs them in RUNBOOK's order - the news, the title odds, who else wants
# whom on the wire, the wire, the trades, then the
# page - one after another, so no two gradle runs overlap, and stops at the
# first that fails. Tuesday morning, before the noon waiver run:
#
#     ./weekly.sh        then open data/console-<season>-w<week>.html
set -u
cd "$(dirname "$0")"
log=$(mktemp)
for tool in NewsCheck TitleOdds FaabRivals TuesdaySwap TradeMarket LeagueConsole; do
    echo "=== $tool"
    ./gradlew run -Pmain="$tool" --no-daemon -q > "$log" 2>&1
    status=$?
    grep -v '^SLF4J\|^WARNING\|warning: \[\|^Note:' "$log"
    if [ "$status" -ne 0 ]; then
        echo "$tool failed (exit $status); the tools after it did not run"
        exit 1
    fi
done
rm -f "$log"
