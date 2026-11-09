/* Pure aggregation helpers are also exercised in Node against Python outputs. */
(function (root) {
  "use strict";
  function quantile(values, p) {
    const a = values.filter(Number.isFinite).sort((x, y) => x - y);
    if (!a.length) return null;
    const h = (a.length - 1) * p, lo = Math.floor(h), hi = Math.ceil(h);
    return a[lo] + (a[hi] - a[lo]) * (h - lo);
  }
  function summary(rows) {
    const times = rows.map(r => r.firstPageReturnTimeMs).filter(Number.isFinite);
    const known = rows.filter(r => Number.isFinite(r.error));
    const errors = known.reduce((n, r) => n + r.error, 0);
    return {queries: rows.length, validTiming: times.length, medianMs: quantile(times, .5),
      p95Ms: quantile(times, .95), errors, errorKnown: known.length, errorRate: known.length ? errors / known.length : null};
  }
  function select(rows, start, end, logic) {
    return rows.filter(r => r.day >= start && r.day <= end && (!logic || r.queryLogic === logic));
  }
  function dateRange(start, end) {
    const first = Date.parse(start), last = Date.parse(end);
    if (!Number.isFinite(first) || !Number.isFinite(last) || first > last) return null;
    return [new Date(first - 43200000).toISOString(), new Date(last + 43200000).toISOString()];
  }
  function ranks(values) {
    const sorted = values.map((v, i) => [v, i]).sort((a, b) => a[0] - b[0]);
    const result = Array(values.length);
    for (let i = 0; i < sorted.length;) {
      let j = i + 1;
      while (j < sorted.length && sorted[j][0] === sorted[i][0]) j++;
      for (let k = i; k < j; k++) result[sorted[k][1]] = (i + j - 1) / 2;
      i = j;
    }
    return result;
  }
  function correlation(rows, field) {
    const pairs = rows.filter(r => Number.isFinite(r.firstPageReturnTimeMs) && Number.isFinite(r[field]));
    if (pairs.length < 3) return {pairs: pairs.length, rho: null};
    const x = ranks(pairs.map(r => r[field])), y = ranks(pairs.map(r => r.firstPageReturnTimeMs));
    const mean = (pairs.length - 1) / 2;
    let xy = 0, xx = 0, yy = 0;
    for (let i = 0; i < x.length; i++) {xy += (x[i] - mean) * (y[i] - mean); xx += (x[i] - mean) ** 2; yy += (y[i] - mean) ** 2;}
    return {pairs: pairs.length, rho: xx && yy ? xy / Math.sqrt(xx * yy) : null};
  }
  root.MetricAnalysis = {quantile, summary, select, correlation, dateRange};
  if (typeof module !== "undefined") module.exports = root.MetricAnalysis;
})(typeof window === "undefined" ? globalThis : window);

