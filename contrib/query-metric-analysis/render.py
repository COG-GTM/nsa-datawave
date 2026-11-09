"""Offline report plus static, provenance-marked plots."""

import html
import json
import re
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import numpy as np
import pandas as pd
from plotly.offline import get_plotlyjs

from analysis import NUMERIC_FIELDS, daily_table

COLORS = ["#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00"]
ROOT = Path(__file__).parent


def html_report(frame, analysis, path, label, synthetic, digest):
    fields = [*NUMERIC_FIELDS, "firstPageReturnTimeMs"]
    columns = ["queryId", "day", "queryLogic", "error", "isFinal", *fields, *[f + "Status" for f in fields]]
    payload = {"data": json.loads(frame[columns].to_json(orient="split", index=False)), "analysis": analysis,
               "qualityFields": fields, "label": label, "synthetic": synthetic}
    change = analysis["change"]
    raw = analysis["quality"]
    substitutions = {
        "LABEL": label,
        "DISCLAIMER": "Fictional records; not operational activity or a measured product defect" if synthetic else "User-supplied local export; provenance not independently verified",
        "CONCLUSION": f"A candidate slowdown begins {change['date']} in {change['queryLogic']}" if change else "No eligible slowdown detected",
        "CHANGE_DETAIL": f"Daily-median timing shifts {change['dailyMedianRatio']:.2f}×; the two-level fit improves by {change['fitImprovement']:.1%}. This boundary was inferred from records, not scenario labels." if change else analysis["findings"][0],
        "INGESTION": f"{raw['inputRows']:,} input rows → {raw['retainedQueries']:,} unique queries. {raw['exactDuplicateRows']:,} exact duplicate rows removed; {raw['supersededSnapshots']:,} earlier snapshots superseded; {raw['excludedRows']:,} rows excluded ({raw['invalidIdentityRows']:,} invalid identity/timestamp rows, {raw['quarantinedQueryIds']:,} quarantined query IDs).",
        "LIMITATION": "All records are deterministic synthetic data. The observed pattern validates the analysis, not the behavior of a real deployment. It does not represent real organizational activity or establish a measured DataWave defect." if synthetic else "This export has not been independently validated against service logs or deployment history. Results describe the supplied records, not a proven product defect.",
        "HASH": digest,
    }
    substitutions = {key: html.escape(value) for key, value in substitutions.items()}
    substitutions["FINDINGS"] = "".join(f"<li>{html.escape(f)}</li>" for f in analysis["findings"])
    substitutions["PAYLOAD"] = json.dumps(payload, separators=(",", ":"), allow_nan=False).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    substitutions["PLOTLY"] = get_plotlyjs()
    substitutions["SCRIPT"] = (ROOT / "report.js").read_text()
    content = re.sub(r"@@([A-Z_]+)@@", lambda match: substitutions[match[1]], (ROOT / "report.html").read_text())
    path.write_text(content, encoding="utf-8")


