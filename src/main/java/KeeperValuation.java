import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each keeper is actually worth, against the nine skill starting slots.
 *
 * The rule, and the only one that matters: keeping a skill player fills one of
 * the nine slots, which frees the pick that would otherwise have filled it.
 * With nine skill slots those picks are rounds one through nine, so a keeper -
 * whatever round he nominally costs - really costs the round-nine pick. He is
 * worth a keeper slot only if he beats what that pick returns.
 *
 * A keeper costing round 13 does not cost you a round-13 player. It costs you a
 * round-9 starter, and hands a bench slot a round-9 calibre player instead of a
 * round-13 one, which is worth nothing here because the bench is not being
 * optimised.
 *
 * Defenses are not candidates. They cannot fill any of the nine slots.
 */
public class KeeperValuation {

    public static class Valued {
        public final Keeper keeper;
        public final double projectedPoints;
        public final double valueOverReplacement;
        public final double costOfTheFreedPick;

        Valued(Keeper keeper, double projectedPoints, double vorp, double cost){
            this.keeper = keeper;
            this.projectedPoints = projectedPoints;
            this.valueOverReplacement = vorp;
            this.costOfTheFreedPick = cost;
        }

        /** Points gained by keeping him rather than drafting normally. */
        public double net(){
            return valueOverReplacement - costOfTheFreedPick;
        }

        public boolean worthAKeeperSlot(){
            return net() > 0;
        }
    }

    public static class Report {
        public final List<Valued> candidates = new ArrayList<>();
        /** Candidates with no published projection, so not gradeable. */
        public final List<String> unprojected = new ArrayList<>();
        /** Replacement drawn with reach risk, per position. */
        public final Map<Position, Double> riskAwareReplacement = new EnumMap<>(Position.class);
        public ReplacementLevel replacement;
        public double freedPickValue;
        public String freedPickDescription = "";
        public int freedPickNumber;
    }

    public static Report evaluate(AAAConfiguration configuration){
        String myID = configuration.getMyID();
        Map<String, Double> points = SleeperProjections.parseTodaysWebPage();

        Report report = new Report();
        report.replacement = ReplacementLevel.forLeague(configuration, points);
        report.freedPickNumber = configuration.pickNumberFor(StartingLineup.lastStarterRound());

        // Replacement with reach risk in it, rather than a fixed rank. The
        // bias is fitted from the league's own drafts, not hand-set.
        int lastCompleted = Integer.parseInt(configuration.getSeason()) - 1;
        Map<Position, Double> bias =
                ManagerProfiles.fitThroughSeason(configuration, lastCompleted).leagueBiasMap();
        AvailabilityModel availability = AvailabilityModel.build(points, bias);
        int myLastStarterPick = configuration.pickNumberFor(StartingLineup.lastStarterRound());
        for(Position position : List.of(Position.QB, Position.RB, Position.WR, Position.TE)){
            report.riskAwareReplacement.put(position,
                    availability.expectedBestAvailable(position, myLastStarterPick, 400, 20260824L));
        }

        Valued freed = bestAvailableAt(configuration, report, points);
        report.freedPickValue = freed == null ? 0.0 : freed.valueOverReplacement;
        report.freedPickDescription = freed == null ? "nothing"
                : freed.keeper.player.firstName + " " + freed.keeper.player.lastName
                        + " (" + freed.keeper.player.position + ")";

        for(Keeper candidate : KeeperChooser.eligibleCandidates(configuration, myID)){
            Position position = candidate.player.position;
            if(!StartingLineup.isSkillPosition(position)){
                // A defense fills none of the nine slots being optimised.
                continue;
            }
            Double projected = points.get(candidate.player.sleeperIDString);
            if(projected == null || projected <= 0.0){
                // Nobody has projected him - a rookie Sleeper has not priced,
                // or someone out for the season. Not a keeper decision.
                report.unprojected.add(candidate.player.firstName + " " + candidate.player.lastName);
                continue;
            }
            // Against what you would realistically still get at that position,
            // not against a fixed replacement rank.
            double replacement = report.riskAwareReplacement.getOrDefault(position,
                    report.replacement.of(position));
            double vorp = projected - replacement;
            report.candidates.add(new Valued(candidate, projected, vorp, report.freedPickValue));
        }
        report.candidates.sort(Comparator.comparingDouble((Valued v) -> v.net()).reversed());
        return report;
    }