if (typeof document !== "undefined") {
  "use strict";
  const payload = JSON.parse(document.getElementById("payload").textContent);
  const rows = payload.data.data.map(values => Object.fromEntries(payload.data.columns.map((c, i) => [c, values[i]])));
  const {summary, select, quantile, correlation, dateRange} = window.MetricAnalysis;
  const $ = id => document.getElementById(id);
  const fmt = (v, digits = 0) => Number.isFinite(v) ? v.toLocaleString("en-US", {maximumFractionDigits: digits}) : "—";
  const pct = v => Number.isFinite(v) ? `${(v * 100).toFixed(2)}%` : "—";
  const esc = v => String(v).replace(/[&<>"']/g, c => ({"&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;"}[c]));
  const palette = ["#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00"];
  const logics = [...new Set(rows.map(r => r.queryLogic))].sort();
  const color = logic => palette[logics.indexOf(logic) % palette.length];
  const change = payload.analysis.change;
  const watermark = payload.label;
  const startDay = payload.analysis.dateRange[0], endDay = payload.analysis.dateRange[1];
  const fields = payload.qualityFields;
  function table(id, headers, body) {
    $(id).innerHTML = `<thead><tr>${headers.map(v => `<th scope="col">${esc(v)}</th>`).join("")}</tr></thead><tbody>` +
      body.map(row => `<tr>${row.map(v => `<td>${esc(v)}</td>`).join("")}</tr>`).join("") + "</tbody>";
  }
  function daysBetween(start, end) {
    const dates = [];
    for (let ms = Date.parse(start); ms <= Date.parse(end); ms += 86400000) dates.push(new Date(ms).toISOString().slice(0, 10));
    return dates;
  }
  function layout(title, extra = {}) {
    return {title: {text: `${esc(title)}<br><sup>${esc(watermark)}</sup>`, x: .02, font: {size: 15}},
      font: {family: "Arial, sans-serif", color: "#22384b", size: 12}, paper_bgcolor: "#fff", plot_bgcolor: "#fff",
      margin: {t: 75, l: 70, r: 35, b: 65}, hovermode: "closest",
      legend: {orientation: "h", y: -0.22}, ...extra};
  }
  const config = {responsive: true, displaylogo: false, modeBarButtonsToRemove: ["sendDataToCloud", "lasso2d", "select2d"],
    toImageButtonOptions: {format: "png", filename: payload.synthetic ? "SYNTHETIC-query-metrics" : "LOCAL-query-metrics", scale: 2}};
  function changeMark() {
    if (!change || change.date < $("start").value || change.date > $("end").value) return {};
    return {shapes: [{type: "line", x0: change.date, x1: change.date, y0: 0, y1: 1, yref: "paper", line: {color: "#9b3a18", dash: "dot", width: 2}}],
      annotations: [{x: 1, xref: "paper", xanchor: "right", y: 1.02, yref: "paper", yanchor: "bottom",
        text: `Suspected change · ${change.date}`, showarrow: false, font: {color: "#9b3a18"}}]};
  }
  function render() {
    const start = $("start").value, end = $("end").value, logic = $("logic").value;
    const selected = start && end && start <= end ? select(rows, start, end, logic) : [];
    const stats = summary(selected), dates = start && end && start <= end ? daysBetween(start, end) : [];
    const dateAxis = {type: "date", title: "Query creation date (UTC)", range: dateRange(start, end) || dateRange(startDay, endDay),
      autorange: false, nticks: 5, tickformat: "%b %d", tickangle: 0};
    const visible = logic ? [logic] : logics;
    $("scope").textContent = `${selected.length.toLocaleString()} queries · ${start || "no start"} to ${end || "no end"} UTC · ${logic || "all query logic"}`;
    $("empty").hidden = !!selected.length;
    $("volume").textContent = fmt(stats.queries);
    $("volume-sub").textContent = `${fmt(dates.length ? stats.queries / dates.length : null, 1)} queries/calendar day`;
    $("median").textContent = `${fmt(stats.medianMs)} ms`;
    $("p95").textContent = `${fmt(stats.p95Ms)} ms`;
    $("timed-sub").textContent = `${fmt(stats.validTiming)} / ${fmt(stats.queries)} with valid first-page timing`;
    $("error-rate").textContent = pct(stats.errorRate);
    $("error-sub").textContent = `${fmt(stats.errors)} / ${fmt(stats.errorKnown)} known; ${fmt(stats.queries - stats.errorKnown)} unknown`;
    const byDay = new Map(dates.map(day => [day, []]));
    selected.forEach(r => byDay.get(r.day)?.push(r));
    const daily = dates.map(day => ({day, ...summary(byDay.get(day))}));
    const trend = [
      {type: "bar", name: "Queries", x: dates, y: daily.map(r => r.queries), marker: {color: "#b8c9d9"}, yaxis: "y2", hovertemplate: "%{x}<br>%{y:,} queries<extra></extra>"},
      ...[["medianMs", "Median", "#0072B2", "solid"], ["p95Ms", "95th percentile", "#D55E00", "dash"]].map(([key, name, c, dash]) =>
        ({type: "scatter", mode: "lines+markers", name, x: dates, y: daily.map(r => r[key]), line: {color: c, dash},
          customdata: daily.map(r => [r.validTiming, r.queries]), hovertemplate: "%{x}<br>%{y:,.1f} ms<br>Timed: %{customdata[0]} / %{customdata[1]} queries<extra>%{fullData.name}</extra>"}))];
    const promises = [Plotly.react("trend", trend, layout("Query volume and first-page timing", {
      xaxis: {...dateAxis}, yaxis: {title: "First-page return time (ms)", domain: [.35, 1], rangemode: "tozero"},
      yaxis2: {title: "Queries", domain: [0, .19], rangemode: "tozero"}, ...changeMark()}), config)];
    const comparisons = [], boxes = [];
    for (const name of visible) {
      const subset = selected.filter(r => r.queryLogic === name);
      for (const period of change ? ["Before", "After"] : ["All"]) {
        const part = subset.filter(r => !change || (period === "Before" ? r.day < change.date : r.day >= change.date));
        const stat = summary(part);
        const calendarDays = dates.filter(d => !change || (period === "Before" ? d < change.date : d >= change.date)).length;
        comparisons.push([name, period, fmt(stat.queries), fmt(calendarDays ? stat.queries / calendarDays : null, 1),
          `${fmt(stat.validTiming)} / ${fmt(stat.queries)}`, fmt(stat.medianMs, 1), fmt(stat.p95Ms, 1), `${stat.errors} / ${stat.errorKnown}`, pct(stat.errorRate)]);
        const times = part.map(r => r.firstPageReturnTimeMs).filter(Number.isFinite);
        if (times.length) boxes.push({type: "box", orientation: "h", name: `${esc(name)} · ${period}`, y: [`${esc(name)} · ${period}`], q1: [quantile(times, .25)], median: [stat.medianMs], q3: [quantile(times, .75)],
          lowerfence: [quantile(times, .05)], upperfence: [stat.p95Ms], boxpoints: false,
          marker: {color: color(name)}, fillcolor: period === "Before" ? "rgba(255,255,255,0.7)" : color(name),
          line: {width: 2}, customdata: [times.length], hovertemplate: `${esc(name)} · ${period}<br>Timed queries: ${times.length}<br>Median: ${fmt(stat.medianMs, 1)} ms<br>p95: ${fmt(stat.p95Ms, 1)} ms<extra></extra>`});
      }
    }
    table("comparison", ["Query logic", "Period", "Queries", "Queries/day", "Timed / all", "Median (ms)", "p95 (ms)", "Errors / known", "Error rate"], comparisons);
    promises.push(Plotly.react("distribution", boxes, layout("First-page timing distributions", {showlegend: false,
      xaxis: {title: "First-page return time (ms)", rangemode: "tozero", nticks: 5},
      yaxis: {type: "category", autorange: "reversed", tickvals: boxes.map(b => b.name), ticktext: boxes.map(b => b.name.replace(" · ", "<br>")), tickfont: {size: 11}},
      margin: {t: 75, l: 110, r: 20, b: 75}}), config));
    const errors = visible.map((name, i) => {
      const group = new Map(dates.map(d => [d, []]));
      selected.filter(r => r.queryLogic === name).forEach(r => group.get(r.day)?.push(r));
      const data = dates.map(d => summary(group.get(d)));
      return {type: "scatter", mode: "lines+markers", name: esc(name), x: dates, y: data.map(r => r.errorRate === null ? null : r.errorRate * 100),
        line: {color: color(name), dash: ["solid", "dash", "dot"][i % 3]}, marker: {symbol: ["circle", "diamond", "square"][i % 3]},
        customdata: data.map(r => [r.errors, r.errorKnown, r.queries]),
        hovertemplate: "%{x}<br>%{y:.2f}%<br>Errors: %{customdata[0]} / %{customdata[1]} known<br>Queries: %{customdata[2]}<extra>%{fullData.name}</extra>"};
    });
    promises.push(Plotly.react("errors", errors, layout("Recorded errors by query logic", {xaxis: {...dateAxis}, yaxis: {title: "Recorded error rate (%)", rangemode: "tozero"}, ...changeMark()}), config));
    const xfield = $("scatter-field").value;
    const eligible = selected.filter(r => Number.isFinite(r[xfield]) && Number.isFinite(r.firstPageReturnTimeMs));
    const stride = Math.max(1, Math.ceil(eligible.length / 1500));
    const sample = eligible.filter((_, i) => i % stride === 0);
    $("scatter-note").textContent = `${fmt(sample.length)} of ${fmt(eligible.length)} complete pairs plotted (deterministic stride). Correlations below use all complete pairs, separately by logic and period.`;
    const scatter = visible.map(name => {
      const part = sample.filter(r => r.queryLogic === name);
      return {type: "scatter", mode: "markers", name: esc(name), x: part.map(r => r[xfield]), y: part.map(r => r.firstPageReturnTimeMs),
        marker: {size: 6, opacity: .5, color: color(name)}, customdata: part.map(r => [esc(r.queryId), r.day, change ? (r.day < change.date ? "Before" : "After") : "All"]),
        hovertemplate: "%{customdata[0]} · %{customdata[1]} · %{customdata[2]}<br>Count: %{x:,}<br>First page: %{y:,.0f} ms<extra>%{fullData.name}</extra>"};
    });
    const axisLabel = xfield === "numResults" ? "Returned results (query cumulative count)" : "Iterator next() calls (query cumulative count)";
    promises.push(Plotly.react("scatter", scatter, layout("Timing and query-level work", {xaxis: {title: axisLabel, rangemode: "tozero"}, yaxis: {title: "First-page return time (ms)", rangemode: "tozero"}}), config));
    const corrRows = [];
    for (const name of visible) for (const period of change ? ["Before", "After"] : ["All"]) {
      const part = selected.filter(r => r.queryLogic === name && (!change || (period === "Before" ? r.day < change.date : r.day >= change.date)));
      const c = correlation(part, xfield);
      corrRows.push([name, period, fmt(c.pairs), fmt(c.rho, 3)]);
    }
    table("correlations", ["Query logic", "Period", "Complete pairs", "Spearman ρ"], corrRows);
    const quality = fields.map(field => {
      const counts = {valid: 0, missing: 0, unset: 0, invalid: 0, ambiguous: 0};
      selected.forEach(r => counts[r[field + "Status"]]++);
      return [field, ...Object.values(counts).map(v => fmt(v))];
    });
    table("quality", ["Measurement", "Valid", "Missing", "Unset (−1)", "Invalid", "Ambiguous page 1"], quality);
    $("quality-scope").textContent = `${fmt(selected.length)} selected queries; ${fmt(selected.filter(r => !r.isFinal).length)} not in a final lifecycle; ${fmt(stats.queries - stats.errorKnown)} unknown error status. Missing or invalid measurements stay out of timing/correlation denominators.`;
    const before = summary(selected.filter(r => change && r.day < change.date)), after = summary(selected.filter(r => change && r.day >= change.date));
    $("selection-finding").textContent = change && before.validTiming && after.validTiming ?
      `Within this selection, median first-page return time is ${fmt(before.medianMs)} → ${fmt(after.medianMs)} ms; p95 is ${fmt(before.p95Ms)} → ${fmt(after.p95Ms)} ms. Compare like-for-like logic below; a changing workload mix can alter the overall median.` :
      "This selection does not have timed queries on both sides of the fixed change point. No before/after conclusion is available.";
    window.reportState = {start, end, logic, summary: stats, daily, selectedQueries: selected.length, rendered: false};
    Promise.all(promises).then(() => { window.reportState.rendered = true; });
  }
  $("start").value = startDay; $("end").value = endDay;
  for (const id of ["start", "end"]) {$(id).min = startDay; $(id).max = endDay;}
  logics.forEach(logic => {const option = document.createElement("option"); option.value = logic; option.textContent = logic; $("logic").appendChild(option);});
  for (const id of ["start", "end", "logic", "scatter-field"]) $(id).addEventListener("change", render);
  $("reset").addEventListener("click", () => {$("start").value = startDay; $("end").value = endDay; $("logic").value = ""; $("scatter-field").value = "numResults"; render();});
  $("export").addEventListener("click", () => {
    const state = window.reportState;
    const csv = ["provenance,day,queries,validTiming,medianMs,p95Ms,errors,errorKnown,errorRate",
      ...state.daily.map(r => [watermark, r.day, r.queries, r.validTiming, r.medianMs, r.p95Ms, r.errors, r.errorKnown, r.errorRate].join(","))].join("\n");
    const url = URL.createObjectURL(new Blob([csv], {type: "text/csv"}));
    const a = document.createElement("a"); a.href = url; a.download = `${payload.synthetic ? "SYNTHETIC" : "LOCAL"}-filtered-daily.csv`; a.click(); URL.revokeObjectURL(url);
  });
  render();
}
