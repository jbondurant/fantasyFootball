# External projection sources

Each file here is a named projection slot: `<name>.csv` becomes selectable
with `-Pprojections=<name>` on DraftPlanner / KeeperPlan / KeeperWhy, and
`-Pprojections=blend:a,b,...` averages feeds. `ProjectionSources` lists slot
status; players a file does not cover keep Sleeper's numbers.

Two formats, told apart by the header row:

    # passTD=4 rec=0.5            <- the site's scoring; bridged via Sleeper
    name,position,points           TD/reception counts (ProjectionBridge)
    Josh Allen,QB,372.5

    name,position,pass_yd,pass_td,pass_int,rush_yd,rush_td   <- stat counts
    Josh Allen,QB,3825.5,28.5,10.5,550.5,10.5                   (sportsbook
                                                                 props etc.)
    scored directly under league settings - any Sleeper stat keys work:
    pass_yd pass_td pass_int rush_yd rush_td rec rec_yd rec_td fum_lost

Registered slot names: etr, fantasypoints, pff, draftsharks, 4for4,
footballguys, ftn, rotoviz, unexpectedpoints, fantasyomatic, actionnetwork,
numberfire, rotogrinders, props. (sleeper and borischen are automatic.)

CSV exports from subscription sites are for personal use - which is why this
directory's CSVs are gitignored and must stay that way.
