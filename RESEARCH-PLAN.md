# What we would need to know to build the missing model

Written 2026-08-31, the night before the draft. **This is pre-draft work** -
Justin's call, and he is right that I made it for him. The whole value of the
missing channel is that it might change a pick tomorrow; deferring it to the
post-season is deferring the only version of the question that pays.

That reorders everything below. Question 4 - would any of this change a pick -
stops being the last question and becomes the ONLY deliverable. The other four
are worth answering exactly as far as they move it.

Deadline is hard: the draft is 2026-09-01 20:45, so anything that has not landed
and been verified by that afternoon does not exist. RUNBOOK.md, DraftNight.java
and LiveLateRounds.java stay frozen unless a result clears the measured 125-point
bar, and a late unverified swap is the one genuinely reckless move available.

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

0. **Would a three-channel model reorder any of Justin's actual picks?** Bound
   this FIRST and without waiting for the rates: sweep bust rate and detection
   lag across every plausible value and ask whether the ordering at picks 79,
   90, 103, 114 and 127 ever changes. If no plausible parameter flips a pick,
   the rest is post-season curiosity and Justin drafts as planned. If some do,
   we know precisely which number has to be right.
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

## The method: what the learning rule should be, and how to validate it (2026-08-31)

Answering questions 1, 4 and 5 above with measurements rather than a survey.
Everything below comes from `InSeasonLearning`, which runs on the thirteen
harvested seasons and prints all of it:

    ./gradlew run -Pmain=InSeasonLearning -q      # ~40 s
    data/in-season-learning-2026-08-31.txt

### 1. The rule: Bayesian updating, and its one parameter is measurable

Recommended, over shrinkage-to-a-grand-mean, changepoint detection and a k-week
moving average:

    estimate_i(k) = (kappa * prior_i + games_i(k) * observed_i(k))
                    / (kappa + games_i(k))

The conjugate-normal posterior mean with the preseason projection as the prior.
It is the right shape for three reasons and none of them is that Bayes is
fashionable.

**It has exactly one free parameter and that parameter is a measurable
quantity, not a knob.** `kappa` is the variance ratio sigma^2_within /
sigma^2_between and its units are GAMES: how much evidence it takes to move the
prior halfway. It is estimated from the week-to-week scatter of every
player-season in the harvest - 1,878 of them - and not from anything about
whether the rule wins. Measured, leave-one-season-out, in units of each
position's own season level:

    POS   sd_within  sd_between   kappa    men   LOO min  LOO max
    QB        0.453       0.130   12.14    294     11.47    12.80
    RB        0.642       0.265    5.87    595      5.55     6.18
    WR        0.648       0.197   10.84    750     10.41    11.46
    TE        0.678       0.214   10.09    239      9.11    11.32

**This is what an identified parameter looks like, and it is the contrast with
the trust coefficient.** Thirteen refits, each blind to a different season, put
running backs between 5.55 and 6.18. The trust coefficient's error bar covered
0.578 and 1.0 - two different models. This one does not come close to covering
either 0 (update on everything) or infinity (never update). The reason is that
`kappa` is estimated at the PLAYER-WEEK level, from the 1,878 player-seasons and
every week inside them, while the trust coefficient was estimated from five
season-level slopes.

The subtraction inside sigma^2_between is the load-bearing step and is what
`InSeasonLearningTest` pins: the observed spread of season rates already
contains the sampling noise of a seventeen-week season, and a decomposition that
forgets to remove it reports players as more different than they are, which
makes kappa too small and the rule too twitchy.

**The alternatives, and why each loses.**

*A k-week moving average with a threshold* is the same rule at kappa = 0, and it
is measurably WORSE THAN A COIN FLIP early: acting on one week's evidence alone
gets the rest of the season right 41.6% of the time, and it is still under 50%
at week 3 (49.5%), only crossing at week 4. That is regression to the mean
priced exactly: throwing the prior away is not neutral, it is harmful, for the
whole month in which a promotion decision would actually be made. It has two
free parameters (the window and the threshold) where
the Bayes rule has one, and neither of them is measurable from anything except
the outcome it is trying to predict. Reject.

*Shrinkage toward a position mean or a local neighbourhood* is what the
`RiskDiscountedValue` trust coefficient already does and it is a different
operation: it shrinks a PRESEASON estimate toward other players. This channel
needs to shrink an IN-SEASON estimate toward the player's own prior. James-Stein
across players is available on top and buys little, because the prior is already
player-specific.

