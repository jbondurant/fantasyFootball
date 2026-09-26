import PlayerImportAndSetup.Position;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * WHAT, IF ANYTHING, KNOWS SOMETHING SLEEPER'S WEEKLY PROJECTION DOES NOT.
 *
 * Justin, week 3: weather, coaches, altitude, the party index of the road
 * city, each player's susceptibility to each, QB-receiver years together, a
 * running back's wear and tear - "can we test a bunch of things against
 * Sleeper to narrow down what to use in a big model, while avoiding
 * overfit?" This is that screen. Its protection against overfitting is the
 * protocol, fixed in this file before any result was seen and reviewed
 * beforehand by three independent critiques (leakage, statistics, football),
 * which changed most of it:
 *
 *  1. PRE-REGISTERED. The candidate list, each definition, the direction
 *     expected, the population and every threshold are the code below. The
 *     shortlist file records a fingerprint of them; confirmation refuses to
 *     run against a registry that changed after discovery.
 *
 *  2. TWO STAGES ON DIFFERENT SEASONS. Discovery runs on 2018-2022 only -
 *     that stage never loads a later season - leaving each season out in
 *     turn. What survives is written to data/feature-screen-shortlist.txt and
 *     committed. Confirmation then fits on all of 2018-2022 and scores
 *     2023-2025 once, which no stage before it has read.
 *
 *  3. THE TEST. Per position, the baseline is Sleeper recalibrated with a
 *     curve, y = a + b*p + b2*(p - pbar)^2 (Sleeper's calibration bends; a
 *     straight line would let any feature correlated with p win on the
 *     curvature). A candidate adds one term, c * x~ * p: the effect is taken as
 *     proportional to the projection. Every non-market feature is tested net
 *     of the betting line (both market terms in its baseline), so "party city"
 *     cannot be the host's defence and "altitude" cannot be Denver's. The
 *     statistic is Clark-West's, f = 2 (y - yB)(yX - yB) on held-out rows:
 *     unbiased under the null, where raw squared-error gain is biased toward
 *     rejecting every feature and has half the power.
 *
 *  4. CENTERING AND MISSING VALUES. Each feature is centered within its
 *     season, week and position on its observed values - using no outcome - so
 *     no feature can win by tracking a season's or a week's calibration drift,
 *     and a missing value becomes zero: that row gets the baseline and adds
 *     nothing to c. Every feature is tested on the same rows.
 *
 *  5. STANDARD ERRORS. Two-way clustered: by season-week (games, and anything
 *     a week shares) and by team-season, or by player for player features.
 *
 *  6. CALIBRATED BY PLACEBOS. Fifty seeded random features at each level a
 *     real one varies at (game, team-week, team-season, player) go through the
 *     identical pipeline, and each level's null is MEASURED from them: pooled
 *     leave-one-season-out Clark-West z's are not N(0,1) even with right
 *     standard errors (mean near -0.46, sd near 1.23), so a real z is
 *     standardised by its level's placebo mean and sd. Placebos are not
 *     hypotheses and sit outside the multiple-testing family.
 *
 *  7. MULTIPLE TESTING. One-sided on the upper tail in both stages: a negative
 *     Clark-West z means the feature made held-out predictions worse, which is
 *     never a discovery - the direction Sleeper errs in is carried by the sign
 *     of c, which the test leaves free. Discovery: Benjamini-Hochberg at
 *     q = 0.10 over every (feature, position) test. Confirmation: Holm at 0.05
 *     over the shortlist.
 *
 *  8. SIZE, NOT ONLY SIGNIFICANCE. Beside every test: the change in RMSE in
 *     points and the 90th percentile of the adjustment it makes. A real
 *     tenth of a point does not change a lineup.
 *
 *  9. PER-PLAYER SUSCEPTIBILITY, LAST. Only for a confirmed feature: each
 *     player's own slope, shrunk to the population's by empirical Bayes
 *     (DerSimonian-Laird) on discovery, kept only if the between-player spread
 *     is real there and the shrunk slopes beat the population slope on the
 *     confirmation seasons.
 *
 * One bound, stated rather than fixed: the baseline the residual-history
 * features read (cal, for A8, A14, P11) is fitted per season from the other
 * discovery seasons, not per fold, so a held-out season's outcomes reach a
 * training row's feature through three pooled coefficients (leverage about
 * 3/N, N in the thousands). Confirmation rows are clean: theirs is fitted on
 * 2018-2022 only.
 *
 * HOW IT WAS RUN. The first discovery run (protocol c80bc0d577362f6b) happened
 * by accident on 2026-09-25: -Pstage was not a forwarded knob, and the run
 * meant as the data-only audit took the default stage. It shortlisted nothing
 * (0 of 166; best two-sided p 0.016 against a bar near 0.0006). A code review
 * already under way then found defects - the leader features chose the leader
 * with the man himself excluded, pace and pass rate mixed units in their
 * priors, the turf field is wrong for four stadiums in 2019-2020, the momentum
 * ratio exploded on promoted backups, the per-player stage had no player
 * intercept, and the calibration assumed a N(0,1) null. Every change after
 * that run is one of the review's findings, listed in TRAPS #149, and none was
 * chosen by reading a result; the stage now has no default. The seasons held
 * back for confirmation, 2023-2025, were read by no test in either run.
 *
 * What a result here means: a factor that survives predicts part of Sleeper's
 * miss on seasons it never saw. What it does not mean: that the factor has no
 * effect on scoring - most of these are real effects that Sleeper already
 * prices, and a null says it prices them well enough. See ScreenData for the
 * population and its audit.
 *
 *     ./gradlew run -Pmain=FeatureScreen -Pstage=audit          (the data only: joins, exclusions, coverage - no outcome is related to any feature)
 *     ./gradlew run -Pmain=FeatureScreen -Pstage=discovery
 *     ./gradlew run -Pmain=FeatureScreen -Pstage=confirm      (after the shortlist is committed)
 */
public class FeatureScreen {

    static final List<String> DISCOVERY = List.of("2018", "2019", "2020", "2021", "2022");
    static final List<String> CONFIRMATION = List.of("2023", "2024", "2025");
    static final double MIN_PROJECTION = 5.0;
    static final double FDR = 0.10;
    static final double CONFIRM_ALPHA = 0.05;
    static final int PLACEBOS = 50;
    static final Path SHORTLIST = Path.of("data", "feature-screen-shortlist.txt");
    static final List<Position> POSITIONS = List.of(Position.QB, Position.RB, Position.WR, Position.TE);

    enum Level { GAME, TEAM_WEEK, TEAM_SEASON, PLAYER }

    interface Value {
        double of(ScreenData.Row r, ScreenData.Context c);
    }

    /** One pre-registered candidate. {@code alsoInBaseline}: other features netted out of its test. */
    record Feature(String key, String what, Level level, String prior, Set<Position> positions,
                   List<String> alsoInBaseline, Value value) {
        boolean market(){
            return key.startsWith("V");
        }
    }

    static final Set<Position> ALL = EnumSet.of(Position.QB, Position.RB, Position.WR, Position.TE);
    static final Set<Position> RB = EnumSet.of(Position.RB);
    static final Set<Position> QB = EnumSet.of(Position.QB);
    static final Set<Position> CATCHERS = EnumSet.of(Position.RB, Position.WR, Position.TE);
    static final Set<Position> PASSING = EnumSet.of(Position.QB, Position.WR, Position.TE);

    static double nz(Double d){
        return d == null ? Double.NaN : d;
    }

    static boolean before(String gametime, String clock){
        return gametime != null && gametime.compareTo(clock) < 0;
    }

