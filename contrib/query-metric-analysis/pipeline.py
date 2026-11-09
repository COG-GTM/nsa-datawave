"""Rebuild all artifacts from a local, complete-snapshot export."""

import argparse
import hashlib
import json
from pathlib import Path

from analysis import analyze, normalize, read_records
from render import html_report, static_plots

ROOT = Path(__file__).parent


def walkthrough(analysis, label):
    change = analysis["change"]
    finding = "\n\n".join(f for f in analysis["findings"] if change and f.startswith(change["queryLogic"] + ":"))
    boundary = f"The strongest eligible shift begins {change['date']} in {change['queryLogic']}." if change else "No eligible slowdown was detected."
    unflagged = ", ".join(x["queryLogic"] for x in analysis["candidates"] if not x["flagged"])
    return f"""# Three-minute walkthrough

**{label}.** About three minutes, including brief pauses for controls.

## 0:00–0:30 — The question and the evidence

“When did query performance degrade, which query types were affected, and what
should an engineer investigate next? This report answers from records, with their
provenance visible throughout. We inspected DataWave's model first. The performance
measure is first-page return time in milliseconds. The model's elapsedTime is
query lifetime, including idle time between requests—not execution latency.”

## 0:30–1:10 — Locate the change

Show the trend chart and hover at the boundary.

“We retained {analysis['summary']['queries']:,} unique queries using each query's latest
complete snapshot. Updates and pages are not extra queries. Python tested daily
boundaries separately within each query logic, without reading scenario labels.
{boundary} Volume and timing are separate, and hover details show valid-timing
denominators. This is a candidate daily boundary, not an exact incident time.”

## 1:10–1:55 — Compare query logic and recorded errors

Select the affected logic, then show the before/after table. Read these calculated results:

{finding}

“{unflagged or 'No comparison group'} did not meet the slowdown threshold.
Queries per day account for unequal comparison windows. Error rates show their
denominators. Filters recalculate charts and summaries together; the global
change point stays fixed and labeled.”

## 1:55–2:30 — What to investigate next

Switch the scatter selector to iterator next() calls.

“Correlations are split by logic and period, not pooled across workloads. Counts
span the query; timing spans page one. Associations cannot prove a cause. Next,
compare query plans, deployment and index changes, and like-for-like query
templates around the boundary. Inspect executor queues, storage reads, and error
logs. Iterator calls are operations—not unique records scanned.”

## 2:30–3:00 — Limits and reproduction

Show quality, then reset filters.

“Missing timings remain missing; zero is valid. Failed queries without a page
are excluded from timing percentiles, potentially understating bad experiences.
The synthetic case validates the analysis, not a real deployment or a product defect.
One command rebuilds this offline report, images, tables and checks. A compatible
local export can be substituted without deploying Java services or Accumulo.”
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="JSON array or JSONL of COMPLETE snapshots; .gz accepted")
    parser.add_argument("--output", type=Path, default=ROOT / "output")
    parser.add_argument("--regenerate", action="store_true", help="Recreate the bundled synthetic records deterministically")
    parser.add_argument("--synthetic", action="store_true", help="Prominently mark a user-supplied synthetic export")
    args = parser.parse_args()
    if args.input and args.regenerate:
        parser.error("--regenerate cannot be combined with --input")
    if args.regenerate:
        from generate import generate
        generate(ROOT / "data")
    path = args.input or ROOT / "data/metrics.jsonl.gz"
    synthetic = args.synthetic or path.resolve() == (ROOT / "data/metrics.jsonl.gz").resolve()
    label = "SYNTHETIC DATA — fictional query metrics" if synthetic else "LOCAL EXPORT — unverified provenance"
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    frame, raw = normalize(read_records(path))
    analysis, tables = analyze(frame, raw)
    analysis.update(provenance=label, inputSha256=digest, synthetic=synthetic)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "analysis.json").write_text(json.dumps(analysis, indent=2, allow_nan=False) + "\n")
    for name, table in {**tables, "queries": frame}.items():
        table = table.copy()
        table.insert(0, "provenance", label)
        table.to_csv(args.output / f"{name}.csv", index=False)
    html_report(frame, analysis, args.output / "report.html", label, synthetic, digest)
    static_plots(frame, analysis, tables, args.output, label)
    script = walkthrough(analysis, label)
    if not synthetic:
        script = script.replace("The synthetic case validates the analysis, not a real deployment or a product defect.",
                                "A local export alone cannot establish a cause or a product defect.")
    (args.output / "walkthrough.md").write_text(script)
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(args.output.iterdir()) if p.is_file() and p.name != "manifest.json"}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"output": str(args.output), "summary": analysis["summary"], "change": analysis["change"], "quality": raw}, indent=2))


if __name__ == "__main__":
    main()
