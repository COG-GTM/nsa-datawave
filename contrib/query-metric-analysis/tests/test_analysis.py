import json
import random
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from analysis import CANDIDATE_COLUMNS, COMPARISON_COLUMNS, analyze, detect_change, measurement, normalize, read_records, summarize
from generate import generate
from render import html_report


def record(qid="q1", timing=100, **overrides):
    result = dict(queryId=qid, queryLogic="EventQuery", createDate="2026-09-01T10:00:00Z",
                  lastUpdated="2026-09-01T11:00:00Z", numUpdates=2, lifecycle="CLOSED",
                  errorCode=None, errorMessage=None, numResults=10, nextCount=100,
                  pageTimes=[dict(pageNumber=1, pagesize=10, returnTime=timing)])
    result.update(overrides)
    return result


class AggregationTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node required for browser date ticks")
    def test_browser_date_ticks_are_distinct_and_include_endpoints(self):
        code = """
const a = require(process.argv[1]);
const dates = Array.from({length:30}, (_, i) => `2026-09-${String(i + 1).padStart(2, '0')}`);
console.log(JSON.stringify([a.dateTicks([]), a.dateTicks(['2026-09-18']), a.dateTicks(dates)]));
"""
        proc = subprocess.run(["node", "-e", code, str(ROOT / "report.js")], text=True, capture_output=True, check=True)
        self.assertEqual(json.loads(proc.stdout), [[], ["2026-09-18"],
            ["2026-09-01", "2026-09-08", "2026-09-16", "2026-09-23", "2026-09-30"]])

    @unittest.skipUnless(shutil.which("node"), "Node required for browser date bounds")
    def test_browser_date_bounds_cover_selected_days(self):
        code = """
const a = require(process.argv[1]);
console.log(JSON.stringify([
  a.dateRange('2026-09-01', '2026-09-30'),
  a.dateRange('2026-09-18', '2026-09-18'),
  a.dateRange('2026-09-30', '2026-09-01'),
  a.dateRange('', '2026-09-30')
]));
"""
        proc = subprocess.run(["node", "-e", code, str(ROOT / "report.js")], text=True, capture_output=True, check=True)
        self.assertEqual(json.loads(proc.stdout), [
            ["2026-08-31T12:00:00.000Z", "2026-09-30T12:00:00.000Z"],
            ["2026-09-17T12:00:00.000Z", "2026-09-18T12:00:00.000Z"], None, None])

    def test_percentiles_and_denominators(self):
        frame, _ = normalize([record(str(i), value) for i, value in enumerate([0, 10, 20, 30, 40, None, -1, -5])])
        result = summarize(frame)
        self.assertEqual(result["queries"], 8)
        self.assertEqual(result["validTiming"], 5)
        self.assertEqual(result["medianMs"], 20)
        self.assertEqual(result["p95Ms"], 38)

    def test_latest_complete_snapshot_and_exact_duplicates(self):
        final = record()
        early = record(timing=10, lastUpdated="2026-09-01T10:10:00Z", numResults=1, nextCount=5)
        frame, audit = normalize([final, early, final])
        self.assertEqual(len(frame), 1)
        self.assertEqual(frame.iloc[0].numResults, 10)
        self.assertEqual(frame.iloc[0].nextCount, 100)
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMs, 100)
        self.assertEqual(audit["exactDuplicateRows"], 1)
        self.assertEqual(audit["supersededSnapshots"], 1)
        other, _ = normalize([early, final])
        pd.testing.assert_frame_equal(frame, other)

    def test_no_filling_latest_from_stale_update(self):
        frame, _ = normalize([record(timing=12, lastUpdated="2026-09-01T10:10:00Z"), record(timing=None)])
        self.assertTrue(pd.isna(frame.iloc[0].firstPageReturnTimeMs))

    def test_pages_are_not_queries_or_lifetime(self):
        frame, _ = normalize([record(pageTimes=[dict(pageNumber=2, returnTime=999), dict(pageNumber=1, returnTime=25)])])
        self.assertEqual(summarize(frame)["queries"], 1)
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMs, 25)
        self.assertEqual(frame.iloc[0].lifetimeMs, 3600000)

    def test_ambiguous_or_absent_first_page(self):
        frame, _ = normalize([record(pageTimes=[dict(pageNumber=1, returnTime=1), dict(pageNumber=1, returnTime=2)])])
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMsStatus, "ambiguous")
        frame, _ = normalize([record(pageTimes=[dict(pageNumber=2, returnTime=999)])])
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMsStatus, "missing")
        frame, _ = normalize([record(pageTimes="invalid")])
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMsStatus, "invalid")
        frame, _ = normalize([record(pageTimes=[dict(pageNumber=True, returnTime=999)])])
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMsStatus, "missing")

    def test_unset_invalid_zero_and_missing(self):
        for value, state in [(None, "missing"), (-1, "unset"), (-2, "invalid"), (0, "valid"),
                             (True, "invalid"), ("100", "invalid"), (1.5, "invalid"), (float("inf"), "invalid"), (2**54, "invalid")]:
            self.assertEqual(measurement(value, True)[1], state)
        self.assertEqual(measurement(-1)[1], "invalid")

    def test_error_definitions_and_unknowns(self):
        unknown = record("unknown"); del unknown["errorCode"]
        frame, _ = normalize([record("ok"), record("code", errorCode="500-1"), record("timeout", lifecycle="NEXTTIMEOUT"),
                              record("cancelled", lifecycle="CANCELLED"), unknown])
        stats = summarize(frame)
        self.assertEqual((stats["errors"], stats["errorKnown"], stats["errorRate"]), (2, 4, .5))

    def test_nonfinite_and_huge_measurements_are_profiled_not_fatal(self):
        values = [float("nan"), float("inf"), float("-inf"), 10**400]
        rows = [record(str(i), timing=value, nextCount=value) for i, value in enumerate(values)]
        frame, audit = normalize(rows + [record("good", timing=50)])
        self.assertEqual(audit["retainedQueries"], 5)
        self.assertEqual(frame.firstPageReturnTimeMsStatus.tolist().count("invalid"), 4)
        self.assertEqual(frame.nextCountStatus.tolist().count("invalid"), 4)
        result, tables = analyze(frame, audit)
        self.assertEqual(result["summary"]["validTiming"], 1)
        self.assertEqual(result["summary"]["medianMs"], 50)
        json.dumps(result, allow_nan=False)

    def test_reject_distributed_updates_and_conflicting_ties(self):
        with self.assertRaisesRegex(ValueError, "COMPLETE"):
            normalize([record(metricType="DISTRIBUTED")])
        with self.assertRaisesRegex(ValueError, "tied"):
            normalize([record(), record(timing=99)])
        with self.assertRaisesRegex(ValueError, "Conflicting createDate/queryLogic"):
            normalize([record(), record(queryLogic="ContentQuery")])

    def test_invalid_timestamp_quarantines_query(self):
        frame, audit = normalize([record(), record(lastUpdated="2026-09-02"), record("good")])
        self.assertEqual(frame.queryId.tolist(), ["good"])
        self.assertEqual(audit["quarantinedQueryIds"], 1)
        self.assertEqual(audit["excludedRows"], 2)

    def test_update_counter_breaks_timestamp_tie(self):
        frame, _ = normalize([record(numUpdates=1, timing=10), record(numUpdates=2, timing=20)])
        self.assertEqual(frame.iloc[0].firstPageReturnTimeMs, 20)

    def test_empty_timing_is_not_zero(self):
        frame, _ = normalize([record(timing=None)])
        self.assertIsNone(summarize(frame)["medianMs"])
        self.assertIsNone(summarize(frame.iloc[:0])["errorRate"])

    def test_flat_series_and_insufficient_data_do_not_trigger(self):
        rows = [dict(day=d.strftime("%Y-%m-%d"), queryLogic="A", validTiming=50, medianMs=100) for d in pd.date_range("2026-09-01", periods=30)]
        self.assertIsNone(detect_change(pd.DataFrame(rows))[0])
        self.assertIsNone(detect_change(pd.DataFrame(rows[:10]))[0])
        for i, row in enumerate(rows):
            row["medianMs"] = 100 + (i % 3 - 1) * 3
        self.assertIsNone(detect_change(pd.DataFrame(rows))[0])


class ScenarioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = read_records(ROOT / "data/metrics.jsonl.gz")
        cls.frame, cls.audit = normalize(cls.records)
        cls.analysis, cls.tables = analyze(cls.frame, cls.audit)

    def test_detector_finds_planted_step_without_labels(self):
        # Ground truth is opened only after analysis; production analysis never reads it.
        truth = json.loads((ROOT / "data/scenario_truth.json").read_text())
        self.assertEqual(self.analysis["change"]["date"], truth["changeDate"])
        self.assertEqual(self.analysis["change"]["queryLogic"], truth["queryLogic"])
        self.assertEqual(self.analysis["summary"]["queries"], truth["queries"])
        self.assertEqual(len(self.frame.day.unique()), truth["days"])
        self.assertEqual([r["queryLogic"] for r in self.analysis["candidates"] if r["flagged"]], [truth["queryLogic"]])

    def test_logic_names_are_not_detector_inputs(self):
        renamed = self.frame.copy()
        renamed["queryLogic"] = renamed.queryLogic.map({"EventQuery": "C", "DiscoveryQuery": "B", "ContentQuery": "A"})
        result, _ = analyze(renamed, self.audit)
        self.assertEqual(result["change"]["queryLogic"], "C")
        self.assertEqual(result["change"]["date"], self.analysis["change"]["date"])

    def test_aggregate_totals_and_complete_pair_correlations(self):
        self.assertEqual(self.tables["daily"].queries.sum(), 20000)
        self.assertEqual(self.tables["before_after"].queries.sum(), 20000)
        self.assertEqual(self.audit["supersededSnapshots"], 4000)
        self.assertEqual(self.audit["exactDuplicateRows"], 200)
        self.assertTrue(self.tables["correlations"].spearmanRho.dropna().between(-1, 1).all())

    def test_generation_is_byte_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            regenerated = generate(tmp)
            self.assertEqual(regenerated.read_bytes(), (ROOT / "data/metrics.jsonl.gz").read_bytes())

    @unittest.skipUnless(shutil.which("node"), "Node required for Python/browser aggregation parity")
    def test_python_javascript_filter_quantile_correlation_parity(self):
        data = json.loads(self.frame.to_json(orient="records"))
        code = """
const fs = require('fs'), a = require(process.argv[1]);
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
console.log(JSON.stringify(input.filters.map(f => {const rows = a.select(input.rows, ...f); return {summary:a.summary(rows), correlation:a.correlation(rows, 'nextCount')};})));
"""
        filters = [["2026-09-01", "2026-09-30", ""], ["2026-09-18", "2026-09-30", "EventQuery"],
                   ["2026-09-01", "2026-09-01", "ContentQuery"], ["2026-09-30", "2026-09-01", ""]]
        rng = random.Random(7)
        for _ in range(20):
            a, b = sorted(rng.sample(range(1, 31), 2))
            filters.append([f"2026-09-{a:02d}", f"2026-09-{b:02d}", rng.choice(["", "EventQuery", "ContentQuery", "DiscoveryQuery"])])
        proc = subprocess.run(["node", "-e", code, str(ROOT / "report.js")], input=json.dumps({"rows": data, "filters": filters}),
                              text=True, capture_output=True, check=True)
        for f, actual in zip(filters, json.loads(proc.stdout)):
            subset = self.frame[(self.frame.day >= f[0]) & (self.frame.day <= f[1])]
            if f[2]: subset = subset[subset.queryLogic == f[2]]
            for key, expected in summarize(subset).items():
                if expected is None: self.assertIsNone(actual["summary"][key])
                else: self.assertAlmostEqual(actual["summary"][key], expected, places=7)
            pairs = subset[["nextCount", "firstPageReturnTimeMs"]].dropna()
            self.assertEqual(actual["correlation"]["pairs"], len(pairs))
            if len(pairs) >= 3 and pairs.nunique().min() > 1:
                self.assertAlmostEqual(actual["correlation"]["rho"], pairs.rank().corr().iloc[0, 1], places=7)

    def test_report_embeds_no_remote_assets_and_escapes_input(self):
        frame = self.frame.head(2).copy()
        unsafe_logic = "</script><script>alert(1)</script>@@PLOTLY@@"
        frame["queryLogic"] = unsafe_logic
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.html"
            html_report(frame, self.analysis, path, "SYNTHETIC DATA", True, "test-hash")
            content = path.read_text()
            self.assertNotIn('<script src=', content)
            self.assertNotIn('<link rel="stylesheet"', content)
            self.assertNotIn('</script><script>alert(1)</script>', content)
            self.assertNotIn('@@PAYLOAD@@', content)
            payload = json.loads(re.search(r'<script id="payload" type="application/json">(.*?)</script>', content, re.S)[1])
            self.assertEqual(payload["data"]["data"][0][payload["data"]["columns"].index("queryLogic")], unsafe_logic)

    def test_user_export_and_no_change_pipeline(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "local.json"
            source.write_text(json.dumps([record(), record("q2", timing=-1)]))
            output = Path(tmp) / "out"
            subprocess.run([sys.executable, str(ROOT / "pipeline.py"), "--input", str(source), "--output", str(output)],
                           check=True, capture_output=True, text=True)
            result = json.loads((output / "analysis.json").read_text())
            self.assertFalse(result["synthetic"])
            self.assertIsNone(result["change"])
            self.assertEqual(result["summary"]["validTiming"], 1)
            self.assertIn("LOCAL EXPORT", (output / "report.html").read_text())
            self.assertEqual(len(list(output.glob("*.png"))), 4)
            comparison = pd.read_csv(output / "before_after.csv")
            candidates = pd.read_csv(output / "change_candidates.csv")
            self.assertEqual(list(comparison.columns), ["provenance", *COMPARISON_COLUMNS])
            self.assertEqual(list(candidates.columns), ["provenance", *CANDIDATE_COLUMNS])
            self.assertTrue(comparison.empty)
            self.assertTrue(candidates.empty)


if __name__ == "__main__":
    unittest.main()
