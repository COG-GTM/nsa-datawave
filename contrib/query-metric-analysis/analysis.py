"""Analyze complete metric snapshots, never distributed update deltas."""

import gzip
import json
import math
from collections import Counter
from pathlib import Path

import numpy as np
import pandas as pd


NUMERIC_FIELDS = ("setupTime", "createCallTime", "loginTime", "numResults", "numPages",
                  "nextCount", "seekCount", "sourceCount", "yieldCount", "docRanges", "fiRanges")
TIMINGS = {"setupTime", "createCallTime", "loginTime", "firstPageReturnTimeMs"}
FINAL = {"CLOSED", "CANCELLED", "MAXRESULTS", "NEXTTIMEOUT", "TIMEOUT", "SHUTDOWN"}
LIFECYCLES = FINAL | {"NONE", "DEFINED", "INITIALIZED", "RESULTS", "MAXWORK"}


def read_records(path):
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        if path.name.endswith((".json", ".json.gz")):
            records = json.load(stream)
        else:
            records = [json.loads(line) for line in stream if line.strip()]
    if not isinstance(records, list) or not records or not all(isinstance(x, dict) for x in records):
        raise ValueError("Input must be a nonempty JSON array or JSONL of complete snapshot objects")
    return records


def timestamp(value):
    # Export contract deliberately excludes ambiguous epoch/naive local timestamps.
    if not isinstance(value, str):
        return None
    try:
        result = pd.Timestamp(value)
        if pd.isna(result) or result.tzinfo is None:
            return None
        return result.tz_convert("UTC")
    except (ValueError, TypeError, OverflowError):
        return None


def measurement(value, timing=False):
    if value is None:
        return None, "missing"
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None, "invalid"
    if abs(value) > 2**53 - 1 or not math.isfinite(value) or value != int(value):
        return None, "invalid"
    if timing and value == -1:
        return None, "unset"
    if value < 0:
        return None, "invalid"
    return int(value), "valid"


def normalize(records):
    """One latest complete snapshot per query; no coalescing or summing updates."""
    canonical = [json.dumps(r, sort_keys=True, allow_nan=True) for r in records]
    unique = list(dict.fromkeys(canonical))
    raw = {"inputRows": len(records), "exactDuplicateRows": len(records) - len(unique)}
    groups, quarantined = {}, set()
    invalid_identity = 0
    for encoded in unique:
        record = json.loads(encoded)
        if record.get("metricType", "COMPLETE") != "COMPLETE":
            raise ValueError("Only COMPLETE cumulative snapshots are supported; merge DISTRIBUTED deltas in the service first")
        qid = record.get("queryId")
        created, updated = timestamp(record.get("createDate")), timestamp(record.get("lastUpdated"))
        logic = record.get("queryLogic")
        if (not isinstance(qid, str) or not qid.strip() or not isinstance(logic, str) or not logic.strip()
                or created is None or updated is None or updated < created):
            invalid_identity += 1
            if isinstance(qid, str) and qid:
                quarantined.add(qid)
            continue
        updates, state = measurement(record.get("numUpdates", 0))
        if state != "valid":
            raise ValueError(f"Invalid numUpdates for {qid}")
        groups.setdefault(qid, []).append((updated, updates, created, logic, record))
    output = []
    superseded = 0
    for qid, versions in groups.items():
        if qid in quarantined:
            continue
        identities = {(x[2], x[3]) for x in versions}
        if len(identities) != 1:
            raise ValueError(f"Conflicting createDate/queryLogic for queryId {qid}; use a single query namespace")
        versions.sort(key=lambda x: (x[0], x[1]))
        if len(versions) > 1 and versions[-1][:2] == versions[-2][:2]:
            raise ValueError(f"Conflicting latest snapshots tied on lastUpdated/numUpdates for {qid}")
        updated, _, created, logic, record = versions[-1]
        superseded += len(versions) - 1
        lifecycle = record.get("lifecycle")
        lifecycle_known = lifecycle in LIFECYCLES if isinstance(lifecycle, str) else False
        error_fields_known = all(k in record and (record[k] is None or isinstance(record[k], str))
                                 for k in ("errorCode", "errorMessage"))
        recorded_error = any(isinstance(record.get(k), str) and bool(record[k].strip())
                             for k in ("errorCode", "errorMessage")) or lifecycle in ("TIMEOUT", "NEXTTIMEOUT")
        error_known = bool(recorded_error or (error_fields_known and lifecycle_known))
        row = {"queryId": qid, "queryLogic": logic, "day": created.strftime("%Y-%m-%d"),
               "createDate": created.isoformat(), "lastUpdated": updated.isoformat(),
               "lifecycle": lifecycle if lifecycle_known else "UNKNOWN", "isFinal": lifecycle in FINAL if lifecycle_known else False,
               "error": int(bool(recorded_error)) if error_known else None,
               "lifetimeMs": (updated - created).total_seconds() * 1000}
        for field in NUMERIC_FIELDS:
            row[field], row[field + "Status"] = measurement(record.get(field), field in TIMINGS)
        pages = record.get("pageTimes")
        first = [p for p in pages if isinstance(p, dict) and not isinstance(p.get("pageNumber"), bool)
                 and p.get("pageNumber") == 1] if isinstance(pages, list) else []
        if len(first) == 1:
            value, status = measurement(first[0].get("returnTime"), True)
        else:
            value, status = None, "ambiguous" if len(first) > 1 else ("invalid" if pages is not None and not isinstance(pages, list) else "missing")
        row["firstPageReturnTimeMs"], row["firstPageReturnTimeMsStatus"] = value, status
        output.append(row)
    if not output:
        raise ValueError("No usable queries after identity/timestamp validation")
    raw.update(invalidIdentityRows=invalid_identity, quarantinedQueryIds=len(quarantined),
               supersededSnapshots=superseded, retainedQueries=len(output))
    raw["excludedRows"] = len(unique) - superseded - len(output)
    return pd.DataFrame(output).sort_values(["day", "queryId"]).reset_index(drop=True), raw


