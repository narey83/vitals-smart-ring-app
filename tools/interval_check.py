#!/usr/bin/env python3
"""Does the ring actually measure on its own at the configured interval?

Reads a CSV export of the app's readings database (epoch millis, kind, value, extra) and reports the gap between
consecutive readings of each kind. Automatic sampling should show a cluster of gaps at the
configured interval; manual taps show up as isolated short gaps.

    adb exec-out run-as uk.co.r99vitals cat files/readings.db > readings.db
    sqlite3 -csv readings.db 'select at,kind,value,extra,manual from readings order by at,id' > readings.csv
    python3 interval_check.py readings.csv 15
"""
import sys
from collections import defaultdict
from datetime import datetime, timedelta

path = sys.argv[1]
expected = int(sys.argv[2]) if len(sys.argv) > 2 else None

rows = defaultdict(list)
for line in open(path):
    line = line.strip()
    if not line:
        continue
    parts = line.split(",")
    rows[parts[1]].append(int(parts[0]) / 1000.0)

if not rows:
    sys.exit("no readings in file — the app has recorded nothing at all")

for kind in sorted(rows):
    times = sorted(rows[kind])
    span = timedelta(seconds=times[-1] - times[0])
    first = datetime.fromtimestamp(times[0]).strftime("%Y-%m-%d %H:%M")
    last = datetime.fromtimestamp(times[-1]).strftime("%Y-%m-%d %H:%M")
    print(f"\n{kind}: {len(times)} readings over {span}  ({first} -> {last})")

    if len(times) < 2:
        print("  too few to show an interval")
        continue

    gaps = [(b - a) / 60.0 for a, b in zip(times, times[1:])]
    gaps_sorted = sorted(gaps)
    median = gaps_sorted[len(gaps_sorted) // 2]
    print(f"  gaps (min): min {min(gaps):.1f}  median {median:.1f}  max {max(gaps):.1f}")

    # Overnight is the honest test: nobody is tapping the button at 03:00.
    night = [
        (b - a) / 60.0
        for a, b in zip(times, times[1:])
        if datetime.fromtimestamp(a).hour in range(1, 6)
    ]
    if night:
        night_median = sorted(night)[len(night) // 2]
        print(f"  overnight (01:00-06:00): {len(night)} gaps, median {night_median:.1f} min")
    else:
        print("  overnight (01:00-06:00): no readings — nothing collected unattended")

    if expected:
        close = [g for g in gaps if abs(g - expected) <= max(2.0, expected * 0.25)]
        verdict = "looks automatic" if len(close) >= 3 else "NO evidence of automatic sampling"
        print(f"  near {expected} min: {len(close)}/{len(gaps)} gaps -> {verdict}")
