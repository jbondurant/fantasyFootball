#!/usr/bin/env python3
"""
Recovers the "Abusing Draft Rankings" sheets (firstseedsports.com) as they
stood NEAR EACH SEASON'S DRAFT, rather than as they stand today.

Why this exists: data/adr-20xx.xlsx were pulled by exporting the sheets LIVE
at their original Google ids. A live export shows whatever the author left in
the sheet - which for an old season can be a post-draft or in-season state,
and for 2025 is not that season at all (the 2025 id was recycled into the 2026
edition, so data/adr-2025.xlsx actually holds "2026 Draft Values"). Draft-day
values have to come from a dated archive, the same way the dated Sleeper ADP
captures did.

Two archives are tried per season, in order:
  1. Wayback captures of the sheet itself (docs.google.com/spreadsheets/d/<id>)
     - a true dated snapshot when one exists.
  2. The live Google export - kept only as a fallback, and always stamped with
     the capture date of the PAGE that named the id, never with a draft date.

Writes data/adr/<season>-<yyyymmdd>-<source>.xlsx and appends one row per file
to data/adr/manifest.csv. Nothing here computes a number; AdrProvenance.java
reads the manifest against the league's real draft dates.

    python3 tools/adr_harvest.py            # every season
    python3 tools/adr_harvest.py 2025 2026  # just these
"""
import csv, os, re, subprocess, sys, time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "data", "adr")
MANIFEST = os.path.join(OUT, "manifest.csv")
SEASONS = ["2020", "2021", "2022", "2023", "2024", "2025", "2026"]
UA = "adr-draft-date-harvest (fantasyFootball research)"
PAUSE = 12          # web.archive.org rate-limits hard; be a good citizen
RETRIES = 6
OUTAGE_WAIT = 600   # the whole service goes dark for stretches; wait it out


def get(url, binary=False):
    """Fetch a url. Shells out to curl: python's urllib times out against
    web.archive.org from this machine while curl over IPv4 does not."""
    for attempt in range(RETRIES):
        finished = subprocess.run(
            ["curl", "-sL", "-4", "--compressed", "-m", "120",
             "-A", UA, url],
            capture_output=True)
        data = finished.stdout
        if finished.returncode == 0 and data:
            return data if binary else data.decode("utf-8", errors="ignore")
        print(f"    retry {attempt}: curl exit {finished.returncode}, "
              f"{len(data)} bytes", flush=True)
        time.sleep(PAUSE * (attempt + 1))
    return None


def captures(url_pattern, collapse="timestamp:8"):
    """Every archived capture of a url, oldest first: [(timestamp, url)]."""
    query = ("https://web.archive.org/cdx/search/cdx?url=" + url_pattern +
             "&output=text&filter=statuscode:200&collapse=" + collapse +
             "&limit=400")
    text = get(query)
    if not text:
        return []
    found = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 3:
            found.append((parts[1], parts[2]))
    return sorted(found)


def sheet_ids(html):
    """Google Sheet ids linked from an Abusing Draft Rankings page."""
    ids = re.findall(r"docs\.google\.com/spreadsheets/d/(?:e/)?([\w-]{20,})", html)
    seen = []
    for identifier in ids:
        if identifier not in seen:
            seen.append(identifier)
    return seen


def save(season, stamp, source, data):
    if not data or data[:2] != b"PK":
        return None
    name = f"{season}-{stamp}-{source}.xlsx"
    path = os.path.join(OUT, name)
    with open(path, "wb") as handle:
        handle.write(data)
    print(f"    saved {name} ({len(data)} bytes)", flush=True)
    return name


def harvest(season, rows):
    print(f"=== {season} ===", flush=True)
    page_captures = captures(f"firstseedsports.com/abusing-draft-rankings-{season}/")
    page_captures = [c for c in page_captures if "utm" not in c[1] and "/feed" not in c[1]]
    print(f"  {len(page_captures)} page captures: "
          f"{[c[0][:8] for c in page_captures]}", flush=True)
    time.sleep(PAUSE)

    identifiers = {}       # sheet id -> earliest page capture naming it
    for timestamp, url in page_captures:
        html = get(f"https://web.archive.org/web/{timestamp}id_/{url}")
        time.sleep(PAUSE)
        if not html:
            continue
        for identifier in sheet_ids(html):
            identifiers.setdefault(identifier, timestamp)
    print(f"  sheet ids: {identifiers}", flush=True)

    for identifier, page_stamp in identifiers.items():
        # 1. a real dated snapshot of the sheet, if the crawler ever took one
        sheet_captures = captures(f"docs.google.com/spreadsheets/d/{identifier}*")
        time.sleep(PAUSE)
        print(f"  {identifier[:14]}..: {len(sheet_captures)} sheet captures", flush=True)
        for timestamp, url in sheet_captures:
            if not re.search(r"(export|pubhtml|htmlview|gviz|/pub)", url):
                continue
            data = get(f"https://web.archive.org/web/{timestamp}id_/{url}", binary=True)
            time.sleep(PAUSE)
            name = save(season, timestamp[:8], "wayback", data)
            if name:
                rows.append([season, timestamp[:8], "wayback", identifier, url, name])
            elif data:
                # pubhtml is HTML, not xlsx - keep it, it still carries values
                path = os.path.join(OUT, f"{season}-{timestamp[:8]}-wayback.html")
                with open(path, "wb") as handle:
                    handle.write(data)
                print(f"    saved {os.path.basename(path)} (html)", flush=True)
                rows.append([season, timestamp[:8], "wayback-html", identifier, url,
                             os.path.basename(path)])

        # 2. the live sheet, stamped with the PAGE capture that named the id
        data = get(f"https://docs.google.com/spreadsheets/d/{identifier}/export?format=xlsx",
                   binary=True)
        time.sleep(PAUSE)
        name = save(season, page_stamp[:8], "live", data)
        if name:
            rows.append([season, page_stamp[:8], "live", identifier,
                         "docs.google.com/spreadsheets/d/" + identifier, name])
        else:
            print(f"    live export unavailable for {identifier[:14]}..", flush=True)


def archive_is_up():
    """web.archive.org goes fully dark for long stretches. Every fetch below
    would burn its retries against a dead service, so check once and wait."""
    probe = subprocess.run(
        ["curl", "-sL", "-4", "-m", "40", "-o", "/dev/null",
         "-w", "%{http_code}", "-A", UA,
         "https://web.archive.org/web/2021/https://example.com/"],
        capture_output=True)
    return probe.stdout.decode().strip().startswith("2")


def main():
    wanted = sys.argv[1:] or SEASONS
    waited = 0
    while not archive_is_up():
        print(f"web.archive.org unreachable; waited {waited // 60} min", flush=True)
        time.sleep(OUTAGE_WAIT)
        waited += OUTAGE_WAIT
        if waited > 6 * 3600:
            print("ARCHIVE STILL DOWN AFTER 6H - giving up", flush=True)
            return
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for season in wanted:
        harvest(season, rows)
    exists = os.path.exists(MANIFEST)
    with open(MANIFEST, "a", newline="") as handle:
        writer = csv.writer(handle)
        if not exists:
            writer.writerow(["season", "captureDate", "source", "sheetId", "url", "file"])
        writer.writerows(rows)
    print(f"ADR HARVEST COMPLETE: {len(rows)} files", flush=True)


if __name__ == "__main__":
    main()
