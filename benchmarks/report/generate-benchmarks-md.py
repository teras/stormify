#!/usr/bin/env python3
"""Populate the data-table placeholders in docs/src/Benchmarks.md from results.csv.

The page itself (intro, methodology, explanations of known gaps,
caveats) is plain markdown. This script only rewrites the content between
two markers:

    <!-- READ_RESULTS -->...<!-- /READ_RESULTS -->
    <!-- INSERT_RESULTS -->...<!-- /INSERT_RESULTS -->

Everything outside those markers is preserved verbatim. After a new bench
run we look at the regenerated tables, decide whether any of the prose
needs updating (e.g. an explanation becomes stale or a new gap appears
that deserves its own paragraph), and edit the markdown by hand.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path


# ---------------------------------------------------------------------------
# Display order of databases (left to right in the tables).
# ---------------------------------------------------------------------------
DB_ORDER = ["sqlite", "mysql", "postgresql", "oracle", "mssql"]
DB_LABEL = {
    "sqlite": "SQLite",
    "mysql": "MySQL",
    "postgresql": "PostgreSQL",
    "oracle": "Oracle",
    "mssql": "SQL Server",
}

# ---------------------------------------------------------------------------
# Scenario groups + display order.
# ---------------------------------------------------------------------------
READ_SCENARIOS = [
    ("findById",            "PK lookup, one-row entity hydration",
        "SELECT * FROM bench_child WHERE id = ?"),
    ("list_window_1000",    "1000-row windowed SELECT into a typed list",
        "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?"),
    ("join_child_parent",   "500-row JOIN projected to a typed DTO",
        "SELECT c.id AS cid, c.value, p.name FROM bench_child c\n"
        "  JOIN bench_parent p ON c.parent_id = p.id\n"
        "  WHERE c.id BETWEEN ? AND ?"),
    ("n_plus_1_50",         "50-row child page + bulk-fetch parents (the JPA-friendly N+1 size)",
        "-- Stormify (2 round-trips):\n"
        "SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 50 rows\n"
        "SELECT * FROM bench_parent WHERE id IN (?, ?, …)\n"
        "-- JPA via lazy @ManyToOne (51 round-trips):\n"
        "SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 50 rows\n"
        "SELECT * FROM bench_parent WHERE id = ?                 -- ×50"),
    ("n_plus_1_1000",       "1000-row child N+1 — extreme case; JPA fires 1001 round-trips",
        "-- Stormify (2 round-trips):\n"
        "SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 1000 rows\n"
        "SELECT * FROM bench_parent WHERE id IN (?, ?, …)\n"
        "-- JPA via lazy @ManyToOne (1001 round-trips):\n"
        "SELECT * FROM bench_child  WHERE id BETWEEN ? AND ?     -- 1000 rows\n"
        "SELECT * FROM bench_parent WHERE id = ?                 -- ×1000"),
    ("paged_scan",          "5000-row server-side cursor stream (fetchSize = 50)",
        "SELECT * FROM bench_child WHERE id BETWEEN ? AND ?"),
    ("complex_filter",      "Three-predicate filter on bench_child",
        "SELECT * FROM bench_child\n"
        "  WHERE status = ? AND value BETWEEN ? AND ?\n"
        "    AND id BETWEEN ? AND ?"),
    ("update_bulk_sql",     "Single UPDATE touching ~100 rows, raw SQL",
        "BEGIN;\n"
        "  UPDATE bench_child SET status = ? WHERE id BETWEEN ? AND ?;\n"
        "COMMIT;"),
    ("update_bulk_orm",     "ORM batch update — load 100 children, mutate, flush",
        "BEGIN;\n"
        "  SELECT * FROM bench_child WHERE id BETWEEN ? AND ?;\n"
        "  -- driver-batched per-row UPDATE (mutate, flush)\n"
        "  UPDATE bench_child SET status = ? WHERE id = ?;   -- ×100\n"
        "COMMIT;"),
    ("tx_rollback",         "BEGIN + UPDATE + ROLLBACK round-trip mechanics",
        "BEGIN;\n"
        "  UPDATE bench_child SET status = ? WHERE id = ?;\n"
        "ROLLBACK;"),
]

INSERT_SCENARIOS = [
    ("insert_1000",         "1000 children inserted in one transaction",
        "BEGIN;\n"
        "  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)\n"
        "    VALUES (?, ?, ?, ?, ?, ?);   -- ×1000, driver-batched\n"
        "COMMIT;"),
    ("insert_10000",        "10 000 children inserted in one transaction, chunked at 5000",
        "BEGIN;\n"
        "  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)\n"
        "    VALUES (?, ?, ?, ?, ?, ?);   -- ×10 000, batched\n"
        "COMMIT;"),
    ("single_insert_1000",  "1000 separate 1-row transactions (per-iter p50)",
        "BEGIN;\n"
        "  INSERT INTO bench_child (id, parent_id, status, value, payload, created_at)\n"
        "    VALUES (?, ?, ?, ?, ?, ?);\n"
        "COMMIT;"),
]


def parse_csv(path):
    """Returns dict[(db, scenario, kind, impl)] = list[duration_ns]."""
    out = defaultdict(list)
    with open(path) as f:
        for r in csv.reader(f):
            if len(r) < 6:
                continue
            db, impl, scenario, kind, _iter, dur, *_ = r
            try:
                ns = int(dur)
            except ValueError:
                continue
            out[(db, scenario, kind, impl)].append(ns)
    return out


def parse_csv_rss(path):
    """Returns dict[(db, scenario, kind, impl)] = list[rss_kb]. Separate
    helper because rss_kb sits in a different column and is only
    populated on the `total`/`ready_ns` summary rows."""
    out = defaultdict(list)
    with open(path) as f:
        for r in csv.reader(f):
            if len(r) < 8:
                continue
            db, impl, scenario, kind, _iter, _dur, _rows, rss = r
            try:
                kb = int(rss)
            except ValueError:
                continue
            if kb <= 0:
                continue
            out[(db, scenario, kind, impl)].append(kb)
    return out


def median(values):
    if not values:
        return None
    s = sorted(values)
    return s[len(s) // 2]


def percentile(values, p):
    """Linear-interpolation percentile (matches Chart.js / numpy default).
    p in [0, 1]. Returns None on empty input. Mirrors what generate-report.py
    feeds into the HTML report so the two presentations stay in sync."""
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    frac = k - lo
    return s[lo] + (s[hi] - s[lo]) * frac


def fmt_us(ns):
    if ns is None:
        return "—"
    us = ns / 1000
    if us >= 10000:
        return f"{us/1000:.1f}ms"
    return f"{us:.0f}μs"


def fmt_ratio(native, jpa):
    """Speedup of native over JPA: jpa / native. Higher is better.
    A 5.0x value means Stormify-native finished in one fifth of the JPA
    time. Below 1.0x means native is slower."""
    if native is None or jpa is None or native == 0:
        return "—"
    return f"{jpa/native:.2f}x"


def fmt_ms(ns):
    """Startup numbers run from <1ms (native) to multi-second (JVM). Render
    in ms with adaptive precision so a SQLite native of ~600μs and a JPA
    cold start of ~2.5s both read clearly in the same column."""
    if ns is None:
        return "—"
    ms = ns / 1_000_000
    if ms < 1:
        return f"{ns/1000:.0f}μs"
    if ms < 100:
        return f"{ms:.1f}ms"
    return f"{ms:.0f}ms"


def fmt_mb(kb):
    """RSS values: native processes are ~7-30MB, JVM ~300-500MB."""
    if kb is None:
        return "—"
    mb = kb / 1024
    if mb < 100:
        return f"{mb:.1f}MB"
    return f"{mb:.0f}MB"


def ratio_marker(native, jpa):
    """Returns (marker_glyph, css_color) for the speedup cell.

    Speedup = jpa / native. ✓ when native is ≥10% faster (speedup ≥
    1.111×), ~ when within ±10% (effective parity), ✗ when native is
    measurably slower (speedup ≤ 0.91×). The 10% band absorbs run-to-run
    noise so we don't flag a noisy tie as a loss. Parity stays uncoloured
    so the eye only catches real wins (green) and real regressions
    (red)."""
    if native is None or jpa is None or native == 0:
        return ("", "")
    s = jpa / native  # speedup
    if s >= 1.111:    # native at least 10% faster
        return ("✓", "#2e7d32")  # green-700
    if s > 0.909:     # within ±10%
        return ("~", "")          # parity — no colour
    return ("✗", "#c62828")      # red-700


def ratio_cell(native, jpa):
    """Markdown cell content combining ratio + (optionally coloured) marker."""
    rs = fmt_ratio(native, jpa)
    glyph, color = ratio_marker(native, jpa)
    if not glyph:
        return rs
    if not color:
        return f"{rs} {glyph}"
    return f'<span style="color:{color}">{rs} {glyph}</span>'


def render_scenario_table_for(stats, scenario, kind, statistic):
    """One markdown table for a given statistic (`p50` / `p95` / `p99`).
    Columns = DBs, rows = stormify-native / jpa-jvm / ratio."""
    head = "| | " + " | ".join(DB_LABEL[d] for d in DB_ORDER) + " |\n"
    sep = "|---|" + "|".join(["---:"] * len(DB_ORDER)) + "|\n"

    if statistic == "p50":
        agg = lambda vs: median(vs)
    elif statistic == "p95":
        agg = lambda vs: percentile(vs, 0.95)
    elif statistic == "p99":
        agg = lambda vs: percentile(vs, 0.99)
    else:
        raise ValueError(f"unknown statistic: {statistic}")

    def cell_for(db, impl):
        vs = stats.get((db, scenario, kind, impl), [])
        return agg(vs)

    natives = {db: cell_for(db, "stormify-native") for db in DB_ORDER}
    jpas    = {db: cell_for(db, "jpa-jvm")         for db in DB_ORDER}

    def row(label, fn):
        return f"| {label} | " + " | ".join(fn(db) for db in DB_ORDER) + " |\n"

    out = head + sep
    out += row("Stormify-native", lambda db: fmt_us(natives[db]))
    out += row("Hibernate (JPA)", lambda db: fmt_us(jpas[db]))
    out += row("Speedup (higher=better)",
               lambda db: ratio_cell(natives[db], jpas[db]))
    return out


def render_scenario_tabs(stats, scenario, kind):
    """A pymdownx.tabbed block with one tab per percentile. Bulk inserts
    only have a single `total` row in the CSV, so for those we fall back
    to a single table with no tabs (the percentile machinery would otherwise
    return the same number three times)."""
    # Detect single-sample scenarios (bulk_insert_* totals) — show one table.
    sample_count = max(
        len(stats.get((db, scenario, kind, impl), []))
        for db in DB_ORDER
        for impl in ("stormify-native", "jpa-jvm")
    )
    if sample_count <= 1:
        return render_scenario_table_for(stats, scenario, kind, "p50")

    out = []
    for stat in ("p50", "p95", "p99"):
        out.append(f'=== "{stat}"\n\n')
        table = render_scenario_table_for(stats, scenario, kind, stat)
        # Indent table by 4 spaces — pymdownx.tabbed wants content indented.
        out.append("\n".join("    " + line if line else line
                             for line in table.splitlines()))
        out.append("\n\n")
    return "".join(out)


def render_section(stats, scenarios, kind, label_prefix):
    """For inserts, the `insert_*` rows live under kind='total' (one
    wall-clock measurement per phase) while `single_insert_1000` sits under
    kind='iter' (per-row p50). We try both kinds and render whichever has
    data so callers don't need to split the scenario list. Each scenario
    tuple is `(name, blurb, sql)`; the SQL block is rendered above the
    timing table so readers see what's actually being measured."""
    out = []
    for scen, blurb, sql in scenarios:
        kind_used = kind
        if not any(stats.get((db, scen, kind, impl), [])
                   for db in DB_ORDER for impl in ("stormify-native", "jpa-jvm")):
            # Try the alternate kind (handles the insert_* / total split).
            alt = "total" if kind == "iter" else "iter"
            if not any(stats.get((db, scen, alt, impl), [])
                       for db in DB_ORDER for impl in ("stormify-native", "jpa-jvm")):
                continue
            kind_used = alt
        out.append(f"### {scen}\n\n_{blurb}._\n\n")
        out.append(f"```sql\n{sql}\n```\n\n")
        out.append(render_scenario_tabs(stats, scen, kind_used))
        out.append("\n")
    return "".join(out)