def summarize(frame):
    timing = frame["firstPageReturnTimeMs"].dropna()
    known = frame["error"].notna().sum()
    return {"queries": len(frame), "validTiming": len(timing),
            "medianMs": float(timing.median()) if len(timing) else None,
            "p95Ms": float(timing.quantile(.95, interpolation="linear")) if len(timing) else None,
            "errors": int(frame["error"].sum()), "errorKnown": int(known),
            "errorRate": float(frame["error"].sum() / known) if known else None}


def daily_table(frame):
    start, end = frame.day.min(), frame.day.max()
    rows = []
    for logic in sorted(frame.queryLogic.unique()):
        group = frame[frame.queryLogic == logic]
        by_day = {day: part for day, part in group.groupby("day")}
        for day in pd.date_range(start, end):
            date = day.strftime("%Y-%m-%d")
            rows.append({"day": date, "queryLogic": logic, **summarize(by_day.get(date, group.iloc[:0]))})
    return pd.DataFrame(rows)


def detect_change(daily):
    """Exploratory two-level fit on daily log medians, with no scenario inputs."""
    candidates = []
    days = sorted(daily.day.unique())
    for logic, group in daily.groupby("queryLogic"):
        valid = group[group.validTiming >= 20].sort_values("day")
        if len(valid) < 14:
            continue
        values = np.log1p(valid.medianMs.to_numpy(dtype=float))
        baseline_sse = float(((values - values.mean()) ** 2).sum())
        if baseline_sse < 1e-12:
            continue
        best = None
        for split in days[7:-6]:
            before, after = valid.day.to_numpy() < split, valid.day.to_numpy() >= split
            if before.sum() < 7 or after.sum() < 7:
                continue
            sse = sum(float(((v - v.mean()) ** 2).sum()) for v in (values[before], values[after]))
            improvement = max(0., 1 - sse / baseline_sse)
            ratio = float(np.expm1(np.median(values[after])) / max(np.expm1(np.median(values[before])), 1.))
            candidate = {"queryLogic": logic, "date": split, "fitImprovement": improvement,
                         "dailyMedianRatio": ratio, "beforeDays": int(before.sum()), "afterDays": int(after.sum())}
            if best is None or improvement > best["fitImprovement"]:
                best = candidate
        if best:
            best["flagged"] = best["fitImprovement"] >= .5 and best["dailyMedianRatio"] >= 1.5
            candidates.append(best)
    eligible = [x for x in candidates if x["flagged"]]
    winner = max(eligible, key=lambda x: x["fitImprovement"] * math.log(x["dailyMedianRatio"])) if eligible else None
    return winner, candidates


