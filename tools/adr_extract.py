#!/usr/bin/env python3
"""
Turns an Abusing Draft Rankings workbook into the dated defaults CSV that
FeedResemblance / ReachAudit / AccuracyShootout already read:

    data/sleeper-defaults-<season>-<yyyymmdd>.csv

The sheet that matters for this league is the half-PPR Sleeper tab - the
platform's literal default draft-room order, which is what the room actually
sees on screen. Its name drifts between editions ("Sleeper .5PPR" in 2020-2024,
"Sleeper Half PPR" in 2026) and so do its columns ("SleeperRank" vs "Sleeper
ADP"), so headers are matched by label, never by column letter.

    python3 tools/adr_extract.py <workbook.xlsx> <season> <yyyymmdd>
    python3 tools/adr_extract.py data/adr/2025-20250823-wayback.xlsx 2025 20250823
"""
import csv, os, re, sys, zipfile

SHEET_NAMES = ["Sleeper Half PPR", "Sleeper .5PPR", "Sleeper HalfPPR", "Sleeper 0.5PPR"]
HEADERS = {
    "name": ["player", "name"],
    "team": ["team"],
    "position": ["pos", "position"],
    "consensus_adp": ["adp"],
    "fp_ecr": ["fantasypros", "fp"],
    "sleeper_rank": ["sleeperrank", "sleeper adp", "sleeper"],
    "landmine": ["landmine"],
}


def shared_strings(archive):
    try:
        raw = archive.read("xl/sharedStrings.xml").decode("utf-8", "ignore")
    except KeyError:
        return []
    return ["".join(re.findall(r"<t[^>]*>(.*?)</t>", block, re.S))
            for block in re.findall(r"<si>.*?</si>", raw, re.S)]


def grid(archive, sheet_name):
    """The sheet as a list of {column letter: value} dicts, one per row."""
    workbook = archive.read("xl/workbook.xml").decode("utf-8", "ignore")
    relations = archive.read("xl/_rels/workbook.xml.rels").decode("utf-8", "ignore")
    match = re.search(r'<sheet[^>]*name="%s"[^>]*r:id="([^"]+)"'
                      % re.escape(sheet_name), workbook)
    if not match:
        return None
    target = re.search(r'Id="%s"[^>]*Target="([^"]+)"' % match.group(1),
                       relations).group(1)
    xml = archive.read("xl/" + target.lstrip("/")).decode("utf-8", "ignore")
    strings = shared_strings(archive)
    rows = []
    for row_xml in re.findall(r"<row[^>]*>.*?</row>", xml, re.S):
        cells = {}
        # cells can be self-closing (<c r="A1" s="2"/>); a regex that only
        # knows the <c ...>...</c> form swallows the next cell and shifts every
        # column one letter left.
        for reference, attributes, body in re.findall(
                r'<c r="([A-Z]+)\d+"([^>]*?)(?:/>|>(.*?)</c>)', row_xml, re.S):
            value = re.search(r"<v>(.*?)</v>", body, re.S)
            if not value:
                continue
            text = value.group(1)
            if 't="s"' in attributes and text.isdigit():
                text = strings[int(text)] if int(text) < len(strings) else text
            cells[reference] = text
        rows.append(cells)
    return rows


def column_map(header_row):
    """Header label -> column letter, resolved by fuzzy label match."""
    resolved = {}
    for field, wanted in HEADERS.items():
        for letter, label in header_row.items():
            flat = str(label).strip().lower()
            if any(flat == w or flat.startswith(w) for w in wanted):
                resolved.setdefault(field, letter)
    return resolved


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    path, season, stamp = sys.argv[1], sys.argv[2], sys.argv[3]
    archive = zipfile.ZipFile(path)
    rows = None
    for name in SHEET_NAMES:
        rows = grid(archive, name)
        if rows:
            print(f"sheet: {name}")
            break
    if not rows:
        workbook = archive.read("xl/workbook.xml").decode("utf-8", "ignore")
        print("no half-PPR Sleeper sheet; workbook has "
              + str(re.findall(r'<sheet[^>]*name="([^"]+)"', workbook)))
        return 1

    header = rows[0]
    columns = column_map(header)
    # the name column carries no header in these sheets: it is the column
    # immediately left of "Team".
    if "name" not in columns and "team" in columns:
        columns["name"] = chr(ord(columns["team"]) - 1)
    if "name" not in columns or "sleeper_rank" not in columns:
        print(f"headers not recognised: {header}")
        return 1

    fields = ["name", "team", "position", "consensus_adp", "fp_ecr",
              "sleeper_rank", "landmine"]
    output = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                          "data", f"sleeper-defaults-{season}-{stamp}.csv")
    written = 0
    with open(output, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(fields)
        for row in rows[1:]:
            name = row.get(columns["name"], "").strip()
            rank = row.get(columns.get("sleeper_rank", ""), "").strip()
            if not name or not rank:
                continue
            writer.writerow([row.get(columns.get(f, ""), "") for f in fields])
            written += 1
    print(f"wrote {os.path.relpath(output)} ({written} players)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
