# Query metric analysis

An isolated Python investigation of **when first-page timing changed, which query
logic was affected, and what an engineer should check next**. No production Java
changes, service credentials, Accumulo, or Java build are needed.

**The bundled records are synthetic.** They do not represent operational activity
or establish a measured DataWave defect. The report, every chart (including image
downloads), and exported tables carry this provenance. The generator's scenario
description is separate from the analysis inputs.

## Run

From the repository root, with Python 3.10+ and pip/venv available:

```sh
./contrib/query-metric-analysis/run.sh --regenerate
```

This installs pinned analysis dependencies in a local `.venv`, recreates the
20,000-query / 30-day dataset, builds all artifacts in `output/`, and runs the
checks. Initial installation needs access to the package index; subsequent
report viewing needs **no network**. Omit `--regenerate` to use the bundled file.
Node.js is optional for running the Python/browser calculation parity test; the
test explicitly reports a skip when Node is unavailable. Python checks still run.

Open `contrib/query-metric-analysis/output/report.html` directly in a browser.
Its data, JavaScript chart library, and styles are embedded. No server, telemetry,
external fonts, tiles, or application deployment. Downloads may be blocked by an
attachment viewer; open the downloaded HTML locally to use CSV and PNG downloads.

For a compatible local export:

```sh
./contrib/query-metric-analysis/run.sh --input /path/to/metrics.jsonl.gz --output /path/to/private-output
```

Add `--synthetic` for a different synthetic export. Other local exports are
prominently labeled **LOCAL EXPORT — unverified provenance**, not silently
presented as production evidence. Raw user/authorization/query text is neither
needed nor embedded, but query identifiers and logic names are: handle the output
according to the source's access rules. Generated artifacts are ignored by Git.
Do not place sensitive exports in the tracked `data/` directory.

## Outputs

| File | Content |
| --- | --- |
| `report.html` | Offline interactive report, inclusive UTC date and logic filters, four charts, summary metrics, tables, quality and findings |
| `01-trends.png` … `04-scatter.png` | Presentation-ready, provenance-marked static charts |
| `analysis.json` | Calculated findings, ingestion audit, detection scores, input hash |
| `daily.csv` | Day × logic volume, valid timing count, median/p95, errors and known-status denominator; zero-volume days included |
| `before_after.csv` | Logic × period totals and per-calendar-day volume at the detected boundary |
| `correlations.csv` | Logic × period × count field complete-pair Spearman correlations |
| `quality.csv` | Per-measurement valid/missing/unset/invalid/ambiguous counts on retained queries |
| `change_candidates.csv` | Best tested boundary per logic, fit improvement, ratio, eligible-day counts and flag |
| `queries.csv` | One normalized row per retained query, with measurement status columns |
| `walkthrough.md` | Approximately three-minute presentation script with calculated findings |
| `manifest.json` | SHA-256 hashes of artifacts; `analysis.json` also records input hash |

Static exports cover the full input. In the report, date/logic filters update
summary metrics, all charts, comparisons, correlations, and retained-query quality
together. Full-input ingestion and detection findings are explicitly labeled
global and do not change: a filtered selection is compared to the same global
boundary. Hover denominators and a filtered daily CSV are available. Scatter
plots use a deterministic stride of at most 1,500 pairs; correlations use **all**
complete pairs, not the plotted subset. Colors are from the accessible
Okabe–Ito palette; line styles and marker shapes also distinguish error series.

## Input contract

JSONL (one object per line) or a JSON array; either may be gzip-compressed. This
is a deliberately small, compatible **export adapter**, not a parser for every
service serialization. Unwrap response envelopes and map XML `pageMetrics` to
the Java/JSON property `pageTimes` before importing. Convert dates to ISO 8601
strings with an explicit timezone; numeric epochs and naive local dates are
not accepted. All durations are integer **milliseconds**, all counters integer
**counts**; JSON null denotes missing. Unknown extra fields are ignored.

```json
{
  "queryId": "example-1",
  "createDate": "2026-09-01T10:00:00Z",
  "lastUpdated": "2026-09-01T10:01:00Z",
  "queryLogic": "EventQuery",
  "numUpdates": 3,
  "lifecycle": "CLOSED",
  "errorCode": null,
  "errorMessage": null,
  "setupTime": 80,
  "createCallTime": 110,
  "loginTime": 2,
  "numResults": 40,
  "numPages": 1,
  "nextCount": 2000,
  "seekCount": 30,
  "sourceCount": 4,
  "yieldCount": 0,
  "docRanges": 4,
  "fiRanges": 1,
  "pageTimes": [{"pageNumber": 1, "pagesize": 40, "returnTime": 250}]
}
```

