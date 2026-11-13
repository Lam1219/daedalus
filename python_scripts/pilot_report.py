"""Home pilot report: reads a folder of press files and prints H1, H3 and H12 verdicts.

    python python_scripts/pilot_report.py path/to/press_files/ [--csv timings.csv]

The rules and thresholds live in daedalus_watch_kit/pilot_analysis.py (docs/product/plan.md, B7).
"""

import argparse
import csv
from dataclasses import asdict
from pathlib import Path

from daedalus_watch_kit.pilot_analysis import analyse, report


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("folder", type=Path)
    ap.add_argument("--csv", type=Path, help="also write one row per press")
    a = ap.parse_args()
    timings, v = analyse(a.folder)
    print(report(v))
    if a.csv:
        with a.csv.open("w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(asdict(timings[0]).keys()) if timings else ["participant"])
            w.writeheader()
            for t in timings:
                w.writerow(asdict(t))


if __name__ == "__main__":
    main()
