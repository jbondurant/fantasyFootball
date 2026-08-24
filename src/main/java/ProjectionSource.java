public enum ProjectionSource {
    /** FantasyPros rest-of-season. No longer carries points - see InSeasonProjectionsFP. */
    IN_SEASON_FP_SITE,
    /** FantasyPros rest-of-season, from CSVs exported by hand into the project root. */
    IN_SEASON_FP_CSV,
    /** Sleeper's projected stat lines, scored with this league's settings. */
    SLEEPER;

}
