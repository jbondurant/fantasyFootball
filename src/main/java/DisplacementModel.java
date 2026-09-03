import PlayerImportAndSetup.Position;

import java.util.Random;

/** A distribution of how far a player lands from his par selection. */
public interface DisplacementModel {
    double sample(Random random, int parDepth, Position position);
}