def static_plots(frame, analysis, tables, output, label):
    plt.rcParams.update({"font.size": 11, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.titleweight": "bold", "axes.labelcolor": "#193247", "text.color": "#193247"})
    logics = sorted(frame.queryLogic.unique())
    colors = {logic: COLORS[i % len(COLORS)] for i, logic in enumerate(logics)}
    change = analysis["change"]

    def save(fig, name, subtitle):
        fig.text(.015, .015, f"{label} | {subtitle}", fontsize=9, color="#7a4800")
        fig.tight_layout(rect=(0, .065, 1, .96))
        fig.savefig(output / f"{name}.png", dpi=180, facecolor="white")
        plt.close(fig)

    def mark(ax):
        locator = mdates.AutoDateLocator(minticks=4, maxticks=7)
        ax.xaxis.set_major_locator(locator)
        ax.xaxis.set_major_formatter(mdates.ConciseDateFormatter(locator))
        if change:
            ax.axvline(pd.Timestamp(change["date"]), color="#9b3a18", linestyle="--", linewidth=1.5,
                       label=f"Suspected change: {change['date']}")

    overall = frame.copy()
    overall["queryLogic"] = "All"
    daily = daily_table(overall)
    x = pd.to_datetime(daily.day)
    fig, axes = plt.subplots(2, 1, figsize=(12, 7.5), sharex=True, gridspec_kw={"height_ratios": [2, 1]})
    axes[0].plot(x, daily.medianMs, color=COLORS[0], label="Median", marker="o", markersize=3)
    axes[0].plot(x, daily.p95Ms, color=COLORS[1], label="95th percentile", linestyle="--")
    mark(axes[0]); axes[0].legend(loc="upper left", fontsize=9)
    axes[0].set(title="First-page return time and query volume", ylabel="First-page return time (ms)", ylim=(0, None))
    axes[1].bar(x, daily.queries, color="#7299b7"); mark(axes[1])
    axes[1].set(ylabel="Queries", xlabel="Query creation date (UTC)")
    save(fig, "01-trends", "One latest snapshot per query | Missing timings excluded; queries retained")

    fig, ax = plt.subplots(figsize=(12, 6.7))
    stats, positions, labels, boxcolors = [], [], [], []
    for logic in logics:
        group = frame[frame.queryLogic == logic]
        for period in (["Before", "After"] if change else ["All"]):
            part = group if not change else group[(group.day < change["date"]) if period == "Before" else (group.day >= change["date"])]
            times = part.firstPageReturnTimeMs.dropna()
            if len(times):
                q = times.quantile([.05, .25, .5, .75, .95])
                stats.append(dict(whislo=q.loc[.05], q1=q.loc[.25], med=q.loc[.5], q3=q.loc[.75], whishi=q.loc[.95], fliers=[]))
                positions.append(len(positions) + 1); labels.append(f"{logic}\n{period}\nn={len(times):,}")
                boxcolors.append(colors[logic] if period != "Before" else "#fff")
    if stats:
        boxes = ax.bxp(stats, positions=positions, showfliers=False, patch_artist=True, medianprops={"color": "#152b3b", "linewidth": 2})
        for box, color in zip(boxes["boxes"], boxcolors):
            box.set_facecolor(color)
        ax.set_xticks(positions, labels, fontsize=9)
    ax.set(title="Timing distributions by query logic", ylabel="First-page return time (ms)", ylim=(0, None))
    save(fig, "02-distributions", "Boxes: p25–p75 | Whiskers: p5–p95 | Outline: before; filled: after")

    fig, ax = plt.subplots(figsize=(12, 6.7))
    for i, logic in enumerate(logics):
        group = tables["daily"][tables["daily"].queryLogic == logic]
        ax.plot(pd.to_datetime(group.day), group.errorRate * 100, color=colors[logic], marker=["o", "s", "D"][i % 3],
                linestyle=["-", "--", ":"][i % 3], markersize=4, label=logic)
    mark(ax); ax.legend(fontsize=9)
    ax.set(title="Recorded error rates by day and query logic", ylabel="Recorded error rate (%)", xlabel="Query creation date (UTC)", ylim=(0, None))
    save(fig, "03-errors", "Errors / known-status queries | Per-day numerators and denominators: daily.csv")

    fig, ax = plt.subplots(figsize=(12, 6.7))
    pairs = frame.dropna(subset=["firstPageReturnTimeMs", "numResults"])
    sample = pairs.iloc[::max(1, int(np.ceil(len(pairs) / 1500)))]
    for logic in logics:
        group = sample[sample.queryLogic == logic]
        ax.scatter(group.numResults, group.firstPageReturnTimeMs, color=colors[logic], s=16, alpha=.45, label=logic)
    ax.legend()
    ax.set(title="First-page timing versus returned results", xlabel="Returned results (query cumulative count)", ylabel="First-page return time (ms)", xlim=(0, None), ylim=(0, None))
    save(fig, "04-scatter", f"{len(sample):,}/{len(pairs):,} complete pairs plotted | Association only; full-pair correlations in correlations.csv")
