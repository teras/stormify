#!/usr/bin/env python3
"""Generate HTML report from benchmark CSV.

CSV columns: db,impl,scenario,kind,iter,duration_ns,rows,rss_kb

Output: standalone HTML with embedded Chart.js (CDN), with sections for
        cold startup, memory footprint, bulk-insert totals, and per-scenario
        p50/p95/p99 charts. Each scenario carries an explanatory caption.
"""
import csv
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path


# ---------------------------------------------------------------------------
# Captions: detailed English explanation + the actual query for each scenario.
# Stored as (description, query) tuples; rendered by the HTML template.
# ---------------------------------------------------------------------------
DESCRIPTIONS = {
    "findById": (
        "Primary-key lookup of a single BenchChild. Each iteration picks a random id within the seeded range "
        "and resolves it to one row, mapped to a typed entity. One bound parameter, one round-trip, "
        "one-row result-set hydration.",
        "SELECT * FROM bench_child WHERE id = ?"
    ),
    "list_window_1000": (
        "Loads a 1000-row window of BenchChild entities into a List. The window starts at a different "
        "offset on every iteration (iteration index multiplied by a prime stride, modulo the data range), "
        "so consecutive iterations select disjoint rows. One round-trip; 1000 rows hydrated to typed entities.",
        "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?"
    ),
    "join_child_parent": (
        "JOIN of bench_child to bench_parent, 500 rows per iteration, projected onto a typed DTO "
        "(cid, value, name). Stormify uses read&lt;ChildParentDto&gt;; JPA uses a JPQL constructor expression "
        "(SELECT NEW pkg.ChildParentDto(...)). One round-trip; 500 DTO instances populated from three columns.",
        "SELECT c.id AS cid, c.value, p.name\nFROM bench_child c JOIN bench_parent p ON c.parent_id = p.id\nWHERE c.id BETWEEN ? AND ?"
    ),
    "n_plus_1_50": (
        "Loads 50 BenchChild rows (a typical web-app page) and resolves their referenced parents — "
        "exactly as a developer would write the code idiomatically in each ORM. Same logical operation, "
        "different SQL strategy because of how each ORM models the relationship:\n\n"
        "• Stormify carries the FK as a raw int (parentId), so the developer writes the batch fetch "
        "  explicitly — one SELECT for children, then one SELECT with an IN-list for distinct parent "
        "  IDs. 2 round-trips, regardless of page size.\n"
        "• JPA carries @ManyToOne parent as a lazy proxy, so iterating and reading c.parent.name silently "
        "  triggers one SELECT per row — the textbook N+1 anti-pattern. 51 round-trips. The persistence "
        "  context is cleared at the start of every iteration so each iteration pays the full N+1 cost.",
        "-- Stormify (2 round-trips):\nSELECT * FROM bench_child  WHERE id BETWEEN ? AND ?\nSELECT * FROM bench_parent WHERE id IN (?, ?, ?, ...)\n\n-- JPA (51 round-trips):\nSELECT * FROM bench_child  WHERE id BETWEEN ? AND ?\nSELECT * FROM bench_parent WHERE id = ?    -- ×50"
    ),
    "n_plus_1_1000": (
        "Same architectural test as n_plus_1_50 but stressed at 1000 children per iteration. The "
        "Stormify side stays at 2 round-trips (one children SELECT + one IN-list parents SELECT, with "
        "1000 placeholders). The JPA side scales linearly to 1001 round-trips. This is where the "
        "anti-pattern's cost becomes catastrophic — exactly the case real applications hit when "
        "a small page-size N+1 ships to production and a list grows.",
        "-- Stormify (2 round-trips):\nSELECT * FROM bench_child  WHERE id BETWEEN ? AND ?\nSELECT * FROM bench_parent WHERE id IN (?, ?, ?, ...)\n\n-- JPA (1001 round-trips):\nSELECT * FROM bench_child  WHERE id BETWEEN ? AND ?\nSELECT * FROM bench_parent WHERE id = ?    -- ×1000"
    ),
    "paged_scan": (
        "Streams 5000 BenchChild rows through a server-side cursor with fetchSize=50 — the result is consumed "
        "row-by-row instead of being materialized into memory all at once. The id window slides per iteration. "
        "Stormify uses readCursor with cursorFetchSize=50; JPA uses Query.getResultStream with the "
        "org.hibernate.fetchSize hint and detach() after each row.",
        "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?"
    ),
    "complex_filter": (
        "Query combining an equality predicate (status), a range predicate on a numeric column (value), and "
        "a range on the primary key (id). The status value cycles through the five seeded values on each "
        "iteration and the id window slides, so each iteration probes a different slice of the data.",
        "SELECT * FROM bench_child\nWHERE status = ? AND value BETWEEN ? AND ?\n  AND id BETWEEN ? AND ?"
    ),
    "update_bulk_sql": (
        "A single UPDATE statement modifying ~100 rows per iteration, expressed as raw SQL with no entity "
        "loading. One round-trip; affected-row count returned. JPA translates the JPQL form to SQL before "
        "execution; Stormify forwards the string as-is.",
        "UPDATE bench_child SET status = ?\nWHERE id BETWEEN ? AND ?"
    ),
    "update_bulk_orm": (
        "ORM-level batch update path: load 100 BenchChild entities, mutate a field on each, flush the "
        "modifications. The flush goes through the JDBC batch protocol (executeBatch on JDBC, the "
        "equivalent on KDBC), which exposes the driver-level batch-size and batch-rewrite settings.",
        "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?\n\nUPDATE bench_child SET status = ? WHERE id = ?"
    ),
    "tx_rollback": (
        "One UPDATE inside an explicit transaction that throws to trigger a ROLLBACK. Measures "
        "BEGIN + statement + ROLLBACK mechanics. After a clean rollback the connection pool keeps "
        "the entry (no eviction) so consecutive iterations do not pay TCP + auth handshakes.",
        "BEGIN;\n  UPDATE bench_child SET status = 'X' WHERE id = ?;\nROLLBACK;"
    ),
    # ----- Insert totals (single-shot wall-clock per phase) -----
    "insert_1000": (
        "Inserts 1000 BenchChild rows in one transaction. Stormify calls create(collection); JPA calls "
        "persist + flush every 50 rows. bench_child is cleared before this phase.",
        "INSERT INTO bench_child (id, parent_id, status, value, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)"
    ),
    "insert_10000": (
        "Inserts 10 000 BenchChild rows in one transaction, batched in chunks of 5000. Both ORMs feed "
        "the JDBC/KDBC batch protocol; PostgreSQL's pgjdbc driver may rewrite the batch into a multi-row "
        "VALUES statement when reWriteBatchedInserts is enabled. bench_child is cleared before this phase "
        "and left populated for the read scenarios.",
        "INSERT INTO bench_child (id, parent_id, status, value, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)"
    ),
    "single_insert_1000": (
        "1000 separate transactions, each performing exactly one INSERT and one COMMIT. No batching. "
        "Stormify-native uses a single persistent connection; JPA's bench equivalent constructs a fresh "
        "EntityManager per iteration.",
        "BEGIN;\n  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at) VALUES (?, ?, ?, ?, ?, ?);\nCOMMIT;"
    ),
}