def comparison_table(frame, change):
    rows = []
    if not change:
        return pd.DataFrame()
    start, end = pd.Timestamp(frame.day.min()), pd.Timestamp(frame.day.max())
    split = pd.Timestamp(change["date"])
    for logic, group in frame.groupby("queryLogic"):
        for period, part, days in (("Before", group[group.day < change["date"]], (split - start).days),
                                   ("After", group[group.day >= change["date"]], (end - split).days + 1)):
            rows.append({"queryLogic": logic, "period": period, "calendarDays": days,
                         "queriesPerDay": len(part) / days if days > 0 else None, **summarize(part)})
    return pd.DataFrame(rows)


def correlation_table(frame, change):
    rows = []
    working = frame.copy()
    working["period"] = np.where(working.day < change["date"], "Before", "After") if change else "All"
    for (logic, period), group in working.groupby(["queryLogic", "period"]):
        for field in ("numResults", "nextCount", "seekCount", "sourceCount", "numPages"):
            pairs = group[["firstPageReturnTimeMs", field]].dropna()
            rho = None
            if len(pairs) >= 3 and pairs.nunique().min() > 1:
                rho = float(pairs.rank(method="average").corr().iloc[0, 1])
            rows.append({"queryLogic": logic, "period": period, "field": field, "pairs": len(pairs), "spearmanRho": rho})
    return pd.DataFrame(rows)


def quality_table(frame):
    rows = []
    for field in (*NUMERIC_FIELDS, "firstPageReturnTimeMs"):
        counts = Counter(frame[field + "Status"])
        rows.append({"field": field, "queries": len(frame), **{s: counts[s] for s in ("valid", "missing", "unset", "invalid", "ambiguous")}})
    return pd.DataFrame(rows)


def analyze(frame, raw):
    daily = daily_table(frame)
    change, candidates = detect_change(daily)
    comparison = comparison_table(frame, change)
    correlations = correlation_table(frame, change)
    findings = []
    if change:
        findings.append(f"The strongest eligible step is {change['date']} in {change['queryLogic']}; "
                        f"the two-level fit reduces daily log-median squared error by {change['fitImprovement']:.1%}. "
                        "This is an exploratory change point, not a causal test or a service defect.")
        for logic in sorted(frame.queryLogic.unique()):
            rows = comparison[comparison.queryLogic == logic].set_index("period")
            before, after = rows.loc["Before"], rows.loc["After"]
            if pd.notna(before.medianMs) and pd.notna(after.medianMs) and before.medianMs > 0:
                findings.append(f"{logic}: median {before.medianMs:,.0f} → {after.medianMs:,.0f} ms "
                                f"({after.medianMs / before.medianMs:.2f}×); p95 {before.p95Ms:,.0f} → {after.p95Ms:,.0f} ms; "
                                f"volume {before.queriesPerDay:,.1f} → {after.queriesPerDay:,.1f} queries/day.")
                if before.errorKnown and after.errorKnown:
                    findings.append(f"{logic}: recorded errors {int(before.errors)}/{int(before.errorKnown)} "
                                    f"({before.errorRate:.2%}) → {int(after.errors)}/{int(after.errorKnown)} ({after.errorRate:.2%}).")
        for row in correlations[correlations.queryLogic == change["queryLogic"]].to_dict("records"):
            if row["field"] in ("numResults", "nextCount") and pd.notna(row["spearmanRho"]):
                findings.append(f"{row['queryLogic']} / {row['period']}: timing vs {row['field']} Spearman "
                                f"ρ={row['spearmanRho']:.2f} across {row['pairs']:,} complete pairs. Association only.")
    else:
        findings.append("No eligible slowdown detected: requires ≥14 observed days, ≥20 timed queries/day, "
                        "≥7 days each side, ≥1.5× daily-median ratio and ≥50% fit improvement.")
    summary = {"quality": raw, "summary": summarize(frame), "change": change, "candidates": candidates,
               "findings": findings, "dateRange": [frame.day.min(), frame.day.max()]}
    return summary, {"daily": daily, "before_after": comparison, "correlations": correlations,
                     "quality": quality_table(frame), "change_candidates": pd.DataFrame(candidates)}