def render_startup_table(stats, rss):
    """Two-table block: cold-start time + RSS, one row per impl plus a
    speedup row. Picks `startup_bench-read` because that's the heavier
    path (loads Hibernate metadata + entity manager factory); native's
    number is essentially the same on the insert path."""
    head = "| | " + " | ".join(DB_LABEL[d] for d in DB_ORDER) + " |\n"
    sep = "|---|" + "|".join(["---:"] * len(DB_ORDER)) + "|\n"

    def fmt_speedup(jpa, native, units_label):
        """`native` is the smaller value (faster / lighter). Speedup =
        jpa / native. The colour rules from the per-iter speedups apply
        identically here — green for any ≥ 1.111×, parity below that."""
        if native is None or jpa is None or native == 0:
            return "—"
        s = jpa / native
        # Cold-start / RSS comparisons are dominated by the JVM's fixed
        # cost; the ratios are large enough that we don't need the muted
        # parity band. Use the same green/red thresholds as elsewhere.
        if s >= 1.111:
            return f'<span style="color:#2e7d32">{s:.0f}× {units_label}</span>'
        if s > 0.909:
            return f"{s:.2f}×"
        return f'<span style="color:#c62828">{s:.2f}× slower</span>'

    def row_time(label, impl):
        cells = []
        for db in DB_ORDER:
            vs = stats.get((db, "startup_bench-read", "ready_ns", impl), [])
            cells.append(fmt_ms(median(vs)) if vs else "—")
        return f"| {label} | " + " | ".join(cells) + " |\n"

    def row_rss(label, impl):
        cells = []
        for db in DB_ORDER:
            vs = rss.get((db, "startup_bench-read", "ready_ns", impl), [])
            cells.append(fmt_mb(median(vs)) if vs else "—")
        return f"| {label} | " + " | ".join(cells) + " |\n"

    def row_time_speedup():
        cells = []
        for db in DB_ORDER:
            n = median(stats.get((db, "startup_bench-read", "ready_ns", "stormify-native"), []))
            j = median(stats.get((db, "startup_bench-read", "ready_ns", "jpa-jvm"), []))
            cells.append(fmt_speedup(j, n, "faster"))
        return "| Native is | " + " | ".join(cells) + " |\n"

    def row_rss_speedup():
        cells = []
        for db in DB_ORDER:
            n = median(rss.get((db, "startup_bench-read", "ready_ns", "stormify-native"), []))
            j = median(rss.get((db, "startup_bench-read", "ready_ns", "jpa-jvm"), []))
            cells.append(fmt_speedup(j, n, "lighter"))
        return "| Native is | " + " | ".join(cells) + " |\n"

    out = "**Cold start — process spawn to first query ready**\n\n"
    out += head + sep
    out += row_time("Stormify-native", "stormify-native")
    out += row_time("Hibernate (JPA)", "jpa-jvm")
    out += row_time_speedup()
    out += "\n**Resident set size at startup**\n\n"
    out += head + sep
    out += row_rss("Stormify-native", "stormify-native")
    out += row_rss("Hibernate (JPA)", "jpa-jvm")
    out += row_rss_speedup()
    return out