- Required identity: nonblank string `queryId`, `queryLogic`, valid `createDate`
  and `lastUpdated` (not earlier than creation). IDs must share one globally
  unique namespace. Invalid identity rows are counted and removed; if such a row
  has a queryId, **all** that query's snapshots are quarantined to avoid retaining
  a stale snapshot as current. Conflicting creation dates or logics for an ID
  stop the pipeline. Missing/nonfinal lifecycle is visible in quality reporting.
- Only full **cumulative, already-combined snapshots**, including complete page
  history, are supported. `metricType` is an optional export discriminator;
  omitted means COMPLETE. DISTRIBUTED/CACHE_ONLY is rejected, not treated as
  a snapshot. Merge distributed updates through the service before exporting.
- Exact repeated objects are dropped. Select the latest `lastUpdated`, using
  nonnegative `numUpdates` (default 0) only as a tie-breaker. Conflicting latest
  snapshots tied on both fields stop rather than guessing. Do not add cumulative
  counts, sum pages across snapshots, or backfill latest measurements from stale
  updates. Source snapshots must preserve page 1; truncated histories yield
  missing first-page timing. This is not a reimplementation of the service combiner.
- Primary measure: `pageTimes[pageNumber == 1].returnTime`, derived as
  `firstPageReturnTimeMs`. Exactly one page 1 is required for a valid timing.
  Repeated page 1 entries are ambiguous; absent page 1 is missing. No substitution
  with `callTime`, `setupTime`, later pages, or `elapsedTime`.
- Optional numeric fields are profiled individually. Null/absent is missing;
  −1 in a timing is unset; other negatives, nonnumeric, fractional, nonfinite,
  booleans or integers outside the JavaScript safe range are invalid. Zero is
  valid, but zero counters may indicate absent instrumentation, not no work.
- Recorded error means a nonblank `errorCode` or `errorMessage`, or lifecycle
  TIMEOUT/NEXTTIMEOUT. Otherwise status is known only when both error fields are
  present as null/strings and lifecycle is recognized. Unknown status is excluded
  from the error-rate denominator. Cancellation alone is not classified as an
  error; neither is lifecycle MAXWORK. This is an explicit analytical definition,
  not the service's guarantee of successful execution.

## Semantics verified in source