*Changepoint detection* is the right model of the world - a back who loses his
job really does change level, discretely - and the wrong model for this data. It
needs a hazard rate and a run-length prior, at least two more parameters, fitted
on seventeen noisy weekly points per player. With sd_within at 0.65 of a
position's level, a changepoint of a plausible size is not detectable inside a
season at all. Reject on identifiability, not on principle.

*Do not tune kappa on season scores.* Section B2 sweeps it: the value that would
have won is 2x to 4x the measured one, and the accuracy curve is flat across
that whole range (58.5% at 1x against 61.9% at 4x at week 4). Ship the measured
value. A tuned constant inside that flat region buys nothing and costs the
ability to say where the number came from.

### 2. The detection lag is three to four weeks, and it is a real signal

`InSeasonLearning` section B. Every pair of men at a position inside roster
depth: the board ranks A over B, the rule at week k says B, did B outscore A
over the REMAINING weeks? The null is exactly 50%, so the test needs nothing
trusted to mean something. Bars are 95%, clustered on season.

    WEEKS SEEN     QB       RB       WR       TE
    1            48.0%    55.7%*   53.2%    51.9%
    2            53.1%    56.2%    56.7%*   51.1%
    3            58.4%    56.5%*   58.0%*   58.1%
    4            61.1%*   58.3%*   57.7%*   61.9%*
    6            62.0%*   59.9%*   60.0%*   64.0%*
    10           61.6%*   60.6%*   60.1%*   69.9%*      (* clears its own bar)

**Four weeks is when all four positions clear, and the curve is flat after
week 6.** So the answer to question 1 is: a bust is detectable at about a month,
the evidence stops accumulating usefully by mid-season, and the promoted man is
right about 60% of the time - not 90%, and not 50%.

It is not the injury channel in disguise. The `healthy` column repeats the test
on the subset where BOTH men played 80% of the remaining weeks: 57.9% RB, 57.6%
WR, 60.0% QB, 61.2% TE at week 4. Stripping availability out leaves the effect
essentially intact, so this is bust and boom, which is the channel the model
does not have.

External check, since the brief asked for one: Harstad's *Fantasy, in Theory:
Bayes and Bob* (footballguys.com/article/HarstadFiT3) runs the same comparison
on rank correlations, over a different sample, and finds the
ADP-plus-early-season blend beats both ADP alone and early-season alone in four
of five positions - the same shape as the B2 sweep, where the interior columns
beat both edges at every week. His rule of thumb that four games of evidence
weighs about the same as an offseason of study is the same order as the kappa
measured here, though it sits below it; his prior is a real projection and this
one is a smoothed ADP curve, and a better prior should raise kappa, not lower
it. Read it as agreement on the magnitude, not on the constant.

### 3. And it is worth almost nothing in season points

Section C. The SAME thirteen seasons, an eleven-round roster drafted from seat 7
on each season's own board, and the only thing that changes between rows is how
the lineup is ordered each week:

    RANKED EACH WEEK BY             mean  vs board  SE(seas)  95% bar  80% det
    preseason board rank          1795.0      +0.0      0.0       0.0      0.0
    fitted prior, no update       1791.6      -3.4      5.4      11.8     16.6
    BAYES at measured kappa       1800.7      +5.6      7.7      16.7     23.4
    BAYES at 4x kappa (tuned)     1797.5      +2.4      5.7      12.5     17.5
    [hindsight] this week         1900.4    +105.3     12.6      27.4     38.3

**The rule that is right 60% of the time is worth +5.6 +/- 7.7 points a season,
which is nothing, and the tuned version is worth less.** The hindsight row is
the ceiling: knowing in advance which of your own available men would score more
each week is worth 105 points a season, and the best rule anybody can actually
run captures about 5% of it. That gap is not a failure of this rule. It is the
week-to-week variance, sd_within 0.65 of a position's level against sd_between
0.20, and no estimator can beat it.

Section D asks the only question that matters before tomorrow: does it reprice a
bench pick? The marginal value of the rounds 8-11 men moves by +0.0, +5.4 +/-
3.9, +8.6 +/- 6.6 and +1.4 +/- 2.0. Those are the same size as the gaps the
objective is choosing between at pick 127 (56.3 RB, 52.9 WR, 51.3 TE), **and not
one of them clears its own 95% bar.** So the channel is not provably
pick-neutral - but the repricing cannot be estimated to better than the gap it
would decide, which is the same thing as not being able to act on it. Draft as
planned.

### 4. The validation protocol, which is the transferable part