# Scenario sections in display order
READ_SCENARIOS_ORDER = [
    "findById", "list_window_1000", "join_child_parent",
    "n_plus_1_50", "n_plus_1_1000",
    "paged_scan", "complex_filter",
    "update_bulk_sql", "update_bulk_orm",
    "tx_rollback",
]
INSERT_SCENARIOS_ORDER = [
    "insert_1000", "insert_10000",
    "single_insert_1000",
]


def percentile(values, p):
    if not values:
        return 0
    s = sorted(values)
    k = (len(s) - 1) * p
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def main(csv_path, html_path):
    rows = []
    with open(csv_path) as fh:
        for r in csv.DictReader(fh):
            try:
                r["duration_ns"] = int(r["duration_ns"])
                r["iter"] = int(r["iter"])
                r["rows"] = int(r["rows"])
                r["rss_kb"] = int(r["rss_kb"])
            except ValueError:
                continue
            if r.get("impl") == "jpa-jvm":
                r["impl"] = "jpa"
            elif r.get("impl") == "stormify-native":
                r["impl"] = "stormify"
            rows.append(r)

    # ------- Group raw rows -------
    iter_groups = defaultdict(list)             # (db, impl, scenario) -> [ns]
    total_singles = {}                          # (db, impl, scenario) -> ns
    startup_ns = {}                             # (db, impl) -> ns (canonical startup)
    rss_map = defaultdict(int)                  # (db, impl) -> peak rss_kb

    for r in rows:
        key = (r["db"], r["impl"], r["scenario"])
        if r["kind"] == "iter":
            iter_groups[key].append(r["duration_ns"])
        elif r["kind"] == "total" and not r["scenario"].startswith("startup_"):
            total_singles[key] = r["duration_ns"]
        elif r["kind"] == "ready_ns" and r["scenario"].startswith("startup_"):
            # Canonical startup = bench-read mode (full Stormify/EMF init).
            # If only bench-insert is present (no read), fall back to it.
            mode = r["scenario"].replace("startup_", "")
            cur = startup_ns.get((r["db"], r["impl"]))
            if cur is None or mode == "bench-read":
                startup_ns[(r["db"], r["impl"])] = r["duration_ns"]
        if r["rss_kb"] > 0:
            rss_map[(r["db"], r["impl"])] = max(rss_map[(r["db"], r["impl"])], r["rss_kb"])

    # ------- Compute stats -------
    stats = {}
    for key, samples in iter_groups.items():
        if not samples:
            continue
        stats[key] = {
            "n": len(samples),
            "min": min(samples),
            "p50": percentile(samples, 0.50),
            "p95": percentile(samples, 0.95),
            "p99": percentile(samples, 0.99),
            "max": max(samples),
            "mean": statistics.mean(samples),
            "stddev": statistics.stdev(samples) if len(samples) > 1 else 0.0,
        }
    # Single-shot totals (bulk inserts) get expanded to a "stat" so they slot in
    for key, ns in total_singles.items():
        if key not in stats:
            stats[key] = {"n": 1, "min": ns, "p50": ns, "p95": ns, "p99": ns,
                          "max": ns, "mean": ns, "stddev": 0.0}

    DB_ORDER = ["sqlite", "mysql", "postgresql", "oracle", "mssql"]
    seen_dbs = {k[0] for k in stats.keys()} | {k[0] for k in startup_ns.keys()}
    dbs = [db for db in DB_ORDER if db in seen_dbs] + sorted(seen_dbs - set(DB_ORDER))
    impls = sorted({k[1] for k in stats.keys()} | {k[1] for k in startup_ns.keys()})

    # ------- Chart data per scenario -------
    def build_chart_for(scenarios):
        chart_data = {}
        for scen in scenarios:
            datasets_p50 = {impl: [] for impl in impls}
            datasets_p95 = {impl: [] for impl in impls}
            datasets_p99 = {impl: [] for impl in impls}
            for db in dbs:
                for impl in impls:
                    s = stats.get((db, impl, scen))
                    datasets_p50[impl].append(s["p50"] / 1000.0 if s else None)
                    datasets_p95[impl].append(s["p95"] / 1000.0 if s else None)
                    datasets_p99[impl].append(s["p99"] / 1000.0 if s else None)
            chart_data[scen] = {
                "labels": dbs,
                "p50": datasets_p50,
                "p95": datasets_p95,
                "p99": datasets_p99,
            }
        return chart_data

    read_present = [s for s in READ_SCENARIOS_ORDER
                    if any((db, impl, s) in stats for db in dbs for impl in impls)]
    insert_present = [s for s in INSERT_SCENARIOS_ORDER
                      if any((db, impl, s) in stats for db in dbs for impl in impls)]
    read_charts = build_chart_for(read_present)
    insert_charts = build_chart_for(insert_present)

    # ------- Startup + memory chart data -------
    startup_chart = {
        "labels": dbs,
        "datasets": {impl: [(startup_ns.get((db, impl), 0) / 1e6) for db in dbs] for impl in impls},
    }
    rss_chart = {
        "labels": dbs,
        "datasets": {impl: [(rss_map.get((db, impl), 0) / 1024.0) for db in dbs] for impl in impls},
    }

    # ------- Full stats table rows -------
    table_rows = []
    for scen in read_present + insert_present:
        for db in dbs:
            for impl in impls:
                s = stats.get((db, impl, scen))
                if not s:
                    continue
                table_rows.append({
                    "scenario": scen, "db": db, "impl": impl, "n": s["n"],
                    "p50_us": round(s["p50"] / 1000.0, 2),
                    "p95_us": round(s["p95"] / 1000.0, 2),
                    "p99_us": round(s["p99"] / 1000.0, 2),
                    "min_us": round(s["min"] / 1000.0, 2),
                    "max_us": round(s["max"] / 1000.0, 2),
                    "mean_us": round(s["mean"] / 1000.0, 2),
                })

    descriptions_payload = {k: {"desc": v[0], "sql": v[1]} for k, v in DESCRIPTIONS.items()}
    payload = {
        "startup_ms": startup_chart,
        "rss_mb": rss_chart,
        "read_charts": read_charts,
        "insert_charts": insert_charts,
        "read_scenarios": read_present,
        "insert_scenarios": insert_present,
        "descriptions": descriptions_payload,
        "dbs": dbs,
        "impls": impls,
        "table_rows": table_rows,
    }

    html = HTML_TEMPLATE.replace("__PAYLOAD__", json.dumps(payload))
    Path(html_path).write_text(html)
    print(f"Wrote {html_path}")


HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="el">
<head>
<meta charset="UTF-8">
<title>Stormify Benchmark Report</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
<style>
  body { font-family: -apple-system, sans-serif; margin: 0; padding: 0 24px 24px 24px; max-width: 1400px; color: #222; }
  .sticky-header { position: sticky; top: 0; z-index: 100; background: #fff; border-bottom: 1px solid #ddd;
                   padding: 10px 0; margin: 0 -24px 16px -24px; padding-left: 24px; padding-right: 24px;
                   display: flex; align-items: center; gap: 16px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }
  .sticky-header strong { font-size: 14px; }
  .sticky-header label { font-size: 13px; cursor: pointer; user-select: none; }
  .sticky-header input[type=checkbox] { vertical-align: middle; margin-right: 4px; }
  h1 { border-bottom: 2px solid #333; padding-bottom: 8px; }
  h2 { margin-top: 32px; color: #444; }
  .scenario { margin: 18px 0; padding: 14px; border: 1px solid #ddd; border-radius: 8px; background: #fafafa; }
  .scenario h3 { margin: 0 0 4px 0; font-size: 16px; }
  .caption { color: #444; font-size: 13px; margin: 0 0 8px 0; line-height: 1.45; }
  .scenario-body p { margin: 0 0 8px 0; }
  .scenario-body pre { background: #f0efea; color: #333; padding: 10px; border-radius: 4px;
                       border-left: 3px solid #c8c5b8; font-family: ui-monospace, Menlo, Consolas, monospace;
                       font-size: 12px; overflow-x: auto; margin: 0; }
  .chart-container { position: relative; height: 280px; margin-top: 6px; }
  .toggles { margin: 4px 0; }
  .toggles button { margin-right: 6px; padding: 3px 10px; cursor: pointer; font-size: 12px; }
  .toggles button.active { background: #2c7be5; color: white; border-color: #2c7be5; }
  table { border-collapse: collapse; width: 100%; font-size: 13px; }
  th, td { border: 1px solid #ddd; padding: 4px 8px; text-align: right; }
  th { background: #f5f5f5; text-align: center; }
  td:first-child, td:nth-child(2), td:nth-child(3) { text-align: left; }
  .small { font-size: 12px; color: #666; }
  details { margin: 8px 0; }
  summary { cursor: pointer; font-weight: bold; padding: 6px 0; }
  .legend { font-size: 12px; color: #666; margin: 4px 0; }
  .pitch { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 12px; }
  .pitch .scenario { margin: 0; }
</style>
</head>
<body>

<div class="sticky-header">
  <strong>Stormify Benchmark Report</strong>
  <label><input type="checkbox" id="log-toggle" checked> Logarithmic Y-axis</label>
</div>

<h1>Stormify Benchmark Report</h1>
<p class="small">All numbers are in <strong>microseconds (μs)</strong> unless otherwise stated. Lower = faster. Warmup time is excluded from measurements (a dedicated <code>bench_warmup</code> table is used to warm JIT and the connection pool before any benchmark scenario runs, so the real <code>bench_*</code> tables stay cold going into measurement).</p>

<h2>Cold startup</h2>
<div class="scenario">
  <p class="caption">Wall-clock time from <code>process exec</code> until the ORM is ready to issue queries (DB connection acquired, framework fully initialized). In <strong>milliseconds</strong>.</p>
  <div class="chart-container"><canvas id="startup-chart"></canvas></div>
</div>

<h2>Peak memory (RSS)</h2>
<div class="scenario">
  <p class="caption">Peak resident set size, sampled from <code>/proc/self/status VmRSS</code> over the lifetime of the benchmark run. In <strong>MB</strong>.</p>
  <div class="chart-container"><canvas id="rss-chart"></canvas></div>
</div>

<h2>Insert scenarios (single-shot totals)</h2>
<p class="legend">Each bar is the wall-clock time for one full insert phase. In <strong>milliseconds</strong>.</p>
<div id="insert-charts"></div>

<h2>Read scenarios (per-iteration p50/p95/p99)</h2>
<p class="legend">500 measured iterations per scenario (fewer for heavier ones — see the <em>N</em> column in the full table). Use the buttons under each chart to toggle between p50, p95, and p99.</p>
<div id="read-charts"></div>

<h2>Full statistics</h2>
<details>
<summary>All numbers (p50 / p95 / p99 / min / max / mean, in μs)</summary>
<div id="full-table"></div>
</details>

<script>
const PAYLOAD = __PAYLOAD__;

const COLORS = {
  "stormify":    "#2c7be5",
  "jpa":         "#e63946",
  "jpa-native":  "#06a77d",
};
function colorFor(impl) { return COLORS[impl] || "#888"; }

const REGISTERED_CHARTS = []; // {chart, unit}
let LOG_SCALE = true;
// Treat differences below this ratio as a tie (within statistical noise).
const TIE_THRESHOLD = 1.10;

// Build per-label ratio annotations (stormify vs jpa-jvm, fallback
// jpa-native). Lower values = faster. Returns array of {text, color} aligned
// with labels.
function buildRatioLabels(labels, dataByImpl) {
  const nat = dataByImpl["stormify"] || [];
  const jpaPrimary = dataByImpl["jpa"] || [];
  const jpaFallback = dataByImpl["jpa-native"] || [];
  return labels.map((_, i) => {
    const n = nat[i];
    const jPrim = jpaPrimary[i];
    const jFall = jpaFallback[i];
    const hasN = n != null && n !== 0;
    const hasJPrim = jPrim != null && jPrim !== 0;
    const hasJFall = jFall != null && jFall !== 0;
    let j, jLabel;
    if (hasJPrim) { j = jPrim; jLabel = "jpa"; }
    else if (hasJFall) { j = jFall; jLabel = "jpa-native"; }
    if (!hasN || j == null) {
      const missing = [];
      if (!hasN) missing.push("stormify");
      if (!hasJPrim && !hasJFall) missing.push("jpa");
      return {
        text: `missing: ${missing.join(", ")}`,
        fg: "#7a5b00", bg: "#fff8d6", border: "#e0b800",
      };
    }
    const ratio = n < j ? j / n : n / j;
    if (ratio < TIE_THRESHOLD) {
      return { text: `≈ ${ratio.toFixed(2)}×`, fg: "#555", bg: "#f3f3f3", border: "#bbb" };
    }
    if (n < j) {
      return { text: `${ratio.toFixed(2)}× stormify`, fg: "#045d44", bg: "#e6f4ee", border: "#06a77d" };
    }
    return { text: `${ratio.toFixed(2)}× ${jLabel}`, fg: "#8a1c25", bg: "#fdecec", border: "#e63946" };
  });
}

// Chart.js plugin: draws ratio labels above each x-category, near the top
// of the chart area.
const ratiosLabelsPlugin = {
  id: 'ratiosLabels',
  afterDatasetsDraw(chart, _args, opts) {
    const labels = opts && opts.labels;
    if (!labels || !labels.length) return;
    const xScale = chart.scales.x;
    if (!xScale) return;
    const ctx = chart.ctx;
    ctx.save();
    ctx.font = 'bold 11px -apple-system, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const padX = 8, padY = 3, radius = 9;
    const cy = chart.chartArea.top + 11;
    labels.forEach((entry, i) => {
      if (!entry) return;
      const cx = xScale.getPixelForValue(i);
      const w = ctx.measureText(entry.text).width + padX * 2;
      const h = 11 + padY * 2;
      const x = cx - w / 2, y = cy - h / 2;
      const r = Math.min(radius, h / 2);
      ctx.beginPath();
      ctx.moveTo(x + r, y);
      ctx.lineTo(x + w - r, y);
      ctx.quadraticCurveTo(x + w, y, x + w, y + r);
      ctx.lineTo(x + w, y + h - r);
      ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
      ctx.lineTo(x + r, y + h);
      ctx.quadraticCurveTo(x, y + h, x, y + h - r);
      ctx.lineTo(x, y + r);
      ctx.quadraticCurveTo(x, y, x + r, y);
      ctx.closePath();
      ctx.fillStyle = entry.bg;
      ctx.fill();
      ctx.lineWidth = 1;
      ctx.strokeStyle = entry.border;
      ctx.stroke();
      ctx.fillStyle = entry.fg;
      ctx.fillText(entry.text, cx, cy);
    });
    ctx.restore();
  }
};
Chart.register(ratiosLabelsPlugin);

function yAxisFor(unit) {
  return {
    type: LOG_SCALE ? 'logarithmic' : 'linear',
    title: { display: true, text: unit + (LOG_SCALE ? ' (log)' : '') },
    beginAtZero: !LOG_SCALE,
  };
}

function applyLogScaleToAll() {
  REGISTERED_CHARTS.forEach(({chart, unit}) => {
    chart.options.scales.y = yAxisFor(unit);
    chart.update();
  });
}

function makeMetricChart(canvasId, payload, unit) {
  const ctx = document.getElementById(canvasId);
  const datasets = PAYLOAD.impls.map(impl => ({
    label: impl,
    data: payload.datasets[impl] || [],
    backgroundColor: colorFor(impl),
  }));
  const ratioLabels = buildRatioLabels(payload.labels, payload.datasets);
  const chart = new Chart(ctx, {
    type: 'bar',
    data: { labels: payload.labels, datasets },
    options: {
      responsive: true, maintainAspectRatio: false,
      layout: { padding: { top: 4 } },
      scales: {
        y: yAxisFor(unit),
        x: { title: { display: true, text: 'database' } },
      },
      plugins: {
        legend: { position: 'bottom' },
        ratiosLabels: { labels: ratioLabels },
      },
    }
  });
  REGISTERED_CHARTS.push({chart, unit});
  return chart;
}

function makePerfChart(canvas, scen, percentile, charts) {
  const data = charts[scen];
  const datasets = PAYLOAD.impls.map(impl => ({
    label: impl,
    data: data[percentile][impl] || [],
    backgroundColor: colorFor(impl),
  }));
  const ratioLabels = buildRatioLabels(data.labels, data[percentile]);
  const chart = new Chart(canvas, {
    type: 'bar',
    data: { labels: data.labels, datasets },
    options: {
      responsive: true, maintainAspectRatio: false,
      layout: { padding: { top: 4 } },
      scales: {
        y: yAxisFor('μs'),
        x: { title: { display: true, text: 'database' } },
      },
      plugins: {
        title: { display: true, text: `${scen} — ${percentile}` },
        legend: { position: 'bottom' },
        ratiosLabels: { labels: ratioLabels },
      },
    }
  });
  REGISTERED_CHARTS.push({chart, unit: 'μs'});
  return chart;
}

function renderScenarioGroup(rootId, scenarios, charts, isInsert) {
  const root = document.getElementById(rootId);
  scenarios.forEach(scen => {
    const wrap = document.createElement("div");
    wrap.className = "scenario";
    const info = PAYLOAD.descriptions[scen] || { desc: "", sql: "" };
    wrap.innerHTML = `
      <h3>${scen}</h3>
      <div class="scenario-body">
        <p class="caption">${info.desc}</p>
        <pre>${info.sql.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>
      </div>
      ${isInsert ? '' : `
      <div class="toggles">
        <button data-p="p50" class="active">p50</button>
        <button data-p="p95">p95</button>
        <button data-p="p99">p99</button>
      </div>`}
      <div class="chart-container"><canvas></canvas></div>
    `;
    root.appendChild(wrap);
    const canvas = wrap.querySelector("canvas");
    if (isInsert) {
      // Insert single-shot total: render as ms bars (p50 = total since n=1).
      const data = charts[scen];
      const dataByImplMs = {};
      PAYLOAD.impls.forEach(impl => {
        dataByImplMs[impl] = (data.p50[impl] || []).map(v => v == null ? null : v / 1000.0);
      });
      const ratioLabels = buildRatioLabels(data.labels, dataByImplMs);
      const datasets = PAYLOAD.impls.map(impl => ({
        label: impl,
        data: dataByImplMs[impl],
        backgroundColor: colorFor(impl),
      }));
      const insChart = new Chart(canvas, {
        type: 'bar',
        data: { labels: data.labels, datasets },
        options: {
          responsive: true, maintainAspectRatio: false,
          layout: { padding: { top: 4 } },
          scales: {
            y: yAxisFor('ms'),
            x: { title: { display: true, text: 'database' } },
          },
          plugins: {
            title: { display: true, text: scen + ' — total ms' },
            legend: { position: 'bottom' },
            ratiosLabels: { labels: ratioLabels },
          },
        }
      });
      REGISTERED_CHARTS.push({chart: insChart, unit: 'ms'});
    } else {
      let chart = makePerfChart(canvas, scen, "p50", charts);
      wrap.querySelectorAll("button").forEach(btn => {
        btn.onclick = () => {
          wrap.querySelectorAll("button").forEach(b => b.classList.remove("active"));
          btn.classList.add("active");
          const idx = REGISTERED_CHARTS.findIndex(e => e.chart === chart);
          if (idx >= 0) REGISTERED_CHARTS.splice(idx, 1);
          chart.destroy();
          chart = makePerfChart(canvas, scen, btn.dataset.p, charts);
        };
      });
    }
  });
}

function renderFullTable() {
  const div = document.getElementById("full-table");
  let html = "<table><thead><tr><th>Scenario</th><th>DB</th><th>Impl</th><th>N</th><th>p50 μs</th><th>p95 μs</th><th>p99 μs</th><th>min</th><th>max</th><th>mean</th></tr></thead><tbody>";
  PAYLOAD.table_rows.forEach(r => {
    html += `<tr><td>${r.scenario}</td><td>${r.db}</td><td>${r.impl}</td><td>${r.n}</td><td>${r.p50_us}</td><td>${r.p95_us}</td><td>${r.p99_us}</td><td>${r.min_us}</td><td>${r.max_us}</td><td>${r.mean_us}</td></tr>`;
  });
  html += "</tbody></table>";
  div.innerHTML = html;
}

makeMetricChart("startup-chart", PAYLOAD.startup_ms, "ms");
makeMetricChart("rss-chart", PAYLOAD.rss_mb, "MB");
renderScenarioGroup("insert-charts", PAYLOAD.insert_scenarios, PAYLOAD.insert_charts, true);
renderScenarioGroup("read-charts", PAYLOAD.read_scenarios, PAYLOAD.read_charts, false);
renderFullTable();

document.getElementById('log-toggle').addEventListener('change', (e) => {
  LOG_SCALE = e.target.checked;
  applyLogScaleToAll();
});
</script>
</body>
</html>
"""


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: generate-report.py <results.csv> <out.html>")
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
