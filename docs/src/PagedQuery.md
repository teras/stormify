# PagedQuery: Stateless Paginated Queries

`PagedQuery<T>` is a thread-safe, stateless paginated query executor. It is the
server-side counterpart to [`PagedList`](PagedList.md): both use the same underlying
query engine (automatic JOINs through FK traversal, per-facet filter/sort semantics,
raw SQL expressions, fixed constraints, enum/date handling), but `PagedQuery` exposes
it through an API designed for REST handlers, RPC methods, background workers — any
context that is stateless per request.

## Quick Start

Configure a `PagedQuery` **once**, typically at application startup — then share the
instance across arbitrarily many concurrent requests.

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.biglist.Facet
    import onl.ycode.stormify.biglist.PageSpec
    import onl.ycode.stormify.biglist.PagedQuery
    import onl.ycode.stormify.biglist.SortDir

    // One-time setup (startup, DI container, etc.)
    val customers = PagedQuery<Customer>().apply {
        addFacet("search", "name", "email", "city").isSortable = false   // search is filter-only
        addFacet("name", "name")                                         // filter + sort
        addFacet("city", "city")
        setConstraints("customer.tenant_id = ?", currentTenantId)
    }

    // Per request:
    fun list(req: HttpRequest): HttpResponse {
        val page = customers.execute(
            PageSpec(
                filters = mapOf("search" to req.q),
                sorts = mapOf("name" to SortDir.ASC),
                page = req.page,
                pageSize = req.size,
            )
        )
        return HttpResponse.json(mapOf(
            "items" to page.rows.map { it.toDto() },
            "total" to page.total,
            "page" to page.page,
            "totalPages" to page.totalPages,
        ))
    }
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.biglist.Facet;
    import onl.ycode.stormify.biglist.PageSpec;
    import onl.ycode.stormify.biglist.PagedQuery;
    import onl.ycode.stormify.biglist.SortDir;

    // One-time setup (startup, DI container, etc.)
    PagedQuery<Customer> customers = new PagedQuery<>(Customer.class);
    customers.addFacet("search", "name", "email", "city").setSortable(false);   // search is filter-only
    customers.addFacet("name", "name");                                          // filter + sort
    customers.addFacet("city", "city");
    customers.setConstraints("customer.tenant_id = ?", currentTenantId);

    // Per request:
    public HttpResponse list(HttpRequest req) {
        Page<Customer> page = customers.execute(new PageSpec(
            req.page(), req.size(),
            Map.of("search", req.q()),
            Map.of("name", SortDir.ASC)
        ));
        return HttpResponse.json(Map.of(
            "items", page.getRows().stream().map(Customer::toDto).toList(),
            "total", page.getTotal(),
            "page", page.getPage(),
            "totalPages", page.getTotalPages()
        ));
    }
    ```

## Aliases are the whole contract

Every facet in a `PagedQuery` has a string [`alias`][Facet.alias]. **The alias is
the only identifier the outside world ever sees** — it is the key in
`PageSpec.filters`, `PageSpec.sorts`, `PageSpec.caseSensitive`, and the only name by
which the client can reference the facet.

Aliases are passed explicitly when a facet is added:

=== "Kotlin"

    ```kotlin
    query.addFacet("search", "name", "email", "city")
    //              ^ alias   ^ fields the alias maps to (OR between them)
    ```

=== "Java"

    ```java
    query.addFacet("search", "name", "email", "city");
    //              ^ alias   ^ fields the alias maps to (OR between them)
    ```

Never use field paths or SQL expressions as aliases — that would defeat the whole
point. Choose stable business-level names (`"name"`, `"total"`, `"search"`) so the
wire contract does not churn when the schema does.

### Per-facet capability flags

Two flags on `Facet` control what a client is allowed to do with an alias:

=== "Kotlin"

    ```kotlin
    query.addFacet("search", "name", "email").isSortable = false     // search is filter-only
    query.addFacet("created", "createdAt").isFilterable = false      // sortable header, no filter input
    ```

=== "Java"

    ```java
    query.addFacet("search", "name", "email").setSortable(false);    // search is filter-only
    query.addFacet("created", "createdAt").setFilterable(false);     // sortable header, no filter input
    ```

- A filter in a `PageSpec` against a facet whose `isFilterable = false` throws.
- A sort against a facet whose `isSortable = false` throws.

Facets that are neither filterable nor sortable should simply not be registered —
there is nothing to gain by exposing them.

## The spec and the result

[`PageSpec`][PageSpec] and [`Page`][Page] are plain data — no annotations, no
companions, no custom type adapters — so they round-trip through any serializer
(Kotlinx Serialization, Jackson, Gson, Parcelable, …).

```kotlin
data class PageSpec(
    val page: Int,
    val pageSize: Int,
    val filters: Map<String, String> = emptyMap(),
    val sorts: Map<String, SortDir> = emptyMap(),
    val caseSensitive: Map<String, Boolean> = emptyMap(),
) {
    // Non-paginated overload — delegates to PageSpec(0, 15, filters, sorts, caseSensitive).
    // Use for forEachStreaming / getAggregator where pagination is ignored anyway.
    constructor(
        filters: Map<String, String> = emptyMap(),
        sorts: Map<String, SortDir> = emptyMap(),
        caseSensitive: Map<String, Boolean> = emptyMap(),
    )
}