Reviewed on `integration` at
[`69be54c47e5e322bb00dbd8db5540f3203ab0d38`](https://github.com/COG-GTM/nsa-datawave/tree/69be54c47e5e322bb00dbd8db5540f3203ab0d38).
Links below are pinned to that revision.

| Fields | Meaning and evidence |
| --- | --- |
| `queryId`, `queryLogic`, `queryType` | Query identity, configured logic name, and query Java class name respectively; query type is **not** interchangeable with query logic. [`QueryMetric.populate`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/QueryMetric.java#L116-L129). EventQuery, DiscoveryQuery and ContentQuery are configured bean names in [`QueryLogicFactory.xml`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/starters/query/src/main/resources/QueryLogicFactory.xml#L289-L354). |
| `createDate`, `lastUpdated`, `elapsedTime` | Java Date timestamps; elapsedTime is **lastUpdated − createDate in ms**, or zero if either missing. It is query lifetime, including idle time, not execution latency. [`BaseQueryMetric`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/BaseQueryMetric.java#L736-L798). |
| `beginDate`, `endDate` | Copied from the query's requested data interval, not execution start/end. [`QueryMetric.populate`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/QueryMetric.java#L124-L125). |
| `setupTime` | Milliseconds spent initializing logic, setting it up and obtaining its transform iterator on the web-service path. Not the full query. [`RunningQuery`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/web-services/query/src/main/java/datawave/webservice/query/runner/RunningQuery.java#L203-L215). |
| Page `returnTime`, `pageRequested`, `pageReturned` | `returnTime` is elapsed wall time around a page-return operation in milliseconds; request/return timestamps are epoch milliseconds. Includes work and waiting, not CPU or total query execution time. [`RunningQuery`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/web-services/query/src/main/java/datawave/webservice/query/runner/RunningQuery.java#L460) and [page recording](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/web-services/query/src/main/java/datawave/webservice/query/runner/RunningQuery.java#L597-L598). |
| `createCallTime`, `loginTime`, page `callTime`, `serializationTime` | Request/response and login measurements, in ms; not additive independent pieces of total query latency. Enrichment copies measured response statistics into metric fields. [`BaseMethodStatsFilter`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query/service/src/main/java/datawave/microservice/query/web/filter/BaseMethodStatsFilter.java#L190-L205), [`QueryMetricsEnrichmentFilterAdvice`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query/service/src/main/java/datawave/microservice/query/web/filter/QueryMetricsEnrichmentFilterAdvice.java#L125-L157), [`JWTAuthenticationFilter`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/starters/datawave/src/main/java/datawave/microservice/authorization/jwt/JWTAuthenticationFilter.java#L89-L95). |
| `numResults`, `numPages`, page `pagesize`, `bytesWritten` | Returned result count and page count, not records scanned. `addPageTime` increments results by pagesize and pages by one; `setPageTimes` recalculates totals. bytesWritten is response bytes. [`BaseQueryMetric.addPageTime`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/BaseQueryMetric.java#L886-L898) and [`setPageTimes`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/BaseQueryMetric.java#L1156-L1164). |
| `errorCode`, `errorMessage`, `lifecycle` | Exception indicators populated by `setError`; lifecycle includes timeouts and cancellation, not just successes. Timing defaults are −1; counts default zero. [`BaseQueryMetric`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/BaseQueryMetric.java#L640-L733), [`setError`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/BaseQueryMetric.java#L1005-L1023). |
| `nextCount`, `seekCount`, `sourceCount`, `yieldCount` | Instrumented iterator next/seek calls, tracked sources/deep copies and yields. Not unique documents scanned, bytes read, or CPU time. [`SourceTrackingIterator`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/warehouse/query-core/src/main/java/datawave/query/iterator/profile/SourceTrackingIterator.java#L27-L42), [`QuerySpan`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/warehouse/query-core/src/main/java/datawave/query/iterator/profile/QuerySpan.java). |
| `docRanges`, `fiRanges` | Counters incremented for timing metadata containing DocumentSpecificTree or FieldIndexTree stages; not a verified count of scanned records. [`DocumentTransformerSupport`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/warehouse/query-core/src/main/java/datawave/query/transformer/DocumentTransformerSupport.java#L229-L248). |
| `numUpdates`, COMPLETE vs DISTRIBUTED | Updates are not queries. DISTRIBUTED counters are added; COMPLETE counters replace totals. The service also combines page histories and maximum setup/create/login times. [`QueryMetricType`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/api/src/main/java/datawave/microservice/querymetric/QueryMetricType.java), [`QueryMetricCombiner`](https://github.com/COG-GTM/nsa-datawave/blob/69be54c47e5e322bb00dbd8db5540f3203ab0d38/microservices/services/query-metric/service/src/main/java/datawave/microservice/querymetric/handler/QueryMetricCombiner.java#L169-L205). |

The [Query Metric service README](../../microservices/services/query-metric/README.md)
describes successive updates, combined cache entries and truncated page histories.
Repository metric fixtures test service behavior, but do not provide a suitable
30-day analysis sample. The bundled synthetic data therefore includes ordinary
variation, weekend volume effects, error cases, missing/unset/invalid timings,
earlier snapshots, and exact duplicates.

## Method and verification

1. Validate identities, deduplicate and select complete snapshots; derive page-1 timing.
2. Profile missingness on retained queries and audit raw exclusions/duplicates.
3. Fit one exploratory step per logic to **daily log(1 + median milliseconds)**,
   minimizing within-segment squared error. Each side needs ≥7 eligible days;
   each eligible day needs ≥20 timed queries. A candidate needs ≥50% fit
   improvement and ≥1.5× increase in daily median. Select the strongest eligible
   logic using improvement × log(ratio). If none qualifies, report no eligible
   slowdown rather than forcing a boundary.
4. Compare query-weighted median and linear p95, volume per calendar day, and
   errors/known status before/after that boundary. Inspect each logic separately.
5. Compute pairwise-complete Spearman rank associations within logic and period
   for returned results, next/seek calls, source count, and page count. These are
   descriptive, not causal; totals cover the query, not only page one.

Daily-boundary search is exploratory, not a statistical hypothesis test; there
is no multiple-testing correction or confidence interval. The strongest group
does not prove that other groups are unaffected. Changing query mix, partial
coverage, failed queries lacking timings, or uninstrumented counters can bias
the result. A production investigation needs plans, logs, traces, deployment
history, and like-for-like workloads. No ground-truth file is imported by the
analysis or renderer. Tests read it **after** analysis to evaluate detection;
renaming the logics must preserve the inferred date and affected group.

Run checks independently after installation:

```sh
contrib/query-metric-analysis/.venv/bin/python -m unittest discover -s contrib/query-metric-analysis/tests -v
```

Checks cover known quantiles, zero/missing/unset/invalid measurements, update
deduplication, shuffled order, conflicting ties, stale-value exclusion, pages
versus queries, lifetime separation, error denominators, timestamp quarantine,
flat/short-series non-detection, synthetic step detection, generator byte
reproducibility, aggregate totals, script escaping, and Python/JavaScript filter
and correlation parity. Browser verification additionally exercises actual
controls and hover details in the generated report.

No pre-commit framework is configured in the repository. Its optional `githooks/pre-push`
executes a full Maven clean/format cycle; this isolated Python addition does not
install that Java-wide hook or require a full service build.
