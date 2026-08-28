# DRAFT CARD — Tue Sep 1, 20:45 · slot 7

**Keepers (free, off-board):** Tuten RB · Purdy QB
**My picks:** 7 · 18 · 31 · 42 · 55 · 66 · 79 · 90 · 103

---

## The one command

    ./gradlew run -Pmain=LiveCommittee

~13s at pick 7, under 1s by round 9. Run it once BEFORE pick 7 (18s warm-up).

**All engines agree + KN PROVEN** → take it.
**Split, or KN unproven** → contested; any listed option is fine. ~2 per draft.

---

## Fallback sequence (only if the tool is dead — costs ~9 pts)

| R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|----|----|----|----|----|----|----|----|----|
| RB | RB | RB | WR | WR | WR | TE | QB | best |

R2 is RB by only ~2 pts — if a WR is clearly better on the actual board, take him.

---

## Position crossover

**RB > WR through pick 31. WR > RB from pick 42 on** (by 12–15 pts).
Take backs early, receivers late. TE is flat all draft (~143) — no urgency.

---

## Rounds 10–16 — STASH YOUNG QBs

Late QBs become startable next year **41%** of the time vs 15–19% all other
positions. 9 of the 10 best stashes in league history were QBs. Young beats
veteran 24% vs 15%. *(This is how Tuten happened — r12 pick, r12 keeper.)*

**Targets:** Bo Nix · Jaxson Dart · Tyler Shough · Cam Ward
**If somehow there:** Mahomes · Stafford · Goff — startable at a stash price

---

## Watch

- **JFMarino (slot 8) autodrafting** — he picks 17, right before my 18.
  If on autopilot he takes Josh Allen **45%** of the time → Allen's survival
  to my pick 18 falls **90% → 49%**. His pick 8 is right after mine: an
  instant ADP-perfect pick is the tell.
- **No QB urgency.** Prescott 366 · Lawrence 355 · Herbert 347 · Mahomes 345
  essentially never go inside 9 rounds. The r8 QB is insurance, not need.
- **DEF + K:** last two rounds, as always.

---

## If something breaks

1. `./gradlew run -Pmain=DraftPlanner` — slower, single engine, still adaptive
2. The fallback sequence above
3. Best available at a position I still need