def replace_marker(text, marker, content):
    """Replace `<!-- MARKER -->` (and any prior generated content following
    it up to the next `<!-- /MARKER -->` or next H2) with new content."""
    open_tag = f"<!-- {marker} -->"
    close_tag = f"<!-- /{marker} -->"
    # If close tag already exists, replace between them
    if close_tag in text:
        pat = re.escape(open_tag) + r".*?" + re.escape(close_tag)
        return re.sub(pat, f"{open_tag}\n{content}\n{close_tag}", text, flags=re.DOTALL)
    # Otherwise insert content + close tag right after open tag
    return text.replace(open_tag, f"{open_tag}\n{content}\n{close_tag}", 1)


def main(csv_path, doc_path):
    stats = parse_csv(csv_path)
    rss = parse_csv_rss(csv_path)
    text = Path(doc_path).read_text()

    text = replace_marker(text, "STARTUP_RESULTS", render_startup_table(stats, rss))
    text = replace_marker(text, "READ_RESULTS", render_section(stats, READ_SCENARIOS, "iter", "read"))
    text = replace_marker(text, "INSERT_RESULTS", render_section(stats, INSERT_SCENARIOS, "iter", "insert"))

    Path(doc_path).write_text(text)
    def has_data(scen):
        return any(stats.get((db, scen, k, i), [])
                   for db in DB_ORDER
                   for k in ("iter", "total")
                   for i in ("stormify-native", "jpa-jvm"))
    n_read = sum(1 for s, *_ in READ_SCENARIOS if has_data(s))
    n_ins  = sum(1 for s, *_ in INSERT_SCENARIOS if has_data(s))
    print(f"updated {doc_path}: {n_read} read scenarios, "
          f"{n_ins} insert scenarios rendered.")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} results.csv docs/src/Benchmarks.md", file=sys.stderr)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