    // ================================================================== THE REGISTRY
    static final List<Feature> REGISTRY = List.of(
        // ---- the market (tested against the plain baseline)
        new Feature("V1", "closing game total (the same for both teams)", Level.GAME, "+ all", ALL, List.of(),
                (r, c) -> nz(r.side.total())),
        new Feature("V2", "closing spread from his team's side, + = favoured", Level.TEAM_WEEK, "+ all, RB most", ALL, List.of(),
                (r, c) -> nz(r.side.spread())),
        // ---- the game's conditions (every non-market feature: net of V1 and V2)
        new Feature("G1", "strong wind: max(0, wind - 12 mph); 0 under a roof; missing outdoors when unrecorded", Level.GAME,
                "- QB WR TE, ? RB", ALL, List.of(), (r, c) -> windOver12(r)),
        new Feature("G1b", "strong wind x his depth: G1 times the air yards per target Sleeper records for him this season (per attempt for a QB), shrunk to the position - Sleeper's air yards count COMPLETIONS only, so this is depth times catch rate",
                Level.PLAYER, "- QB WR TE", PASSING, List.of(), (r, c) -> {
                    double wind = windOver12(r);
                    return Double.isNaN(wind) ? Double.NaN : wind * depth(r, c);
                }),
        new Feature("G2", "cold: max(0, 50 - temp F); 0 under a roof; missing outdoors when unrecorded", Level.GAME,
                "- QB WR TE, ? RB", ALL, List.of(), (r, c) -> r.side.roofed() ? 0
                        : r.side.temp() == null ? Double.NaN : Math.max(0, 50 - r.side.temp())),
        new Feature("G3", "under a roof: dome or closed retractable (open counts as outdoors)", Level.GAME, "+ QB WR TE, ? RB",
                ALL, List.of(), (r, c) -> r.side.roof() == null ? Double.NaN : r.side.roofed() ? 1 : 0),
        new Feature("G4", "artificial turf: the stadium's majority surface over the seven seasons centred on this one (corrects nflverse's 2019-2020 errors)", Level.GAME,
                "? all", ALL, List.of(), (r, c) -> {
                    String s = c.surface(r.side);
                    return s == null ? Double.NaN : ScreenData.TURF.contains(s) ? 1 : s.contains("grass") ? 0 : Double.NaN;
                }),
        new Feature("G5", "visiting Denver (altitude)", Level.TEAM_WEEK, "- all", ALL, List.of(),
                (r, c) -> !r.side.neutral() && !r.side.home() && "DEN".equals(r.side.homeTeam()) ? 1 : 0),
        new Feature("G6", "home (neutral site missing) - expected priced (Sleeper projects ~2% higher at home)", Level.TEAM_WEEK,
                "+ all", ALL, List.of(), (r, c) -> r.side.neutral() ? Double.NaN : r.side.home() ? 1 : 0),
        new Feature("G9", "short week: his team's rest <= 5 days (week 1 missing)", Level.TEAM_WEEK, "- all", ALL, List.of(),
                (r, c) -> r.week == 1 || r.side.rest() == null ? Double.NaN : r.side.rest() <= 5 ? 1 : 0),
        new Feature("A11a", "his team off a bye: rest >= 13 days (week 1 missing)", Level.TEAM_WEEK, "? all", ALL, List.of(),
                (r, c) -> r.week == 1 || r.side.rest() == null ? Double.NaN : r.side.rest() >= 13 ? 1 : 0),
        new Feature("A11b", "opponent off a bye: its rest >= 13 days (week 1 missing)", Level.TEAM_WEEK, "- all", ALL, List.of(),
                (r, c) -> r.week == 1 || r.side.opponentRest() == null ? Double.NaN : r.side.opponentRest() >= 13 ? 1 : 0),
        new Feature("G11", "primetime: kickoff 20:00 ET or later", Level.GAME, "? all", ALL, List.of(),
                (r, c) -> r.side.gametime() == null ? Double.NaN : before(r.side.gametime(), "20:00") ? 0 : 1),
        new Feature("A9", "body clock, early: hours his team's home clock is behind ET, for a kickoff before 14:00 ET at an Eastern-zone stadium (ARI by DST)",
                Level.TEAM_WEEK, "- all", ALL, List.of(), (r, c) -> r.side.neutral() ? Double.NaN
                        : before(r.side.gametime(), "14:00") && ScreenData.HOURS_BEHIND_ET.getOrDefault(r.side.homeTeam(), -1) == 0
                        ? ScreenData.hoursBehindEt(r.line.team(), r.side.gameday()) : 0),
        new Feature("A10", "body clock, night: hours behind ET for a kickoff at 20:00 ET or later", Level.TEAM_WEEK, "+ all", ALL,
                List.of(), (r, c) -> r.side.neutral() ? Double.NaN : before(r.side.gametime(), "20:00") ? 0
                        : ScreenData.hoursBehindEt(r.line.team(), r.side.gameday())),
        new Feature("G16", "party city (Justin's hypothesis, cities fixed before any result): road game at Las Vegas, Miami or New Orleans",
                Level.TEAM_WEEK, "- all", ALL, List.of(), (r, c) -> !r.side.neutral() && !r.side.home()
                        && ScreenData.PARTY_CITIES.contains(r.side.homeTeam()) ? 1 : 0),
        new Feature("A5", "dome team outdoors in the cold: his team's home games are mostly roofed, this one is outdoors at 40 F or below",
                Level.TEAM_WEEK, "- QB WR TE, ? RB", ALL, List.of(), (r, c) -> r.side.roofed() ? 0
                        : r.side.temp() == null ? Double.NaN
                        : c.homeRoofed(r.season, r.line.team()) && r.side.temp() <= 40 ? 1 : 0),
        new Feature("A12", "QB switch: his team's starting QB (Sleeper gs) differs from its previous game's", Level.TEAM_WEEK,
                "- WR, ? RB TE QB", ALL, List.of(), (r, c) -> qbSwitch(c, r.season, r.week, r.line.team())),
        new Feature("O1", "opponent QB switch: the opponent's starter differs from its previous game's", Level.TEAM_WEEK,
                "+ RB, ? others", ALL, List.of(), (r, c) -> qbSwitch(c, r.season, r.week, r.line.opponent())),
        new Feature("A13a", "his team out of contention: week >= 12 and losses - wins >= 4 before the game", Level.TEAM_WEEK,
                "? all", ALL, List.of(), (r, c) -> outOfContention(c, r.season, r.week, r.line.team())),
        new Feature("A13b", "opponent out of contention (same rule)", Level.TEAM_WEEK, "+ all", ALL, List.of(),
                (r, c) -> outOfContention(c, r.season, r.week, r.line.opponent())),
        new Feature("A6", "pace: his team's plays per game and the opponent's plays faced per game, averaged, this season with 4-game priors at last season's plays run and plays faced",
                Level.TEAM_WEEK, "+ all", ALL, List.of(), (r, c) -> (c.playsPerGame(r.season, r.line.team(), r.week)[0]
                        + c.playsFacedPerGame(r.season, r.line.opponent(), r.week)) / 2),
        new Feature("A7", "pass rate: his team's (attempts + sacks) / plays this season, with 4 pseudo-games of last season's plays at last season's share", Level.TEAM_WEEK,
                "+ QB WR TE, - RB", ALL, List.of(), (r, c) -> c.playsPerGame(r.season, r.line.team(), r.week)[1]),
        new Feature("A8", "opponent's residual allowed at his position: mean of (y - baseline, less its season-week mean)/p over men who faced it this season (weight 1) and last (0.25), shrunk n/(n+30)",
                Level.TEAM_WEEK, "+ all", ALL, List.of(), FeatureScreen::opponentResidual),
        // ---- coaching
        new Feature("C1", "new head coach, early: his first season with the franchise (interim coaches 0), weeks 1-4", Level.TEAM_SEASON,
                "? all", ALL, List.of(), FeatureScreen::newCoachEarly),
        new Feature("A14", "the coach's history of beating Sleeper: mean of (y - baseline, less its season-week mean)/p over his players at the position in his earlier seasons, shrunk n/(n+100)",
                Level.TEAM_SEASON, "+ all, RB most", ALL, List.of(), FeatureScreen::coachResidual),
        // ---- the player
        new Feature("P1", "age at kickoff", Level.PLAYER, "- all", ALL, List.of(), FeatureScreen::age),
        new Feature("P1b", "age x second half: age times (week >= 9 minus the season's calendar share of such weeks), net of age", Level.PLAYER,
                "- all", ALL, List.of("P1"), (r, c) -> {
                    double age = age(r, c);
                    return Double.isNaN(age) ? Double.NaN : age * ((r.week >= 9 ? 1 : 0) - ScreenData.lateShare(r.season));
                }),
        new Feature("P2", "RB wear: career touches before this season (missing when his Sleeper record starts in 2010, truncated); tested net of age",
                Level.PLAYER, "- RB", RB, List.of("P1"), FeatureScreen::careerTouches),
        new Feature("P3a", "RB season load: touches this season before the game, from week 10 (0 before)", Level.PLAYER, "- RB", RB,
                List.of(), (r, c) -> r.week >= 10 ? seasonTouchesBefore(r, c) : 0),
        new Feature("P3b", "RB acute load: last game's touches minus his mean before it (3-game prior at last season's position mean)",
                Level.PLAYER, "- RB", RB, List.of(), FeatureScreen::acuteLoad),
        new Feature("P4", "RB last season's touches (0 for a rookie) - the curse of 370, expected near-null", Level.PLAYER, "- RB",
                RB, List.of(), FeatureScreen::lastSeasonTouches),
        new Feature("P5", "rookie: his first season with a played Sleeper row", Level.PLAYER, "? all", ALL, List.of(),
                (r, c) -> c.rookie(r)),
        new Feature("P6", "rookie x second half: rookie times (week >= 9 minus the season's calendar share of such weeks), net of rookie", Level.PLAYER,
                "+ all", ALL, List.of("P5"), (r, c) -> c.rookie(r) == 0 ? 0 : (r.week >= 9 ? 1 : 0) - ScreenData.lateShare(r.season)),
        new Feature("P7", "QB-receiver games together: earlier games (since 2010) where he (a target) and this game's starting QB (10+ attempts) both played for one team, capped at 34",
                Level.PLAYER, "+ WR TE, ? RB", CATCHERS, List.of(), (r, c) -> nz(c.gamesTogether.get(r))),
        new Feature("P8", "on a different franchise from the one he played most games for last season", Level.PLAYER, "? all", ALL,
                List.of(), FeatureScreen::newTeam),
        new Feature("P11", "his points residual this season: sum of (y - baseline, less its season-week mean) over his earlier rows / (n + 8)", Level.PLAYER,
                "? all", ALL, List.of(), FeatureScreen::pointsResidual),
        new Feature("A1", "Sleeper's own revision: log(this projection / max(mean of his last three this season, 5)) (missing before three)",
                Level.PLAYER, "+ all", ALL, List.of(), FeatureScreen::projectionMomentum),
        new Feature("A2", "usage residual, last 3 games: (actual - projected) (carries + catches; attempts + carries for a QB) / max(projected, 3 a game), x n/(n+2)",
                Level.PLAYER, "+ RB WR TE, ? QB", ALL, List.of(), FeatureScreen::usageResidual),
        new Feature("A3", "touchdown residual this season: sum (actual - projected TDs) / (n + 4)", Level.PLAYER, "- or 0 all", ALL,
                List.of(), FeatureScreen::touchdownResidual),
        new Feature("U1", "opportunity-share trend: his share of the team's carries + targets over his last 3 games minus his earlier games (from his 4th)",
                Level.PLAYER, "+ RB WR TE", CATCHERS, List.of(), FeatureScreen::shareTrend),
        new Feature("Q1", "QB rushing: carries per game this season with a 4-game prior at his last season's (the position's when none)",
                Level.PLAYER, "+ QB", QB, List.of(), FeatureScreen::qbRushing),
        new Feature("F1", "first game back: he played earlier (this season or last) and missed each of his team's last two games", Level.PLAYER,
                "- all", ALL, List.of(), FeatureScreen::firstGameBack),
        new Feature("F2", "leader returns: his team's touch leader before its last two games (him included) missed both, carries a Sleeper projection this week, and he led the rest in those two",
                Level.PLAYER, "- all", ALL, List.of(), FeatureScreen::leaderReturns),
        new Feature("P12", "next man up (control, expected priced): his team's touch leader at the position (him included) went down in its last game - no gp, or under half his touches a game - and he led the rest in it",
                Level.PLAYER, "+ all", ALL, List.of(), FeatureScreen::nextManUp),
        new Feature("P13", "leader out (control, expected priced): the touch leader of the team's last 3 games (him included) carries no Sleeper projection this week - inactive, known before kickoff - and he leads the rest",
                Level.PLAYER, "+ all", ALL, List.of(), FeatureScreen::leaderOut)
    );

