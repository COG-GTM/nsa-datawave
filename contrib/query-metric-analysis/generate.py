"""Deterministic fictional COMPLETE snapshots; scenario metadata is kept out of records."""

import gzip
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np


def generate(directory, seed=20261001, count=20000):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    start = datetime(2026, 9, 1, tzinfo=timezone.utc)
    change_day = 17
    logics = ["EventQuery", "DiscoveryQuery", "ContentQuery"]
    weights = np.array([.8 if (start + timedelta(days=d)).weekday() >= 5 else 1.1 for d in range(30)])
    counts = rng.multinomial(count, weights / weights.sum())
    records = []
    serial = 0
    for day, queries in enumerate(counts):
        daily_factor = rng.lognormal(0, .045)
        for _ in range(queries):
            serial += 1
            index = int(rng.choice(3, p=[.5, .28, .22]))
            logic = logics[index]
            affected = logic == "EventQuery" and day >= change_day
            created = start + timedelta(days=day, seconds=int(rng.integers(0, 86400)))
            results = max(0, int(rng.lognormal([4.7, 3.4, 2.6][index], .85)))
            next_count = int((results * rng.uniform(12, 30) + rng.lognormal(7, .6)) * (2.7 if affected else 1))
            first_time = int(max(0, daily_factor * rng.lognormal(0, .48) *
                                 ([180, 100, 65][index] + .028 * next_count + .12 * results) * (1.9 if affected else 1)))
            error = rng.random() < (.065 if affected else .012)
            num_pages = max(1, int(np.ceil(results / 100)))
            sizes = [min(100, max(0, results - p * 100)) for p in range(num_pages)]
            pages, requested = [], created + timedelta(milliseconds=180)
            for number, size in enumerate(sizes, 1):
                duration = first_time if number == 1 else int(first_time * rng.uniform(.3, .8))
                returned = requested + timedelta(milliseconds=duration)
                pages.append({"pageNumber": number, "pagesize": size, "returnTime": duration,
                              "pageRequested": int(requested.timestamp() * 1000), "pageReturned": int(returned.timestamp() * 1000),
                              "callTime": duration + 12, "serializationTime": 4, "loginTime": 2, "bytesWritten": size * 160})
                requested = returned + timedelta(seconds=int(rng.integers(1, 40)))
            # Incomplete/invalid timing is not imputed from lifetime, call time, or other pages.
            missing = rng.random()
            if missing < .025:
                pages[0]["returnTime"] = None
            elif missing < .045:
                pages[0]["returnTime"] = -1
            elif missing < .05:
                pages[0]["returnTime"] = -8
            if error and rng.random() < .5:
                pages = []
                results, num_pages = 0, 0
            updated = requested + timedelta(seconds=int(rng.integers(10, 600)))
            row = {"queryId": f"synthetic-{serial:06d}", "queryLogic": logic,
                   "createDate": created.isoformat(), "lastUpdated": updated.isoformat(), "numUpdates": 3,
                   "lifecycle": "NEXTTIMEOUT" if error else "CLOSED",
                   "errorCode": "500-1" if error else None,
                   "errorMessage": "Synthetic failure; no production event" if error else None,
                   "setupTime": int(rng.lognormal(4.4, .4)), "createCallTime": 180, "loginTime": 2,
                   "numResults": results, "numPages": num_pages, "pageTimes": pages,
                   "nextCount": next_count, "seekCount": int(next_count * rng.uniform(.01, .06)),
                   "sourceCount": int(rng.integers(1, 15)), "yieldCount": int(rng.poisson(1)),
                   "docRanges": int(rng.integers(1, 30)), "fiRanges": int(rng.integers(1, 15))}
            if rng.random() < .03:
                row["nextCount"] = None
            if rng.random() < .015:
                row["numResults"] = None
            if rng.random() < .012:
                row["setupTime"] = -1
            records.append(row)
            if serial % 5 == 0:
                early = dict(row, lastUpdated=(created + timedelta(milliseconds=100)).isoformat(), numUpdates=1,
                             lifecycle="INITIALIZED", errorCode=None, errorMessage=None, pageTimes=[],
                             numResults=0, numPages=0, nextCount=0, seekCount=0)
                records.append(early)
            if serial % 100 == 0:
                records.append(dict(row))
    rng.shuffle(records)
    data = "".join(json.dumps(r, separators=(",", ":"), sort_keys=True) + "\n" for r in records).encode()
    path = directory / "metrics.jsonl.gz"
    path.write_bytes(gzip.compress(data, mtime=0))
    truth = {"synthetic": True, "seed": seed, "queries": count, "days": 30,
             "changeDate": (start + timedelta(days=change_day)).strftime("%Y-%m-%d"), "queryLogic": "EventQuery",
             "note": "Generator/evaluation only. Never an input to analysis or report construction."}
    (directory / "scenario_truth.json").write_text(json.dumps(truth, indent=2) + "\n")
    return path


if __name__ == "__main__":
    generate(Path(__file__).parent / "data")