    /**
     * The best value realistically still on the board at the round-nine pick.
     * That is what a keeper costs, since keeping frees that pick from starter
     * duty.
     */
    private static Valued bestAvailableAt(AAAConfiguration configuration,
                                          Report report,
                                          Map<String, Double> points){
        int pick = report.freedPickNumber;
        Valued best = null;
        for(Map.Entry<String, Double> entry : points.entrySet()){
            Player player = Player.getPlayerFromSIDV2(entry.getKey());
            if(player == null || !StartingLineup.isSkillPosition(player.position)){
                continue;
            }
            double adp = SleeperProjections.adpOf(entry.getKey());
            // Gone already, or too far past this pick to count on.
            if(adp < pick - 6 || adp > pick + 40){
                continue;
            }
            double vorp = report.replacement.valueOver(player, entry.getValue());
            if(best == null || vorp > best.valueOverReplacement){
                best = new Valued(new Keeper(null, player, StartingLineup.lastStarterRound()),
                        entry.getValue(), vorp, 0.0);
            }
        }
        return best;
    }

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        Report report = evaluate(configuration);

        System.out.println("Optimising the nine skill starting slots: QB, RB, RB, WR, WR, WR, TE, FLEX, FLEX");
        System.out.println("(the defense slot is filled late and is not part of this)\n");

        System.out.println("replacement level, at your round-" + StartingLineup.lastStarterRound()
                + " pick, drawn 400 times so a reach costs you what it really costs:");
        for(Map.Entry<Position, Double> entry : report.riskAwareReplacement.entrySet()){
            Position position = entry.getKey();
            System.out.printf("   %-3s  %7.1f   (fixed %s%d would have said %.1f)%n",
                    position, entry.getValue(), position, report.replacement.rankOf(position),
                    report.replacement.of(position));
        }

        System.out.printf("%nkeeping frees your round-%d pick (overall %d), which returns %s = %+.0f%n",
                StartingLineup.lastStarterRound(), report.freedPickNumber,
                report.freedPickDescription, report.freedPickValue);
        System.out.println("so a keeper has to beat that to be worth a slot\n");

        System.out.printf("%-24s %-4s %-6s %8s %8s %8s%n", "PLAYER", "POS", "KEEP", "PROJ", "VORP", "NET");
        int worth = 0;
        for(Valued valued : report.candidates){
            if(valued.worthAKeeperSlot()){
                worth++;
            }
            System.out.printf("%-24s %-4s r%-5d %8.0f %+8.0f %+8.0f%s%n",
                    valued.keeper.player.firstName + " " + valued.keeper.player.lastName,
                    valued.keeper.player.position,
                    valued.keeper.roundCanBeKept,
                    valued.projectedPoints,
                    valued.valueOverReplacement,
                    valued.net(),
                    valued.worthAKeeperSlot() ? "   <- worth a keeper slot" : "");
        }
        if(!report.unprojected.isEmpty()){
            System.out.println("\nno projection published, so not graded: "
                    + String.join(", ", report.unprojected));
        }
        System.out.printf("%n%d of %d candidates clear the bar. You may keep up to %d.%n",
                worth, report.candidates.size(), configuration.getMaxKeepers());
        if(worth < configuration.getMaxKeepers()){
            System.out.println("Keeping fewer than the maximum is allowed, and better than keeping a");
            System.out.println("player who costs more than he returns.");
        }
    }

}
