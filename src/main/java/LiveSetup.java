import PlayerImportAndSetup.Position;
import java.util.*;

/**
 * THE ONE PLACE THE LIVE PATH IS WARMED.
 *
 * Every tool that prices a board - Draft2026 and every harness that claims to
 * certify it - needs the same twenty-five lines: a sixteen-round schedule, the
 * fitted choice model, the planner, the keepers, this year's curve, the
 * nflverse pools, the defence scatter, and the survival table. They each had
 * their own copy, and the copies DIVERGED:
 *
 *   TailLegality      never set scheduleRounds, so it measured the 9-round game
 *   DryRun            never built the survival table
 *   FragilityBinding  never built it either - and its "0 of 66 refused"
 *                     reached DRAFT-READY as a fact about what ships
 *   LivePathStress    never built it, found by the adversarial pass
 *   PreFlight         never set scheduleRounds, and complained about itself
 *
 * That is five, twice written by me after the fault had already been named
 * once. A harness warmed differently from the tool certifies nothing, and no
 * amount of care fixes a shape that has to be retyped eight times. So it is
 * typed once here.
 *
 * `-PsurvivalDraws=0` still turns the survival table off, because the tagged
 * numbers were measured without it and reproducing them has to stay possible.
 */
public final class LiveSetup {

    public final AAAConfiguration configuration;
    public final DraftPlanner planner;
    public final DraftSimulator simulator;
    public final Set<String> kept;
    public final Map<Position, double[]> curve;
    public final Map<Position, List<List<Double>>> pools;
    public final List<String> order;
    public final List<PairwiseOdds.Man> men;
    public final String draftID;
    public final double survivalSeconds;

    private LiveSetup(AAAConfiguration configuration, DraftPlanner planner,
                      DraftSimulator simulator, Set<String> kept,
                      Map<Position, double[]> curve,
                      Map<Position, List<List<Double>>> pools, List<String> order,
                      List<PairwiseOdds.Man> men, String draftID,
                      double survivalSeconds){
        this.configuration = configuration;
        this.planner = planner;
        this.simulator = simulator;
        this.kept = kept;
        this.curve = curve;
        this.pools = pools;
        this.order = order;
        this.men = men;
        this.draftID = draftID;
        this.survivalSeconds = survivalSeconds;
    }

    /** Exactly what Draft2026 runs on. Nothing else may assemble this. */
    public static LiveSetup forTonight() throws Exception {
        System.setProperty("scheduleRounds", "16");
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int last = Integer.parseInt(configuration.getSeason()) - 1;
        Map<String, Double> earliness = SelectionModel.qbEarliness(configuration, last);
        ChoiceModel choice = BoostedSelectionModel.fitShipped(configuration, last, earliness);
        DraftPlanner planner = DraftPlanner.forCurrentSeason(configuration,
                DraftPlanner.keepersFromProperty(configuration), choice, earliness);
        DraftSimulator simulator = planner.simulator();
        double survivalSeconds = LiveBoard.warmSurvival(planner, simulator);
        Set<String> kept = LiveBoard.kept(configuration);
        Map<Position, double[]> curve = LiveBoard.thisYear(planner, kept);
        Map<String, List<DetectionLag.Man>> wider = NflverseBoards.usable(null);
        List<String> order = new ArrayList<>(new TreeMap<>(wider).keySet());
        List<PairwiseOdds.Man> men = PairwiseOdds.nflverseMen(wider, order);
        Map<Position, List<List<Double>>> pools =
                new EnumMap<>(BoardValue.pools(men, curve));
        List<List<Double>> defence = LiveBoard.defenceScatter();
        if(!defence.isEmpty()){
            pools.put(Position.DEF, defence);
        }
        return new LiveSetup(configuration, planner, simulator, kept, curve, pools,
                order, men, configuration.getDraftID(), survivalSeconds);
    }

    /** One line saying which rule the numbers below were measured under. */
    public String rule(){
        return LiveBoard.SURVIVAL == null
                ? "survival table OFF - the retired ADP cutoff is in force"
                : String.format("survival table on, as in Draft2026 (%.0fs at warm)",
                        survivalSeconds);
    }
}