    // ================================================================== FEATURE VALUES

    static double windOver12(ScreenData.Row r){
        if(r.side.roofed()){
            return 0;
        }
        return r.side.wind() == null ? Double.NaN : Math.max(0, r.side.wind() - 12);
    }

    /** His air yards per target (per attempt for a QB) this season before the game, with ten of the position's last-season mean. */
    static double depth(ScreenData.Row r, ScreenData.Context c){
        double air = 0;
        double n = 0;
        for(int w : c.hisPlayedWeeksBefore(r)){
            ScreenData.Line line = c.line(r.season, w, r.id());
            air += line.airYards();
            n += r.position() == Position.QB ? line.passAtt() : line.targets();
        }
        return (air + 10 * c.meanDepth(r.season, r.position())) / (n + 10);
    }

    static double qbSwitch(ScreenData.Context c, String season, int week, String team){
        List<Integer> before = c.teamGamesBefore(season, team, week);
        if(before.isEmpty()){
            return Double.NaN;
        }
        String now = c.startingQb.get(season + "|" + week + "|" + team);
        String then = c.startingQb.get(season + "|" + before.get(before.size() - 1) + "|" + team);
        return now == null || then == null ? Double.NaN : now.equals(then) ? 0 : 1;
    }

    static double outOfContention(ScreenData.Context c, String season, int week, String team){
        if(week < 12){
            return 0;
        }
        int[] wl = c.record(season, team, week);
        return wl[1] - wl[0] >= 4 ? 1 : 0;
    }

    static double opponentResidual(ScreenData.Row r, ScreenData.Context c){
        double sum = 0;
        double weight = 0;
        String opponent = ScreenData.franchise(r.line.opponent());
        for(ScreenData.Row o : c.rowsAgainst.getOrDefault(r.season + "|" + opponent + "|" + r.position(), List.of())){
            if(o.week < r.week && !Double.isNaN(o.resid)){
                sum += o.resid / o.p;
                weight += 1;
            }
        }
        String last = String.valueOf(Integer.parseInt(r.season) - 1);
        for(ScreenData.Row o : c.rowsAgainst.getOrDefault(last + "|" + opponent + "|" + r.position(), List.of())){
            if(!Double.isNaN(o.resid)){
                sum += 0.25 * o.resid / o.p;
                weight += 0.25;
            }
        }
        return sum / (weight + 30);
    }

    static double newCoachEarly(ScreenData.Row r, ScreenData.Context c){
        String coach = r.side.coach();
        if(coach == null){
            return Double.NaN;
        }
        if(r.week > 4){
            return 0;
        }
        String franchise = ScreenData.franchise(r.line.team());
        boolean interim = !coach.equals(c.openingCoach(r.season, franchise));
        boolean first = !c.coached(coach, franchise, String.valueOf(Integer.parseInt(r.season) - 1));
        return first && !interim ? 1 : 0;
    }

