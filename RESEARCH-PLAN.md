# What we would need to know to build the missing model

Written 2026-08-31, the night before the draft. Nothing here is on the draft's
critical path; the RUNBOOK is settled and every finding below is post-season
work. Written down because the alternative is that it lives in one session's
scrollback and gets re-derived from nothing next August.

## The model that is missing

`WeeklyStarterValue` promotes a bench man through exactly one channel: a starter
drawn `!up()`, which is injury. Survivors are ranked by preseason expectation,
which never updates. So a starter who plays seventeen games and disappoints
keeps his ranking and keeps starting, and a bench man who breaks out is never
promoted. Justin named this on 2026-08-31: *"some starters bust, and some bench
players boom."*

Measured the same night, that blindness is **not** currently costing anything —
at pick 127 the objective bids 52.9 for the best free receiver against a
measured ~40, so it over-prices rather than under-prices him, and the ordering
(a back or receiver worth ~3x a defence) is agreed by both the model and the
outcomes. **So the case for building this is not that the current answer is
wrong.** It is that the current answer is right for a reason we cannot inspect,
and that is a bad place to leave a model you intend to keep using.

## The five questions that would settle it

Ordered by how much they would change the model, not by how interesting.

1. **How fast is a bust detectable?** A promotion rule can only use what a
   manager knew that week. If four weeks of evidence separates a bust from a
   slow start, the channel is worth much less than if two do. This is the
   hinge: everything else in the model is downstream of the detection lag.
2. **What does a real manager actually do?** Promotion delay, waiver
   aggressiveness, drop behaviour. We have five seasons of this league's real
   rosters; we have never looked at the *transactions*, only the drafts.
3. **How good is the wire, week to week?** The 8.7 points/week streaming rate
   is load-bearing - it is why a drafted defence prices at -12.9 - and it rests
   on one calculation nobody has stress-tested.
4. **Would any of it change a pick?** Asked FIRST in the build, not last. If a
   three-channel model reorders nothing, that is a finding worth having cheaply
   rather than after a month of work.
5. **How do you validate any of this?** The season is the unit of independent
   randomness. Thirteen seasons puts the bar at ~101 points and slot and
   opponent variation are exhausted. Pick-level questions have 50x the data -
   which is why the bench and defence answers came out clean while every
   plan-level comparison drowned. A model whose claims are pick-level is
   testable; one whose claims are season-level mostly is not.

## Three streams of information, and where each actually lives

**Fantasy football, the domain.** Bust and boom rates by position and ADP tier;
in-season promotion behaviour; how thin the waiver wire really is. Most of this
is measurable from data already on disk - thirteen seasons of boards and
outcomes - and should be measured before anything is read. Outside sources are
for framing and for rates we cannot compute, not for numbers we can.

**Modelling and AI, the method.** The specific shapes this problem takes:
sequential decision-making under uncertainty, option and insurance valuation,
Bayesian updating for the in-season learning rule, and above all inference with
a tiny effective sample. The last one is the transferable part - this repo has
now been burned by selection optimism (+126 measured), by a rank correlation
used where a regression slope belonged, and by three separate results that
looked real until they got an honest error bar.

**The project's own history.** 5,400 archived conversations, MODEL.md, and the
git log. The standing warning applies: a claim in an archived chat that "I wrote
a program that..." is an idea, not an artifact. Verify before believing.

## What would make this fail

Adding channels without validation is how a model overfits, and this one already
has more free parameters than the data can identify. The trust coefficient could
not be distinguished from 1.0 at five seasons; a bust-detection lag and a
promotion rule are two more knobs with the same problem. **Any new channel has
to be paid for by a pick-level prediction that can be checked**, not by a
season-level score that will land inside the bar whatever we do.