Justin's instinct in the section above - a new channel earns its place by a
pick-level prediction, not a season-level score - is **right, and the reason
given for it is wrong.** The reason given is that pick-level questions have 50x
the data. That is not what saves them, and believing it will produce pick-level
tests that fail anyway.

The proof is inside this one tool. Section D is a pick-level claim, measured on
the same 13 seasons, and it does NOT resolve: +5.4 against an SE of 3.9, t=1.4.
Section B is also a pick-level claim and it resolves overwhelmingly: t=7.7 on
6,093 decisions. The difference is not the amount of data. It is that D is
measured in POINTS PER SEASON and B is measured as a BOUNDED PER-DECISION RATE.

    THE CLAIM                          effect   SE(season)  effect/SE  decisions
    B: a flip at week 4 is right       58.49%       1.105%       7.69       6093
    C: the season score moves             5.6          7.7       0.73         13

Everything here clusters on season, because the season is the unit of
independent randomness and thirteen of them is all there is. Clustering means
the estimate's error is set by the BETWEEN-SEASON variance of the estimand, and
aggregating more picks into the same seasonal quantity does not touch it - the
t-statistic of a per-pick average and of the season total it sums to are
identical. What changes the between-season variance is changing the estimand. A
season score's between-season variance is set by which men the roster happened
to hold and is unbounded. An accuracy is bounded in [0,1], so its between-season
variance is bounded too, and pooling thousands of within-season decisions
genuinely shrinks it.

**The protocol, for this channel and the next one.**

1. **State the claim as a bounded, per-decision, within-season quantity.** An
   accuracy, a hit rate, a win probability, a share. Not points a season. Both
   arms of the comparison must be exposed to the same realised football, on the
   same players, in the same weeks - that is what makes the season effect
   cancel rather than accumulate.
2. **Make the null structural, not fitted.** The flip test's null is exactly
   50% because a flip made on noise is right half the time. A test whose
   baseline has to be estimated spends its precision estimating the baseline.
3. **Fit every constant leave-one-season-out and report the spread of the
   refits as the error bar.** Not a bootstrap over players - that measures the
   wrong thing, because players inside a season are not independent.
4. **Identify each parameter twice, by different routes, and ship only if they
   agree in magnitude.** Here: a variance decomposition that never sees an
   outcome, against a validation sweep that sees nothing else. 5.87 against
   12-24 for backs is agreement to a factor of four on a flat curve; that is
   what "identified" is allowed to mean at this sample size.
5. **Print the hindsight arm.** Always. It is the ceiling on the channel and it
   is the bug this repo has shipped twice. A rule scoring near the oracle is a
   leak, not a triumph. Here the oracle is +105 and the honest rule is +5.6, and
   knowing the ceiling is what makes +5.6 interpretable rather than
   disappointing.
6. **Guard the copy against the original.** `InSeasonLearning` reimplements the
   weekly lineup fill so the ordering can be swapped, and throws unless the
   board ranker reproduces `EraGame.seasonPoints` to the point on all thirteen
   seasons. That guard caught a real bug on the first run - the flex queue was
   ordered on positional rank where the original uses overall rank - which would
   have shown up as an 8.8-point "effect" of the learning rule.
7. **Only then, and only as a report, score it at season level.** Never as the
   gate. If the oracle ceiling is below the season-level bar, no rule of that
   family can ever clear it and the season-level test is decoration.

### 5. So: build it or not?

**The honest answer to "can a bust/boom channel be validated at this sample
size" is: the CHANNEL can, and its VALUE cannot.**

That the channel exists is now proven at t=7.7, with a measured parameter and a
measured detection lag, and none of that was available this morning. What cannot
be established at thirteen seasons is that adding it to the draft objective is
worth anything, because the ceiling on the whole family - perfect foresight
about your own roster - is 105 points a season, and the honest rule delivers 5.6
+/- 7.7. The tool computes what proving that 5.6 would take, at 95% and 80%
power: **193 seasons.** Thirteen exist. That claim is not establishable, now or
ever, and no cleverness in the estimator changes it.

The recommendation is therefore: build it AFTER the draft, as a correctness fix
rather than an improvement, with the measured kappa and no tuning, and never
claim a season-points gain for it. It makes `WeeklyStarterValue` describe the
game it is modelling instead of a game where nobody ever disappoints, which is
worth having in a model Justin intends to keep. It will not change a pick, and
nothing in the next month of work will change a pick either.

Model A is byte-identical after all of this: plan [RB, WR, RB, WR, WR, WR, TE,
QB, QB]. Nothing here reaches Tuesday's tooling.