data class Page<T>(
    val rows: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
) {
    val totalPages: Int
}
```

Both constructors are `@JvmOverloads`-annotated, so Java can pick whichever form
matches its needs — `new PageSpec(page, pageSize, filters, …)` for paginated
queries or `new PageSpec(filters, …)` for non-paginated use cases — without
having to spell out the other defaults.

Aliases not present in the configured facet set are silently dropped from `filters`,
`sorts`, and `caseSensitive`. A sort whose value is neither `SortDir.ASC` nor
`SortDir.DESC` cannot be constructed (the enum prevents it).

An incoming JSON request body matching the shape above can be deserialized
into a `PageSpec` directly via `PageSpec.fromJson(String)`.

## Fixed constraints

Use [`setConstraints`][PagedQuery.setConstraints] to pin a baseline `WHERE` that
the client **cannot** override — tenant scoping, soft-delete filters, permission
gates, etc:

=== "Kotlin"

    ```kotlin
    customers.setConstraints("customer.tenant_id = ? AND customer.deleted = 0", tenantId)
    ```

=== "Java"

    ```java
    customers.setConstraints("customer.tenant_id = ? AND customer.deleted = 0", tenantId);
    ```

Any per-request spec filter then composes with this via `AND`. There is no way for a
client to bypass it through a crafted alias or spec value — aliases are opaque
lookups, not SQL.

See [PagedList · Constraints](PagedList.md#constraints).

## Raw facets

Raw SQL facets work the same way as on `PagedList`, but are always configured
server-side and addressed by alias only:

=== "Kotlin"

    ```kotlin
    query.addSqlFacet("total", "SUM(order_total)", Facet.NUMERIC)
    // client filter `{"total": ">=1000"}` becomes SUM(order_total) >= 1000
    ```

=== "Java"

    ```java
    query.addSqlFacet("total", "SUM(order_total)", Facet.NUMERIC);
    // client filter `{"total": ">=1000"}` becomes SUM(order_total) >= 1000
    ```

The raw expression is **never** derived from user input — it is part of the server's
configuration and the client never sees or sends SQL.

See [PagedList · Raw Facets](PagedList.md#raw-facets).

## Streaming

For bulk exports, report generation, or any case where you need to visit every
matching row, `forEachStreaming(spec, action)` opens a database cursor and
forwards rows to the action in chunks that match Stormify's sibling-batch size,
so FK touches inside the callback resolve in one query per chunk. The `page`
and `pageSize` fields on [`PageSpec`][PageSpec] are ignored — streaming always
covers every matching row, so callers are responsible for bounding the result
set via filters and constraints.

=== "Kotlin"

    ```kotlin
    val spec = PageSpec(filters = mapOf("active" to "true"))
    customers.forEachStreaming(spec) { c -> writeCsvRow(c) }
    ```

=== "Java"

    ```java
    PageSpec spec = new PageSpec(Map.of("active", "true"));
    customers.forEachStreaming(spec, c -> writeCsvRow(c));
    ```

The lock described under [Concurrency](#concurrency) is held only around the
(cheap) SQL-plan stage — the iteration itself runs outside the lock, so
concurrent callers do not block on each other's cursor lifetime.

See [PagedList · Streaming](PagedList.md#streaming-with-foreachstreaming).

## Aggregations

`getAggregator(spec)` returns an aggregation builder bound to this query and
the filter/constraint state derived from [`spec`][PageSpec]. Page and page size
on the spec are ignored — aggregations always consider the entire matching set.
Chain any combination of `sum`, `avg`, `min`, `max`, `count`, `countDistinct`
or `raw`, then call `execute<T>()` for a single scalar or `execute()` for a
`Map<String, Any?>` keyed by each added aggregation's alias.

=== "Kotlin"

    ```kotlin
    val total: BigDecimal? = orders.getAggregator(spec)
        .sum(Order_.amount)
        .execute<BigDecimal>()

    val summary: Map<String, Any?> = orders.getAggregator(spec)
        .sum(Order_.amount, "total")
        .avg(Order_.amount, "average")
        .count("*", "cnt")
        .execute()
    ```

=== "Java"

    ```java
    BigDecimal total = orders.getAggregator(spec)
        .sum(Order_.amount)
        .execute(BigDecimal.class);

    Map<String, Object> summary = orders.getAggregator(spec)
        .sum(Order_.amount, "total")
        .avg(Order_.amount, "average")
        .count("*", "cnt")
        .execute();
    ```

See [PagedList · Aggregations](PagedList.md#aggregations).

## Facet values

For facet pickers / autocomplete dropdowns, `filterValues(alias, spec)` returns
a [`Page<String>`][Page] of distinct values for the named facet. The facet's
own filter is excluded from the query, so the picker keeps showing every value
the user could select — the active filters of *other* facets still apply.
`filterValuesWithCounts(alias, spec)` returns the same values paired with row
counts, facet-navigation style.

=== "Kotlin"

    ```kotlin
    val cities: Page<String> =
        customers.filterValues("city", spec)

    val byCategory: Page<FilterCountedValue> =
        products.filterValuesWithCounts("category", spec)
    ```

=== "Java"

    ```java
    Page<String> cities = customers.filterValues("city", spec);

    Page<FilterCountedValue> byCategory =
        products.filterValuesWithCounts("category", spec);
    ```

The `page` and `pageSize` fields of the supplied spec paginate the distinct
values themselves.

See [PagedList · Filter Values](PagedList.md#filter-values-distinct-per-facet).

## Table refs

Raw SQL in [`setConstraints`](#fixed-constraints) or in a raw facet sometimes
needs to reference a joined table by its SQL alias. Register a `TableRef` with
`addTableRef(...)` (root, dotted string path, or KSP typed `ReferencePath`) and
use its `alias` so the engine's JOIN aliasing stays opaque to your expressions.

=== "Kotlin"

    ```kotlin
    val query = PagedQuery<Customer>().apply {
        val root    = addTableRef()                       // the root entity table
        val address = addTableRef("address")              // FK-traversed
        val hq      = addTableRef(Customer_.company.hq)   // KSP typed path

        addFacet("name", Customer_.name)
        addSqlFacet("sameCity",
            "CASE WHEN $address.city = $hq.city THEN 1 ELSE 0 END",
            Facet.NUMERIC)
        setConstraints("$root.tenant_id = ?", currentTenantId)
    }
    ```

=== "Java"

    ```java
    PagedQuery<Customer> query = new PagedQuery<>(Customer.class);
    TableRef root    = query.addTableRef();                          // the root entity table
    TableRef address = query.addTableRef("address");                 // FK-traversed
    TableRef hq      = query.addTableRef(Customer_.company().hq);    // KSP typed path

    query.addFacet("name", Customer_.name);
    query.addSqlFacet("sameCity",
        "CASE WHEN " + address.getAlias() + ".city = "
            + hq.getAlias() + ".city THEN 1 ELSE 0 END",
        Facet.NUMERIC);
    query.setConstraints(root.getAlias() + ".tenant_id = ?", currentTenantId);
    ```

See [PagedList · Table Refs](PagedList.md#table-refs).

## Concurrency

`PagedQuery.execute()` is thread-safe. The engine holds a per-instance lock around
the (cheap) SQL-plan stage, because the underlying join-tree activation mutates
per-node flags; the lock is released before the database roundtrip so that the
expensive work runs in parallel.

**A `PagedQuery` is auto-sealed the first time it reads data from the database.**
After that, any attempt to change its configuration — adding facets, table refs
or constraints, toggling flags, or mutating any setup-time property on a
registered `Facet` or `TableRef` — throws `IllegalArgumentException`.

The seal enforces the contract that `PagedQuery` is a **configure-once-share-
forever** object: concurrent callers never observe a half-reconfigured engine
because there is no legal way for configuration to change once requests are in
flight. If you need a different shape, construct a fresh instance.

## Why not just use `PagedList` over REST?

`PagedList` is a UI model. Its per-facet `filter` and `sort` are mutable fields
shared across every caller of the list — a second request racing against the
first would corrupt its state. Its cached page, `_size` and `selected` entity
persist across calls and have no meaning in a stateless request/response cycle.
Its `saveState()` produces keys derived internally from paths and SQL
expressions: designed for local round-trip persistence, never as a wire
contract — publishing it as a REST payload exposes field paths, foreign-key
traversals, and raw SQL fragments the server was meant to keep private.

`PagedQuery` is the right tool whenever you find yourself either instantiating
a fresh `PagedList` per HTTP request, or mapping request parameters to
`facet.filter` and `facet.sort` by hand. The stateless engine does both for you.

| Scenario | Use |
| --- | --- |
| Server renders a desktop grid via ZK / JavaFX / Swing / Compose Desktop. | `PagedList` |
| Embedded UI keeps a single list instance alive across user interactions. | `PagedList` |
| REST endpoint returns a page of rows per request. | `PagedQuery` |
| RPC / gRPC method with pagination. | `PagedQuery` |
| Anything where a fresh instance is constructed per incoming request. | `PagedQuery` |

If the answer is "both" — a backend that serves both a desktop client *and* a
REST façade over the same data — configure one `PagedQuery` for the REST side
and let the desktop client use its own `PagedList` locally. They are independent
views, sharing nothing but your entity classes.

[Facet.alias]: api-stormify/stormify/onl.ycode.stormify.biglist/-facet/alias.html
[PageSpec]: api-stormify/stormify/onl.ycode.stormify.biglist/-page-spec/index.html
[Page]: api-stormify/stormify/onl.ycode.stormify.biglist/-page/index.html
[PagedQuery.setConstraints]: api-stormify/stormify/onl.ycode.stormify.biglist/-abstract-paged-query/set-constraints.html
