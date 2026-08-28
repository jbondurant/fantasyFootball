import PlayerImportAndSetup.Position;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic flux balance analysis, applied to a fantasy draft. Justin asked for
 * this as a joke and it turned out to be a real LP relaxation, which is the
 * best kind of joke.
 *
 * The dFBA correspondence, taken seriously:
 *   metabolites          roster slots (QB, RB, WR, TE, FLEX)
 *   reactions            "draft a player at position p" - consumes one pick,
 *                        produces one unit of position-p roster
 *   flux vector v        how many of my remaining picks go to each position
 *   stoichiometry S.v=0  every pick fills exactly one slot; slots must balance
 *   flux bounds          a position cannot absorb more picks than it has open
 *                        slots, nor more than the pool can supply
 *   biomass objective    best-nine points
 *   substrate depletion  the waiting table - available talent at each position
 *                        falls as the draft consumes it, exactly like a
 *                        metabolite being eaten
 *   the DYNAMIC part     re-solve the LP every round with updated depletion,
 *                        take the highest-flux reaction, advance one timestep
 *
 * Where the analogy breaks: fluxes are continuous, picks are integral. So this
 * is an LP relaxation rounded by taking the maximum-flux reaction - which is
 * the standard dFBA time-stepping scheme, and also, unavoidably, a greedy.
 *
 * The LP is small enough (4 reactions, <=7 timesteps) to solve by enumerating
 * integer allocations, so no solver dependency.
 *
 *   ./gradlew run -Pmain=FluxDraft [-Ptrials=400]
 */
public class FluxDraft {

    public static void main(String[] args){
        AAAConfiguration configuration = AAAConfiguration.getInstance();
        int trials = Integer.getInteger("trials", 400);
        PolicyTournament tournament = PolicyTournament.forCurrentGame(configuration, 300);

        Map<String, double[]> results = new java.util.LinkedHashMap<>();
        results.put("flux-balance (dFBA)", tournament.evaluate(named(
                seed -> tournament.new FluxPolicy()), trials));
        results.put("greedy-vorp", tournament.evaluate(named(
                seed -> tournament.new GreedyVorp()), trials));
        results.put("oldschool-2-vorp", tournament.evaluate(named(
                seed -> tournament.new Lookahead(2, 16, PolicyTournament.Tail.VORP, seed)),
                trials));

        System.out.printf("%n%-26s %10s %8s%n", "ENGINE", "mean", "+/-SE");
        results.entrySet().stream()
                .sorted((a, b) -> Double.compare(PolicyTournament.mean(b.getValue()),
                        PolicyTournament.mean(a.getValue())))
                .forEach(e -> System.out.printf("%-26s %10.1f %8.1f%n", e.getKey(),
                        PolicyTournament.mean(e.getValue()),
                        PolicyTournament.standardError(e.getValue())));
        System.out.println("\nIf flux-balance lands on greedy-vorp, that is the honest"
                + "\nresult: the LP relaxation of a draft IS marginal-value greedy, and"
                + "\ndressing it in stoichiometry does not change what it computes.");
    }

    static PolicyTournament.Factory named(
            java.util.function.LongFunction<PolicyTournament.TournamentPolicy> make){
        return new PolicyTournament.Factory() {
            @Override
            public String name(){
                return "flux";
            }

            @Override
            public PolicyTournament.TournamentPolicy create(long seed){
                return make.apply(seed);
            }
        };
    }
}