    static double coachResidual(ScreenData.Row r, ScreenData.Context c){
        String coach = r.side.coach();
        if(coach == null){
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for(ScreenData.Row o : c.rowsByCoach.getOrDefault(coach + "|" + r.position(), List.of())){
            if(o.season.compareTo(r.season) < 0 && !Double.isNaN(o.resid)){
                sum += o.resid / o.p;
                n++;
            }
        }
        return sum / (n + 100);
    }

    static double age(ScreenData.Row r, ScreenData.Context c){
        LocalDate born = c.born.get(r.id());
        return born == null ? Double.NaN : ScreenData.age(born, r.side.gameday());
    }

    static double careerTouches(ScreenData.Row r, ScreenData.Context c){
        Integer first = c.firstSeason.get(r.id());
        if(first == null || first <= ScreenData.HISTORY_FROM){
            return Double.NaN;
        }
        double sum = 0;
        for(int s = first; s < Integer.parseInt(r.season); s++){
            sum += c.seasonTouches.getOrDefault(String.valueOf(s), Map.of()).getOrDefault(r.id(), 0.0);
        }
        return sum;
    }

    static double seasonTouchesBefore(ScreenData.Row r, ScreenData.Context c){
        double sum = 0;
        for(int w : c.hisPlayedWeeksBefore(r)){
            sum += c.line(r.season, w, r.id()).touches();
        }
        return sum;
    }

    static double acuteLoad(ScreenData.Row r, ScreenData.Context c){
        List<Integer> weeks = c.hisPlayedWeeksBefore(r);
        if(weeks.isEmpty()){
            return Double.NaN;
        }
        double last = c.line(r.season, weeks.get(weeks.size() - 1), r.id()).touches();
        double sum = 0;
        for(int w : weeks.subList(0, weeks.size() - 1)){
            sum += c.line(r.season, w, r.id()).touches();
        }
        double prior = c.meanTouches(String.valueOf(Integer.parseInt(r.season) - 1), r.position());
        return last - (sum + 3 * prior) / (weeks.size() - 1 + 3);
    }

    static double lastSeasonTouches(ScreenData.Row r, ScreenData.Context c){
        Integer first = c.firstSeason.get(r.id());
        if(first == null){
            return Double.NaN;
        }
        if(first >= Integer.parseInt(r.season)){
            return 0;
        }
        return c.seasonTouches.getOrDefault(String.valueOf(Integer.parseInt(r.season) - 1), Map.of()).getOrDefault(r.id(), 0.0);
    }

    static double newTeam(ScreenData.Row r, ScreenData.Context c){
        Map<String, Integer> last = c.seasonFranchiseGames.getOrDefault(String.valueOf(Integer.parseInt(r.season) - 1), Map.of())
                .get(r.id());
        if(last == null || last.isEmpty()){
            return Double.NaN;
        }
        String most = null;
        for(Map.Entry<String, Integer> e : new TreeMap<>(last).entrySet()){
            if(most == null || e.getValue() > last.get(most)){
                most = e.getKey();
            }
        }
        return most.equals(ScreenData.franchise(r.line.team())) ? 0 : 1;
    }

    static double pointsResidual(ScreenData.Row r, ScreenData.Context c){
        double sum = 0;
        int n = 0;
        for(ScreenData.Row e : c.earlier(r)){
            if(!Double.isNaN(e.resid)){
                sum += e.resid;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / (n + 8);
    }

    static double projectionMomentum(ScreenData.Row r, ScreenData.Context c){
        ScreenData.Season s = c.seasons.get(r.season);
        List<Double> previous = new ArrayList<>();
        for(int w = r.week - 1; w >= 1 && previous.size() < 3; w--){
            Double p = s.projectedPoints.getOrDefault(w, Map.of()).get(r.id());
            if(p != null){
                previous.add(p);
            }
        }
        if(previous.size() < 3){
            return Double.NaN;
        }
        double mean = previous.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        // floored at the population's own threshold: a promoted backup's three
        // weeks at a point would otherwise make a ratio of thirty
        return Math.log(r.p / Math.max(mean, MIN_PROJECTION));
    }

    static double projectedOpportunities(JsonObject line, Position position){
        return position == Position.QB ? FaabDemand.stat(line, "pass_att") + FaabDemand.stat(line, "rush_att")
                : FaabDemand.stat(line, "rush_att") + FaabDemand.stat(line, "rec");
    }

    static double projectedTds(JsonObject line, Position position){
        return position == Position.QB ? FaabDemand.stat(line, "pass_td") + FaabDemand.stat(line, "rush_td")
                : FaabDemand.stat(line, "rush_td") + FaabDemand.stat(line, "rec_td");
    }

    static double usageResidual(ScreenData.Row r, ScreenData.Context c){
        ScreenData.Season s = c.seasons.get(r.season);
        List<Integer> weeks = c.hisPlayedWeeksBefore(r);
        double actual = 0;
        double projected = 0;
        int n = 0;
        for(int i = weeks.size() - 1; i >= 0 && n < 3; i--){
            JsonObject line = s.projected.getOrDefault(weeks.get(i), Map.of()).get(r.id());
            if(line == null){
                continue;
            }
            actual += s.line(weeks.get(i), r.id()).opportunities();
            projected += projectedOpportunities(line, r.position());
            n++;
        }
        return n == 0 ? Double.NaN : (actual - projected) / Math.max(projected, 3.0 * n) * n / (n + 2.0);
    }

    static double touchdownResidual(ScreenData.Row r, ScreenData.Context c){
        ScreenData.Season s = c.seasons.get(r.season);
        double sum = 0;
        int n = 0;
        for(int w : c.hisPlayedWeeksBefore(r)){
            JsonObject line = s.projected.getOrDefault(w, Map.of()).get(r.id());
            if(line != null){
                sum += s.line(w, r.id()).tds() - projectedTds(line, r.position());
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / (n + 4);
    }

    static double shareTrend(ScreenData.Row r, ScreenData.Context c){
        List<Integer> weeks = c.hisPlayedWeeksBefore(r);
        if(weeks.size() < 4){
            return Double.NaN;
        }
        double[] recent = share(r, c, weeks.subList(weeks.size() - 3, weeks.size()));
        double[] earlier = share(r, c, weeks.subList(0, weeks.size() - 3));
        return recent[1] <= 0 || earlier[1] <= 0 ? Double.NaN : recent[0] / recent[1] - earlier[0] / earlier[1];
    }

    /** {his carries + targets, the team's} over some weeks. */
    static double[] share(ScreenData.Row r, ScreenData.Context c, List<Integer> weeks){
        double his = 0;
        double team = 0;
        for(int w : weeks){
            ScreenData.Line me = c.line(r.season, w, r.id());
            his += me.rushAtt() + me.targets();
            for(ScreenData.Line line : c.seasons.get(r.season).lines.getOrDefault(w, Map.of()).values()){
                if(line.played() && line.team().equals(me.team())){
                    team += line.rushAtt() + line.targets();
                }
            }
        }
        return new double[]{his, team};
    }

    static double qbRushing(ScreenData.Row r, ScreenData.Context c){
        double sum = 0;
        List<Integer> weeks = c.hisPlayedWeeksBefore(r);
        for(int w : weeks){
            sum += c.line(r.season, w, r.id()).rushAtt();
        }
        double prior = c.rushPerGame(String.valueOf(Integer.parseInt(r.season) - 1), r.id());
        return (sum + 4 * prior) / (weeks.size() + 4);
    }

    static double firstGameBack(ScreenData.Row r, ScreenData.Context c){
        List<Integer> team = c.teamGamesBefore(r.season, r.line.team(), r.week);
        if(team.size() < 2){
            return 0;
        }
        List<Integer> lastTwo = ScreenData.Context.last(team, 2);
        for(int w : lastTwo){
            if(c.played(r.season, w, r.id())){
                return 0;
            }
        }
        for(int w = 1; w < lastTwo.get(0); w++){
            if(c.played(r.season, w, r.id())){
                return 1;
            }
        }
        return c.seasonTouches.getOrDefault(String.valueOf(Integer.parseInt(r.season) - 1), Map.of()).containsKey(r.id()) ? 1 : 0;
    }

    static double leaderReturns(ScreenData.Row r, ScreenData.Context c){
        List<Integer> team = c.teamGamesBefore(r.season, r.line.team(), r.week);
        if(team.size() < 3){
            return 0;
        }
        List<Integer> lastTwo = ScreenData.Context.last(team, 2);
        List<Integer> earlier = team.subList(0, team.size() - 2);
        String leader = ScreenData.Context.top(c.touchesOver(r.season, earlier, r.line.team(), r.position(), null));
        if(leader == null || leader.equals(r.id()) || !projectedThisWeek(r, c, leader)){
            return 0;
        }
        for(int w : lastTwo){
            if(c.played(r.season, w, leader)){
                return 0;
            }
        }
        String filler = ScreenData.Context.top(c.touchesOver(r.season, lastTwo, r.line.team(), r.position(), leader));
        return r.id().equals(filler) ? 1 : 0;
    }

    static double nextManUp(ScreenData.Row r, ScreenData.Context c){
        List<Integer> team = c.teamGamesBefore(r.season, r.line.team(), r.week);
        if(team.size() < 2){
            return 0;
        }
        int u = team.get(team.size() - 1);
        List<Integer> before = ScreenData.Context.last(team.subList(0, team.size() - 1), 3);
        String leader = ScreenData.Context.top(c.touchesOver(r.season, before, r.line.team(), r.position(), null));
        if(leader == null || leader.equals(r.id()) || !c.played(r.season, before.get(before.size() - 1), leader)){
            return 0;
        }
        double sum = 0;
        int n = 0;
        for(int w : team.subList(0, team.size() - 1)){
            ScreenData.Line line = c.line(r.season, w, leader);
            if(line != null && line.played()){
                sum += line.touches();
                n++;
            }
        }
        ScreenData.Line atU = c.line(r.season, u, leader);
        boolean down = atU == null || !atU.played() || n > 0 && atU.touches() < 0.5 * sum / n;
        if(!down){
            return 0;
        }
        String next = ScreenData.Context.top(c.touchesOver(r.season, List.of(u), r.line.team(), r.position(), leader));
        return r.id().equals(next) ? 1 : 0;
    }

    /**
     * Whether a man carries a Sleeper projection in the row's week: the stored
     * weekly number is read after the inactives, so membership is his status
     * as known before kickoff - never the game's own gp (TRAPS: a late
     * scratch Sleeper had not seen, or an active man who took no snap).
     */
    static boolean projectedThisWeek(ScreenData.Row r, ScreenData.Context c, String id){
        return c.seasons.get(r.season).projectedPoints.getOrDefault(r.week, Map.of()).containsKey(id);
    }

    static double leaderOut(ScreenData.Row r, ScreenData.Context c){
        List<Integer> last3 = ScreenData.Context.last(c.teamGamesBefore(r.season, r.line.team(), r.week), 3);
        if(last3.isEmpty()){
            return 0;
        }
        String leader = ScreenData.Context.top(c.touchesOver(r.season, last3, r.line.team(), r.position(), null));
        if(leader == null || leader.equals(r.id()) || projectedThisWeek(r, c, leader)){
            return 0;
        }
        String next = ScreenData.Context.top(c.touchesOver(r.season, last3, r.line.team(), r.position(), leader));
        return r.id().equals(next) ? 1 : 0;
    }

    // ================================================================== THE PLACEBOS

    /** A seeded uniform on [-1, 1] from a unit's key: the same unit, the same draw, every run. */
    static double placebo(int seed, String unit){
        long h = 1125899906842597L + seed * 0x9E3779B97F4A7C15L;
        for(int i = 0; i < unit.length(); i++){
            h = 31 * h + unit.charAt(i);
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    static String unit(ScreenData.Row r, Level level){
        return switch(level){
            case GAME -> r.side.gameId();
            case TEAM_WEEK -> r.teamWeek();
            case TEAM_SEASON -> r.teamSeason();
            case PLAYER -> r.id();
        };
    }

    // ================================================================== ONE POSITION'S DATA

    /** One position's rows as arrays: projections, outcomes, centered features, cluster ids. */
    static final class Pos {
        final Position position;
        final List<ScreenData.Row> rows;
        final int n;
        final double[] p;
        final double[] y;
        final String[] season;
        final int[] seasonWeek;
        final int[] teamSeason;
        final int[] player;
        final double[][] x;          // registry order, centered, missing = 0
        final int[] observed;        // per feature, rows with a value

        /** Straight from arrays, for tests: every row its own player, team-season clusters as given. */
        Pos(Position position, double[] p, double[] y, String[] season, int[] seasonWeek, int[] teamSeason, int[] player, double[][] x){
            this.position = position;
            this.rows = List.of();
            this.n = p.length;
            this.p = p;
            this.y = y;
            this.season = season;
            this.seasonWeek = seasonWeek;
            this.teamSeason = teamSeason;
            this.player = player;
            this.x = x;
            this.observed = new int[x.length];
        }

        Pos(Position position, List<ScreenData.Row> rows){
            this.position = position;
            this.rows = rows;
            this.n = rows.size();
            p = new double[n];
            y = new double[n];
            season = new String[n];
            seasonWeek = new int[n];
            teamSeason = new int[n];
            player = new int[n];
            Map<String, Integer> sw = new HashMap<>();
            Map<String, Integer> ts = new HashMap<>();
            Map<String, Integer> pl = new HashMap<>();
            for(int i = 0; i < n; i++){
                ScreenData.Row r = rows.get(i);
                p[i] = r.p;
                y[i] = r.y;
                season[i] = r.season;
                seasonWeek[i] = sw.computeIfAbsent(r.seasonWeek(), k -> sw.size());
                teamSeason[i] = ts.computeIfAbsent(r.teamSeason(), k -> ts.size());
                player[i] = pl.computeIfAbsent(r.id(), k -> pl.size());
            }
            x = new double[REGISTRY.size()][];
            observed = new int[REGISTRY.size()];
            for(int j = 0; j < REGISTRY.size(); j++){
                double[] raw = new double[n];
                for(int i = 0; i < n; i++){
                    raw[i] = rows.get(i).x[j];
                    observed[j] += Double.isNaN(raw[i]) ? 0 : 1;
                }
                x[j] = centered(raw, seasonWeek);
            }
        }

        double[] placebo(Level level, int seed){
            double[] raw = new double[n];
            for(int i = 0; i < n; i++){
                raw[i] = FeatureScreen.placebo(seed * 7 + level.ordinal(), unit(rows.get(i), level));
            }
            return centered(raw, seasonWeek);
        }

        int[] clusterFor(Level level){
            return level == Level.PLAYER ? player : teamSeason;
        }
    }

    /** Each value minus the mean of the observed values in its group; missing (NaN) becomes 0. */
    static double[] centered(double[] raw, int[] group){
        Map<Integer, double[]> sums = new HashMap<>();
        for(int i = 0; i < raw.length; i++){
            if(!Double.isNaN(raw[i])){
                double[] s = sums.computeIfAbsent(group[i], k -> new double[2]);
                s[0] += raw[i];
                s[1]++;
            }
        }
        double[] out = new double[raw.length];
        for(int i = 0; i < raw.length; i++){
            if(!Double.isNaN(raw[i])){
                double[] s = sums.get(group[i]);
                out[i] = raw[i] - s[0] / s[1];
            }
        }
        return out;
    }

    // ================================================================== THE MODEL AND THE TEST

    /** y = a + b p + b2 (p - pbar)^2 + sum c_k x_k p. */
    record Model(double[] beta, double pbar) {
        double predict(double p, double[][] terms, int i){
            double q = p - pbar;
            double v = beta[0] + beta[1] * p + beta[2] * q * q;
            for(int k = 0; k < terms.length; k++){
                v += beta[3 + k] * terms[k][i] * p;
            }
            return v;
        }
    }

    static Model fit(double[] p, double[] y, boolean[] train, double[][] terms){
        int k = 3 + terms.length;
        double pbar = 0;
        int m = 0;
        for(int i = 0; i < p.length; i++){
            if(train[i]){
                pbar += p[i];
                m++;
            }
        }
        pbar /= Math.max(1, m);
        double[][] a = new double[k][k];
        double[] b = new double[k];
        double[] row = new double[k];
        for(int i = 0; i < p.length; i++){
            if(!train[i]){
                continue;
            }
            double q = p[i] - pbar;
            row[0] = 1;
            row[1] = p[i];
            row[2] = q * q;
            for(int t = 0; t < terms.length; t++){
                row[3 + t] = terms[t][i] * p[i];
            }
            for(int r = 0; r < k; r++){
                b[r] += row[r] * y[i];
                for(int s = 0; s < k; s++){
                    a[r][s] += row[r] * row[s];
                }
            }
        }
        for(int r = 0; r < k; r++){
            a[r][r] += 1e-9 * (a[r][r] + 1);
        }
        return new Model(FaabDemand.solve(a, b), pbar);
    }

    /** The variance of a mean under two-way clustering (Cameron-Gelbach-Miller), and its degrees of freedom. */
    static double[] twoWayVariance(double[] f, int[] a, int[] b){
        int n = f.length;
        double mean = 0;
        for(double v : f){
            mean += v;
        }
        mean /= n;
        double va = oneWay(f, a, null, mean);
        double vb = oneWay(f, b, null, mean);
        double vab = oneWay(f, a, b, mean);
        double v = va + vb - vab;
        if(v <= 0){
            v = Math.max(va, vb);
        }
        return new double[]{v, Math.min(distinct(a), distinct(b)) - 1};
    }

    static int distinct(int[] ids){
        return (int) Arrays.stream(ids).distinct().count();
    }

    static double oneWay(double[] f, int[] a, int[] b, double mean){
        Map<Long, Double> sums = new HashMap<>();
        for(int i = 0; i < f.length; i++){
            long key = b == null ? a[i] : ((long) a[i] << 32) | (b[i] & 0xffffffffL);
            sums.merge(key, f[i] - mean, Double::sum);
        }
        int g = sums.size();
        if(g < 2){
            return Double.NaN;
        }
        double s = 0;
        for(double v : sums.values()){
            s += v * v;
        }
        double n = f.length;
        return (double) g / (g - 1) * s / (n * n);
    }

    /** One test's held-out numbers. */
    record Outcome(double meanF, double se, double z, double meanD, double rmseB, double rmseX, double c, double p90,
                   int seasonsBetter, int seasons, int n) {}

    /**
     * Leave-one-season-out over {@code folds}: fit base and base+candidate on
     * the other seasons, score the held-out one. Or, with {@code confirmOn}
     * given, one fit on {@code folds} scored on {@code confirmOn}.
     */
    static Outcome test(Pos d, double[][] base, double[] candidate, int[] cluster, List<String> folds, List<String> confirmOn){
        double[][] withX = Arrays.copyOf(base, base.length + 1);
        withX[base.length] = candidate;
        List<Double> fs = new ArrayList<>();
        List<Double> ds = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        double sqB = 0;
        double sqX = 0;
        int better = 0;
        int seasons = 0;
        List<List<String>> plan = new ArrayList<>();
        if(confirmOn == null){
            for(String held : folds){
                plan.add(List.of(held));
            }
        }
        else{
            plan.add(confirmOn);
        }
        for(List<String> held : plan){
            boolean[] train = new boolean[d.n];
            boolean any = false;
            for(int i = 0; i < d.n; i++){
                train[i] = folds.contains(d.season[i]) && !held.contains(d.season[i]);
                any |= held.contains(d.season[i]);
            }
            if(!any){
                continue;
            }
            Model mb = fit(d.p, d.y, train, base);
            Model mx = fit(d.p, d.y, train, withX);
            Map<String, double[]> bySeason = new TreeMap<>();
            for(int i = 0; i < d.n; i++){
                if(!held.contains(d.season[i])){
                    continue;
                }
                double yb = mb.predict(d.p[i], base, i);
                double yx = mx.predict(d.p[i], withX, i);
                double eb = d.y[i] - yb;
                double ex = d.y[i] - yx;
                fs.add(2 * eb * (yx - yb));
                ds.add(eb * eb - ex * ex);
                idx.add(i);
                sqB += eb * eb;
                sqX += ex * ex;
                bySeason.computeIfAbsent(d.season[i], k -> new double[1])[0] += eb * eb - ex * ex;
            }
            for(double[] s : bySeason.values()){
                seasons++;
                better += s[0] > 0 ? 1 : 0;
            }
        }
        double[] f = fs.stream().mapToDouble(Double::doubleValue).toArray();
        int[] a = new int[f.length];
        int[] b = new int[f.length];
        for(int k = 0; k < f.length; k++){
            a[k] = d.seasonWeek[idx.get(k)];
            b[k] = cluster[idx.get(k)];
        }
        double meanF = Arrays.stream(f).average().orElse(0);
        double se = Math.sqrt(twoWayVariance(f, a, b)[0]);
        double meanD = ds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        // the fitted c and the size of the adjustment it makes, on every fold season together
        boolean[] all = new boolean[d.n];
        for(int i = 0; i < d.n; i++){
            all[i] = folds.contains(d.season[i]);
        }
        Model whole = fit(d.p, d.y, all, withX);
        double c = whole.beta()[3 + base.length];
        List<Double> adjust = new ArrayList<>();
        for(int i = 0; i < d.n; i++){
            if(all[i] && candidate[i] != 0){
                adjust.add(Math.abs(c * candidate[i] * d.p[i]));
            }
        }
        adjust.sort(Comparator.naturalOrder());
        double p90 = adjust.isEmpty() ? 0 : adjust.get((int) Math.floor(0.9 * (adjust.size() - 1)));
        return new Outcome(meanF, se, se > 0 ? meanF / se : 0, meanD, Math.sqrt(sqB / Math.max(1, f.length)),
                Math.sqrt(sqX / Math.max(1, f.length)), c, p90, better, seasons, f.length);
    }

    static double[][] baseTerms(Pos d, Feature feature){
        List<double[]> terms = new ArrayList<>();
        if(!feature.market()){
            terms.add(d.x[index("V1")]);
            terms.add(d.x[index("V2")]);
        }
        for(String also : feature.alsoInBaseline()){
            terms.add(d.x[index(also)]);
        }
        return terms.toArray(new double[0][]);
    }

    static int index(String key){
        for(int j = 0; j < REGISTRY.size(); j++){
            if(REGISTRY.get(j).key().equals(key)){
                return j;
            }
        }
        throw new IllegalArgumentException("no feature " + key);
    }

    static double phi(double z){
        return CensoredDisplacement.phi(z);
    }

    /** Benjamini-Hochberg: which of these p-values are discoveries at false discovery rate q. */
    static boolean[] benjaminiHochberg(double[] p, double q){
        Integer[] order = new Integer[p.length];
        for(int i = 0; i < p.length; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        int k = 0;
        for(int rank = 1; rank <= p.length; rank++){
            if(p[order[rank - 1]] <= q * rank / p.length){
                k = rank;
            }
        }
        boolean[] out = new boolean[p.length];
        for(int rank = 1; rank <= k; rank++){
            out[order[rank - 1]] = true;
        }
        return out;
    }

    /** Holm's step-down at family-wise alpha. */
    static boolean[] holm(double[] p, double alpha){
        Integer[] order = new Integer[p.length];
        for(int i = 0; i < p.length; i++){
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        boolean[] out = new boolean[p.length];
        for(int rank = 0; rank < p.length; rank++){
            if(p[order[rank]] <= alpha / (p.length - rank)){
                out[order[rank]] = true;
            }
            else{
                break;
            }
        }
        return out;
    }

    /**
     * A level's null, measured: the mean and sd of its placebo z's. Pooled
     * leave-one-season-out Clark-West z's are not N(0,1) even with right
     * standard errors - the code review derived a mean near -0.46 and an sd
     * near 1.23, and the first run's banks read -0.30 to -0.48 and 1.15 to
     * 1.32 - so a real z is standardised against its own level's bank, not
     * against the normal: z_adj = (z - mean) / sd.
     */
    record Null(double mean, double sd, int n) {
        double adjust(double z){
            return (z - mean) / sd;
        }
    }

    static Null calibrate(List<Double> zs){
        double mean = zs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = zs.stream().mapToDouble(z -> (z - mean) * (z - mean)).sum() / Math.max(1, zs.size() - 1);
        return new Null(mean, Math.sqrt(var) > 0 ? Math.sqrt(var) : 1, zs.size());
    }

    /** Placebo banks at every level, through the same test as the real features, and each level's null. */
    static Map<Level, Null> nulls(Harvest h, List<String> folds, List<String> confirmOn, StringBuilder out){
        Map<Level, List<Double>> placeboZ = new TreeMap<>();
        for(Pos d : h.byPosition().values()){
            double[][] base = {d.x[index("V1")], d.x[index("V2")]};
            for(Level level : Level.values()){
                for(int seed = 0; seed < PLACEBOS; seed++){
                    placeboZ.computeIfAbsent(level, k -> new ArrayList<>())
                            .add(test(d, base, d.placebo(level, seed), d.clusterFor(level), folds, confirmOn).z());
                }
            }
        }
        Map<Level, Null> nulls = new TreeMap<>();
        out.append(String.format("%n== CALIBRATION: %d seeded random features per level and position, through the identical test ==%n", PLACEBOS));
        out.append(String.format("%-12s %6s %8s %8s %16s%n", "level", "n", "mean z", "sd z", "adjusted > 1.645"));
        for(Map.Entry<Level, List<Double>> e : placeboZ.entrySet()){
            Null n0 = calibrate(e.getValue());
            nulls.put(e.getKey(), n0);
            long upper = e.getValue().stream().filter(z -> n0.adjust(z) > 1.645).count();
            out.append(String.format("%-12s %6d %+8.3f %8.3f %15.1f%%%n", e.getKey(), e.getValue().size(), n0.mean(), n0.sd(),
                    100.0 * upper / e.getValue().size()));
        }
        out.append("a real z is standardised by its level's placebo mean and sd; 'adjusted > 1.645' should read about 5% if the\n");
        out.append("upper tail is normal-shaped - the test is one-sided on that tail, since a negative Clark-West z means the\n");
        out.append("feature made held-out predictions worse, whichever way its coefficient points.\n");
        return nulls;
    }

    /** A fingerprint of the protocol: every definition, level, prior, position set, baseline and constant. */
    static String fingerprint(){
        StringBuilder all = new StringBuilder(DISCOVERY + "|" + CONFIRMATION + "|" + MIN_PROJECTION + "|" + FDR + "|"
                + CONFIRM_ALPHA + "|" + PLACEBOS);
        for(Feature f : REGISTRY){
            all.append('\n').append(f.key()).append('|').append(f.what()).append('|').append(f.level()).append('|')
                    .append(f.prior()).append('|').append(f.positions()).append('|').append(f.alsoInBaseline());
        }
        // and the code itself: a changed lambda, population rule or test passes no gate on its text alone
        for(String file : List.of("FeatureScreen.java", "ScreenData.java", "NflverseGames.java")){
            try {
                all.append('\n').append(Files.readString(Path.of("src", "main", "java", file), StandardCharsets.UTF_8));
            }
            catch(IOException unreadable){
                all.append('\n').append(file).append(" unreadable");
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(all.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for(int i = 0; i < 8; i++){
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        }
        catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException(impossible);
        }
    }

    // ================================================================== THE HARVEST

    record Harvest(List<ScreenData.Row> rows, ScreenData.Audit audit, ScreenData.Context context, Map<Position, Pos> byPosition) {}

    /**
     * Loads the stat rows from 2010 through the last population season, the
     * projections of the population seasons, builds the rows, calibrates the
     * baseline for the residual features (each discovery season's from the
     * other discovery seasons; a confirmation season's from all of them), and
     * fills and centers every feature.
     */
    static Harvest harvest(List<String> population) throws IOException {
        LeagueScoringSettings scoring = SleeperLeague.getSeriousLeague().league.leagueScoringSettings;
        int lastSeason = population.stream().mapToInt(Integer::parseInt).max().orElseThrow();
        Map<String, ScreenData.Season> seasons = new TreeMap<>();
        for(int s = ScreenData.HISTORY_FROM; s <= lastSeason; s++){
            String season = String.valueOf(s);
            boolean strict = population.contains(season) || population.contains(String.valueOf(s + 1));
            seasons.put(season, ScreenData.load(season, population.contains(season), strict, scoring));
        }
        Map<String, NflverseGames.Side> sides = NflverseGames.sides();
        ScreenData.Audit audit = new ScreenData.Audit();
        List<ScreenData.Row> rows = ScreenData.population(seasons, population, sides, MIN_PROJECTION, audit);
        // the spread's sign, checked on the rows' own games before anything leans on it
        List<double[]> spreadMargin = new ArrayList<>();
        for(ScreenData.Row r : rows){
            if(r.side.spread() != null && r.side.margin() != null){
                spreadMargin.add(new double[]{r.side.spread(), r.side.margin()});
            }
        }
        if(WeeklyFeedAudit.fit(spreadMargin).r() <= 0){
            throw new IllegalStateException("the schedule's spread does not point at the winner - its sign is not what NflverseGames assumes");
        }
        // the calibrated baseline the residual features read
        for(Position position : POSITIONS){
            List<ScreenData.Row> here = rows.stream().filter(r -> r.position() == position).toList();
            double[] p = here.stream().mapToDouble(r -> r.p).toArray();
            double[] y = here.stream().mapToDouble(r -> r.y).toArray();
            for(String season : population){
                boolean[] train = new boolean[here.size()];
                for(int i = 0; i < here.size(); i++){
                    String s = here.get(i).season;
                    train[i] = DISCOVERY.contains(s) && (!DISCOVERY.contains(season) || !s.equals(season));
                }
                Model cal = fit(p, y, train, new double[0][]);
                for(int i = 0; i < here.size(); i++){
                    if(here.get(i).season.equals(season)){
                        here.get(i).cal = cal.predict(p[i], new double[0][], i);
                    }
                }
            }
        }
        // the residual a history feature reads: y - cal, less the mean of the same over its season, week and
        // position, so a season's calibration drift to date cannot ride along (computable before the week after)
        Map<String, double[]> weekMean = new HashMap<>();
        for(ScreenData.Row r : rows){
            double[] m = weekMean.computeIfAbsent(r.seasonWeek() + "|" + r.position(), k -> new double[2]);
            m[0] += r.y - r.cal;
            m[1]++;
        }
        for(ScreenData.Row r : rows){
            double[] m = weekMean.get(r.seasonWeek() + "|" + r.position());
            r.resid = r.y - r.cal - m[0] / m[1];
        }
        ScreenData.Context context = new ScreenData.Context(seasons, sides, rows, PlayerRawData.birthDates());
        for(ScreenData.Row r : rows){
            r.x = new double[REGISTRY.size()];
            for(int j = 0; j < REGISTRY.size(); j++){
                Feature f = REGISTRY.get(j);
                r.x[j] = f.positions().contains(r.position()) ? f.value().of(r, context) : Double.NaN;
            }
        }
        Map<Position, Pos> byPosition = new LinkedHashMap<>();
        for(Position position : POSITIONS){
            byPosition.put(position, new Pos(position, rows.stream().filter(r -> r.position() == position).toList()));
        }
        return new Harvest(rows, audit, context, byPosition);
    }

    // ================================================================== THE STAGES

    public static void main(String[] args) throws IOException {
        String stage = System.getProperty("stage", "");
        String out = switch(stage){
            case "audit" -> audit();
            case "discovery" -> discovery();
            case "confirm" -> confirm();
            default -> throw new IllegalArgumentException("say which stage: -Pstage=audit, -Pstage=discovery or -Pstage=confirm"
                    + " (there is no default - on 2026-09-25 a missing knob ran discovery when the audit was meant)");
        };
        System.out.print(out);
        Path report = Path.of("data", "feature-screen-" + stage + "-" + LocalDate.now() + ".txt");
        Files.writeString(report, out, StandardCharsets.UTF_8);
        System.out.println("\nwritten to " + report);
    }

    static void header(StringBuilder out, String stage, Harvest h){
        out.append(String.format("FEATURE SCREEN - %s  (%s; protocol %s)%n", stage.toUpperCase(), LocalDate.now(), fingerprint()));
        out.append("Population: every regular-season player-week with Sleeper gp >= 1 and a Sleeper weekly projection >= ")
                .append(MIN_PROJECTION).append(" league points,\nweeks 1-17 (1-16 in 2018-2020), QB/RB/WR/TE, joined to its game in nflverse's schedule on team, opponent and date.\n");
        out.append(String.format("%-7s %9s %9s %9s %9s %13s %12s%n", "season", "played", "proj>=5", "joined", "unjoined", "proj>=5, DNP",
                "made QB"));
        for(Map.Entry<String, int[]> e : h.audit().bySeason.entrySet()){
            int[] c = e.getValue();
            out.append(String.format("%-7s %9d %9d %9d %9d %13d %12d%n", e.getKey(), c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        out.append("history seasons with unreadable weeks (non-population, left empty): ");
        List<String> unread = new ArrayList<>();
        h.context().seasons.forEach((s, season) -> {
            if(season.unreadWeeks > 0){
                unread.add(s + " " + season.unreadWeeks);
            }
        });
        out.append(unread.isEmpty() ? "none" : String.join(", ", unread)).append('\n');
        for(String u : h.audit().unjoined){
            out.append("   unjoined: ").append(u).append('\n');
        }
        out.append("starting QB per team-game (Sleeper gs): ").append(h.context().startersSeen).append('\n');
        out.append("Sleeper's stored weekly projection is read AFTER the inactives (a promoted backup's roughly doubles, an inactive\n");
        out.append("man carries none): anything fitted here is fitted on that late number and must be fed one.\n");
        out.append(String.format("%nrows by position: %s%n", h.byPosition().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue().n).toList()));
    }

    /**
     * The discovery seasons' data without a single test: what joined, what was
     * dropped, how often each feature has a value and how it is spread. Run
     * before discovery so a data defect can be fixed without having seen a
     * result that the fix could be tuned to.
     */
    static String audit() throws IOException {
        Harvest h = harvest(DISCOVERY);
        StringBuilder out = new StringBuilder();
        header(out, "audit (no outcome is related to any feature here)", h);
        coverage(out, h);
        out.append("\nfeature distributions over rows with a value (min / p10 / median / p90 / max, share nonzero):\n");
        for(Feature f : REGISTRY){
            List<Double> v = new ArrayList<>();
            for(ScreenData.Row r : h.rows()){
                double x = r.x[index(f.key())];
                if(!Double.isNaN(x)){
                    v.add(x);
                }
            }
            v.sort(Comparator.naturalOrder());
            if(v.isEmpty()){
                out.append(String.format("  %-5s no values%n", f.key()));
                continue;
            }
            long nonzero = v.stream().filter(x -> x != 0).count();
            out.append(String.format("  %-5s %9.3f %9.3f %9.3f %9.3f %9.3f   %5.1f%% nonzero of %d%n", f.key(), v.get(0),
                    v.get(v.size() / 10), v.get(v.size() / 2), v.get(9 * v.size() / 10), v.get(v.size() - 1),
                    100.0 * nonzero / v.size(), v.size()));
        }
        return out.toString();
    }

    static void coverage(StringBuilder out, Harvest h){
        out.append("\ncoverage (rows with a value / rows at its positions) by feature and season:\n");
        List<String> seasons = h.rows().stream().map(r -> r.season).distinct().sorted().toList();
        for(Feature f : REGISTRY){
            out.append(String.format("  %-5s", f.key()));
            for(String season : seasons){
                long n = h.rows().stream().filter(r -> r.season.equals(season) && !Double.isNaN(r.x[index(f.key())])).count();
                long of = h.rows().stream().filter(r -> r.season.equals(season) && f.positions().contains(r.position())).count();
                out.append(String.format("  %s %5d/%-5d", season, n, of));
            }
            out.append('\n');
        }
    }

    static String discovery() throws IOException {
        Harvest h = harvest(DISCOVERY);
        StringBuilder out = new StringBuilder();
        header(out, "discovery", h);

        Map<Level, Null> nulls = nulls(h, DISCOVERY, null, out);

        // ---- the real tests
        record Test(Feature feature, Position position, Outcome outcome, double zAdj, double p, int observed) {}
        List<Test> tests = new ArrayList<>();
        for(Feature f : REGISTRY){
            for(Position position : POSITIONS){
                if(!f.positions().contains(position)){
                    continue;
                }
                Pos d = h.byPosition().get(position);
                int j = index(f.key());
                if(d.observed[j] < 50){
                    continue;
                }
                Outcome o = test(d, baseTerms(d, f), d.x[j], d.clusterFor(f.level()), DISCOVERY, null);
                double z = nulls.get(f.level()).adjust(o.z());
                tests.add(new Test(f, position, o, z, 1 - phi(z), d.observed[j]));
            }
        }
        double[] ps = tests.stream().mapToDouble(Test::p).toArray();
        boolean[] found = benjaminiHochberg(ps, FDR);
        List<Integer> order = new ArrayList<>();
        for(int i = 0; i < tests.size(); i++){
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> ps[i]));
        out.append(String.format("%n== DISCOVERY 2018-2022, leave one season out: %d tests, Benjamini-Hochberg at q = %.2f ==%n", tests.size(), FDR));
        out.append("z = Clark-West gain over its baseline / two-way clustered se, standardised by its level's placebo null; p one-sided.\n");
        out.append("gain = held-out squared-error gain in points^2 a player-week; RMSE is baseline -> with the feature;\n");
        out.append("adj90 = 90th percentile of the adjustment c*x*p it makes, in points; seasons = held-out seasons it helped.\n");
        out.append(String.format("%-5s %-3s %7s %8s %9s %9s %15s %7s %7s %8s %-9s %s%n", "feat", "pos", "rows", "z", "p", "gain",
                "RMSE", "c", "adj90", "seasons", "prior", "discovered"));
        for(int i : order){
            Test t = tests.get(i);
            Outcome o = t.outcome();
            out.append(String.format("%-5s %-3s %7d %+8.2f %9.2g %+9.4f %6.3f->%-7.3f %+7.4f %7.2f %5d/%-2d %-9s %s%n",
                    t.feature().key(), t.position(), t.observed(), t.zAdj(), t.p(), o.meanD(), o.rmseB(), o.rmseX(), o.c(), o.p90(),
                    o.seasonsBetter(), o.seasons(), t.feature().prior(), found[i] ? "<- SHORTLIST" : ""));
        }
        out.append("\nthe registry:\n");
        for(Feature f : REGISTRY){
            out.append(String.format("  %-5s %-11s %s%s%n", f.key(), f.level(), f.what(),
                    f.alsoInBaseline().isEmpty() ? "" : " [net of " + String.join(", ", f.alsoInBaseline()) + "]"));
        }
        out.append("every feature except V1 and V2 is tested net of both.\n");
        coverage(out, h);

        // ---- the shortlist, for committing before confirmation is run
        StringBuilder list = new StringBuilder();
        list.append("# FeatureScreen shortlist - discovery ").append(LocalDate.now()).append('\n');
        list.append("# protocol ").append(fingerprint()).append('\n');
        list.append("# commit this file BEFORE running -Pstage=confirm; confirmation refuses a changed protocol\n");
        list.append("feature,position,sign,z,p\n");
        int shortlisted = 0;
        for(int i : order){
            if(found[i]){
                Test t = tests.get(i);
                list.append(String.format("%s,%s,%s,%.3f,%.3g%n", t.feature().key(), t.position(), t.outcome().c() >= 0 ? "+" : "-",
                        t.zAdj(), t.p()));
                shortlisted++;
            }
        }
        Files.writeString(SHORTLIST, list.toString(), StandardCharsets.UTF_8);
        out.append(String.format("%nshortlist: %d of %d tests, written to %s - commit it, then run -Pstage=confirm.%n",
                shortlisted, tests.size(), SHORTLIST));
        return out.toString();
    }

    record Shortlisted(String key, Position position) {}

    static List<Shortlisted> readShortlist() throws IOException {
        if(!Files.exists(SHORTLIST)){
            throw new IllegalStateException("no " + SHORTLIST + ": run -Pstage=discovery and commit its shortlist first");
        }
        List<String> lines = Files.readAllLines(SHORTLIST, StandardCharsets.UTF_8);
        String protocol = lines.stream().filter(l -> l.startsWith("# protocol ")).findFirst()
                .map(l -> l.substring("# protocol ".length()).trim()).orElse("");
        if(!protocol.equals(fingerprint())){
            throw new IllegalStateException("the shortlist was made under protocol " + protocol + " and the registry is now "
                    + fingerprint() + ": a changed registry needs a new discovery run, not a confirmation");
        }
        List<Shortlisted> out = new ArrayList<>();
        for(String line : lines){
            if(line.startsWith("#") || line.startsWith("feature,") || line.isBlank()){
                continue;
            }
            String[] cells = line.split(",");
            out.add(new Shortlisted(cells[0], Position.valueOf(cells[1])));
        }
        return out;
    }

    static String confirm() throws IOException {
        List<Shortlisted> shortlist = readShortlist();
        List<String> population = new ArrayList<>(DISCOVERY);
        population.addAll(CONFIRMATION);
        Harvest h = harvest(population);
        StringBuilder out = new StringBuilder();
        header(out, "confirmation", h);
        if(shortlist.isEmpty()){
            out.append("\nThe shortlist is empty: discovery found nothing to confirm, and 2023-2025 stay unread by any test.\n");
            return out.toString();
        }
        Map<Level, Null> nulls = nulls(h, DISCOVERY, CONFIRMATION, out);

        List<Outcome> outcomes = new ArrayList<>();
        double[] ps = new double[shortlist.size()];
        double[] zs = new double[shortlist.size()];
        for(int i = 0; i < shortlist.size(); i++){
            Shortlisted s = shortlist.get(i);
            Feature f = REGISTRY.get(index(s.key()));
            Pos d = h.byPosition().get(s.position());
            Outcome o = test(d, baseTerms(d, f), d.x[index(f.key())], d.clusterFor(f.level()), DISCOVERY, CONFIRMATION);
            outcomes.add(o);
            zs[i] = nulls.get(f.level()).adjust(o.z());
            ps[i] = 1 - phi(zs[i]);      // one-sided: the discovery fit's c must improve the unseen seasons
        }
        boolean[] confirmed = holm(ps, CONFIRM_ALPHA);
        out.append(String.format("%n== CONFIRMATION: fit on 2018-2022, scored once on 2023-2025; one-sided, Holm at %.2f over %d ==%n",
                CONFIRM_ALPHA, shortlist.size()));
        out.append(String.format("%-5s %-3s %7s %8s %9s %9s %15s %7s %7s  %s%n", "feat", "pos", "rows", "z", "p", "gain", "RMSE", "c",
                "adj90", "verdict"));
        List<Shortlisted> kept = new ArrayList<>();
        for(int i = 0; i < shortlist.size(); i++){
            Outcome o = outcomes.get(i);
            Shortlisted s = shortlist.get(i);
            out.append(String.format("%-5s %-3s %7d %+8.2f %9.2g %+9.4f %6.3f->%-7.3f %+7.4f %7.2f  %s%n", s.key(), s.position(), o.n(),
                    zs[i], ps[i], o.meanD(), o.rmseB(), o.rmseX(), o.c(), o.p90(), confirmed[i] ? "CONFIRMED" : "not confirmed"));
            if(confirmed[i]){
                kept.add(s);
            }
        }
        bigModel(out, h, kept);
        perPlayer(out, h, kept);
        return out.toString();
    }

    /**
     * The pre-registered big model: per position, every confirmed feature, with
     * whatever each was tested net of (the market terms for a non-market
     * feature, and its own alsoInBaseline), fit on 2018-2022 and scored on
     * 2023-2025 against the plain curved baseline and against that baseline
     * plus the market terms - so the line's own gain is not credited to the
     * features.
     */
    static void bigModel(StringBuilder out, Harvest h, List<Shortlisted> kept){
        out.append("\n== THE BIG MODEL: every confirmed feature with what it was tested net of, fit on 2018-2022, scored on 2023-2025 ==\n");
        out.append(String.format("%-4s %7s %12s %12s %12s %12s %9s %9s  %s%n", "pos", "rows", "RMSE Sleeper", "RMSE base",
                "RMSE base+V", "RMSE model", "z vs base", "z vs +V", "terms"));
        for(Pos d : h.byPosition().values()){
            List<String> keys = new ArrayList<>();
            for(Shortlisted s : kept){
                if(s.position() == d.position){
                    Feature f = REGISTRY.get(index(s.key()));
                    List<String> needed = new ArrayList<>(f.alsoInBaseline());
                    if(!f.market()){
                        needed.addAll(0, List.of("V1", "V2"));
                    }
                    needed.add(s.key());
                    for(String k : needed){
                        if(!keys.contains(k)){
                            keys.add(k);
                        }
                    }
                }
            }
            double raw = 0;
            int n = 0;
            for(int i = 0; i < d.n; i++){
                if(CONFIRMATION.contains(d.season[i])){
                    raw += (d.y[i] - d.p[i]) * (d.y[i] - d.p[i]);
                    n++;
                }
            }
            double[][] market = {d.x[index("V1")], d.x[index("V2")]};
            Outcome withMarket = testJoint(d, new double[0][], market);
            if(keys.isEmpty()){
                out.append(String.format("%-4s %7d %12.3f %12.3f %12.3f %12s %9s %9s  nothing confirmed%n", d.position, n,
                        Math.sqrt(raw / n), withMarket.rmseB(), withMarket.rmseX(), "-", "-", "-"));
                continue;
            }
            double[][] terms = keys.stream().map(k -> d.x[index(k)]).toArray(double[][]::new);
            Outcome vsBase = testJoint(d, new double[0][], terms);
            Outcome vsMarket = testJoint(d, market, terms);
            out.append(String.format("%-4s %7d %12.3f %12.3f %12.3f %12.3f %+9.2f %+9.2f  %s%n", d.position, n, Math.sqrt(raw / n),
                    vsBase.rmseB(), withMarket.rmseX(), vsBase.rmseX(), vsBase.z(), vsMarket.z(), String.join(" ", keys)));
        }
        out.append("z is Clark-West on the unseen seasons, two-way clustered by season-week and player (not placebo-standardised).\n");
    }

    /** {@code terms} against the curved baseline plus {@code base}, fit on discovery and scored on confirmation. */
    static Outcome testJoint(Pos d, double[][] base, double[][] terms){
        boolean[] train = new boolean[d.n];
        for(int i = 0; i < d.n; i++){
            train[i] = DISCOVERY.contains(d.season[i]);
        }
        double[][] all = new double[base.length + terms.length][];
        System.arraycopy(base, 0, all, 0, base.length);
        for(int k = 0; k < terms.length; k++){
            all[base.length + k] = terms[k];
        }
        Model mb = fit(d.p, d.y, train, base);
        Model mx = fit(d.p, d.y, train, all);
        List<Double> f = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        double sqB = 0;
        double sqX = 0;
        for(int i = 0; i < d.n; i++){
            if(!CONFIRMATION.contains(d.season[i])){
                continue;
            }
            double yb = mb.predict(d.p[i], base, i);
            double yx = mx.predict(d.p[i], all, i);
            f.add(2 * (d.y[i] - yb) * (yx - yb));
            idx.add(i);
            sqB += (d.y[i] - yb) * (d.y[i] - yb);
            sqX += (d.y[i] - yx) * (d.y[i] - yx);
        }
        double[] fa = f.stream().mapToDouble(Double::doubleValue).toArray();
        int[] a = idx.stream().mapToInt(i -> d.seasonWeek[i]).toArray();
        int[] b = idx.stream().mapToInt(i -> d.player[i]).toArray();
        double mean = Arrays.stream(fa).average().orElse(0);
        double se = Math.sqrt(twoWayVariance(fa, a, b)[0]);
        return new Outcome(mean, se, se > 0 ? mean / se : 0, 0, Math.sqrt(sqB / fa.length), Math.sqrt(sqX / fa.length), 0, 0, 0, 1, fa.length);
    }

    /**
     * One player's slope of residual on z = x~ * p WITHIN him: with his own
     * intercept, so Sleeper's persistent bias on the man is not read as his
     * susceptibility, and a sandwich variance from his own residuals, not the
     * pooled one. Returns {slope, variance} or null with fewer than 20 rows or
     * no spread in z.
     */
    static double[] withinPlayerSlope(List<double[]> ze){
        if(ze.size() < 20){
            return null;
        }
        double zbar = 0;
        double ebar = 0;
        for(double[] r : ze){
            zbar += r[0];
            ebar += r[1];
        }
        zbar /= ze.size();
        ebar /= ze.size();
        double szz = 0;
        double sze = 0;
        for(double[] r : ze){
            szz += (r[0] - zbar) * (r[0] - zbar);
            sze += (r[0] - zbar) * (r[1] - ebar);
        }
        if(szz <= 1e-12){
            return null;
        }
        double slope = sze / szz;
        double meat = 0;
        for(double[] r : ze){
            double u = (r[1] - ebar) - slope * (r[0] - zbar);
            meat += (r[0] - zbar) * (r[0] - zbar) * u * u;
        }
        return new double[]{slope, meat / (szz * szz), zbar};
    }

    /**
     * Justin's "each player's susceptibility": for a confirmed feature, every
     * player's own within-player slope on discovery (20+ rows where the
     * feature is not zero), shrunk to the population's by DerSimonian-Laird;
     * carried to confirmation only if the spread between players is real (Q
     * test p < 0.05) and the median reliability is at least 0.2, and kept only
     * if it then beats the population slope on 2023-2025.
     */
    static void perPlayer(StringBuilder out, Harvest h, List<Shortlisted> kept){
        out.append("\n== PER-PLAYER SUSCEPTIBILITY (confirmed features only) ==\n");
        if(kept.isEmpty()){
            out.append("nothing confirmed, so there is no population effect for a player to deviate from: not run.\n");
            return;
        }
        for(Shortlisted s : kept){
            Pos d = h.byPosition().get(s.position());
            Feature f = REGISTRY.get(index(s.key()));
            double[][] base = baseTerms(d, f);
            double[][] withX = Arrays.copyOf(base, base.length + 1);
            withX[base.length] = d.x[index(s.key())];
            boolean[] train = new boolean[d.n];
            for(int i = 0; i < d.n; i++){
                train[i] = DISCOVERY.contains(d.season[i]);
            }
            Model m = fit(d.p, d.y, train, withX);
            Map<Integer, List<double[]>> byPlayer = new HashMap<>();
            for(int i = 0; i < d.n; i++){
                double z = withX[base.length][i] * d.p[i];
                if(train[i] && z != 0){
                    byPlayer.computeIfAbsent(d.player[i], k -> new ArrayList<>()).add(new double[]{z, d.y[i] - m.predict(d.p[i], withX, i)});
                }
            }
            List<double[]> slopes = new ArrayList<>();                // {player, c_i, se_i^2, zbar_i}
            for(Map.Entry<Integer, List<double[]>> e : byPlayer.entrySet()){
                double[] sl = withinPlayerSlope(e.getValue());
                if(sl != null && sl[1] > 0){
                    slopes.add(new double[]{e.getKey(), sl[0], sl[1], sl[2]});
                }
            }
            double[] dl = derSimonianLaird(slopes);
            double tau2 = dl[0];
            int k = slopes.size();
            double pQ = k > 1 ? 1 - chiSquareCdf(dl[1], k - 1) : 1;
            List<Double> reliability = new ArrayList<>();
            for(double[] sl : slopes){
                reliability.add(tau2 / (tau2 + sl[2]));
            }
            reliability.sort(Comparator.naturalOrder());
            double median = reliability.isEmpty() ? 0 : reliability.get(reliability.size() / 2);
            out.append(String.format("%s %s: %d players with 20+ rows; tau^2 %.3g, Q %.1f on %d df (p %.3g), median reliability %.2f%n",
                    s.key(), s.position(), k, tau2, dl[1], Math.max(0, k - 1), pQ, median));
            if(pQ >= 0.05 || median < 0.2){
                out.append("   the players do not differ enough, or are measured too loosely, to estimate their own slopes: the population slope stands.\n");
                continue;
            }
            Map<Integer, double[]> deviation = new HashMap<>();          // {shrunk slope deviation, his zbar}
            for(double[] sl : slopes){
                deviation.put((int) sl[0], new double[]{tau2 / (tau2 + sl[2]) * (sl[1] - dl[2]), sl[3]});
            }
            List<Double> fs = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            for(int i = 0; i < d.n; i++){
                if(!CONFIRMATION.contains(d.season[i])){
                    continue;
                }
                double pop = m.predict(d.p[i], withX, i);
                double[] dev = deviation.get(d.player[i]);
                double z = withX[base.length][i] * d.p[i];
                double own = dev == null ? pop : pop + dev[0] * (z - dev[1]);
                fs.add(2 * (d.y[i] - pop) * (own - pop));
                idx.add(i);
            }
            double[] fa = fs.stream().mapToDouble(Double::doubleValue).toArray();
            double se = Math.sqrt(twoWayVariance(fa, idx.stream().mapToInt(i -> d.seasonWeek[i]).toArray(),
                    idx.stream().mapToInt(i -> d.player[i]).toArray())[0]);
            double mean = Arrays.stream(fa).average().orElse(0);
            double z = se > 0 ? mean / se : 0;
            out.append(String.format("   own slopes against the population slope on 2023-2025: z %+.2f (one-sided p %.3g) %s%n", z, 1 - phi(z),
                    1 - phi(z) < 0.05 ? "<- players differ, and it carries" : "<- does not carry: the population slope stands"));
        }
    }

    /** {tau^2, Q, weighted mean} over rows {id, estimate, variance}. */
    static double[] derSimonianLaird(List<double[]> rows){
        double s1 = 0;
        double s2 = 0;
        double sum = 0;
        for(double[] r : rows){
            double w = 1 / r[2];
            s1 += w;
            s2 += w * w;
            sum += w * r[1];
        }
        if(s1 == 0){
            return new double[]{0, 0, 0};
        }
        double mean = sum / s1;
        double q = 0;
        for(double[] r : rows){
            q += (r[1] - mean) * (r[1] - mean) / r[2];
        }
        double tau2 = Math.max(0, (q - (rows.size() - 1)) / (s1 - s2 / s1));
        return new double[]{tau2, q, mean};
    }

    /** Wilson-Hilferty: the chi-square CDF through a normal, accurate enough for a gate. */
    static double chiSquareCdf(double x, int k){
        if(k <= 0 || x <= 0){
            return 0;
        }
        double z = (Math.cbrt(x / k) - (1 - 2.0 / (9 * k))) / Math.sqrt(2.0 / (9 * k));
        return phi(z);
    }
}
