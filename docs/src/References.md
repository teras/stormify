# References and Lazy Loading

When an entity property's type is another entity (not a primitive, `String`, date, etc.),
Stormify treats it as a **reference field**: the database column stores the primary-key
value of the referenced entity, and Stormify reads or writes the referenced entity as
needed.

## Reference Fields (Foreign Keys)

When a property's type is another entity, the database column stores the primary key
value of the referenced entity:

=== "Kotlin"

    ```kotlin
    data class Order(
        @DbField(primaryKey = true)
        var id: Int = 0,
        var customer: Customer? = null  // DB column stores customer's PK
    )
    ```

=== "Java"

    ```java
    public class Order {
        @DbField(primaryKey = true)
        private Integer id;
        private Customer customer;  // DB column stores customer's PK
        // getters/setters omitted
    }
    ```

When reading an `Order`, Stormify creates a `Customer` instance with **only its primary key
set** — a *shadow reference*. If `Customer` extends `AutoTable`, the remaining fields are
auto-loaded on first access (see below).

## AutoTable: Auto-Hydration

`AutoTable` is an abstract base class for entities whose fields are auto-hydrated from
the database when the library produces a shadow reference for them. The behavior is
**ownership-based**: who created the entity determines whether the library is allowed to
fetch from the database without an explicit request.

| How it was created | Behavior on first `db` field access |
|---|---|
| **User-constructed** — `User()` in your code, possibly via `apply { id = 1 }` | No DB access. Reads return the in-memory value (or the delegate default); writes just store. |
| **Library-constructed shadow reference** — the FK stub Stormify produces when loading a row whose column points to another entity | The row is fetched, all fields are filled, then the original read or write proceeds. |
| **Already loaded** — result of `findById` / `findAll` / `create`, or a shadow that has already been hydrated | No DB access. Reads return the loaded values. |

The distinction is decided **at construction time** and never changes for the lifetime of
the instance. The library marks every shadow reference it creates; user-constructed
entities are not marked.

### How It Works in Kotlin

The `by db(defaultValue)` delegate makes a field auto-hydrating *only on shadow
references*. On user-constructed entities, the delegate just stores and returns the
value, with no DB access.

```kotlin
class User : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var name: String by db("")
    var email: String by db("")
}
```

Use `by db(defaultValue)` for every non-primary-key property.

### How It Works in Java

Java subclasses call `hydrate()` (a `protected` method on `AutoTable`) from every
getter/setter of a non-primary-key field. `hydrate()` is a no-op on user-constructed and
already-loaded entities; on a shadow reference it loads the row exactly once.

```java
public class User extends AutoTable {
    private Integer id;
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() {
        hydrate();
        return name;
    }
    public void setName(String name) {
        hydrate();
        this.name = name;
    }
}
```

### Loading by ID

To fetch a row when you have its primary key, use `findById`:

=== "Kotlin"

    ```kotlin
    val user = findById<User>(userId)
        ?: error("User $userId not found")
    println(user.name)
    println(user.email)   // no second query — already loaded
    ```

=== "Java"

    ```java
    User user = StormifyJ.getDefault().findById(User.class, userId);
    if (user == null) throw new IllegalStateException("User " + userId + " not found");
    System.out.println(user.getName());
    System.out.println(user.getEmail());   // no second query — already loaded
    ```

### Refresh

To re-fetch an entity that has already been loaded — for example, because another part of
the system may have written to the row — call `stormify.refresh(entity)`. Every call
performs a SELECT and overwrites the in-memory fields with the database values. In-memory
changes set before the call are lost; if you need them afterwards, set them again after
`refresh` returns.

=== "Kotlin"

    ```kotlin
    val user = findById<User>(42)!!
    stormify.refresh(user)   // forces a re-read from the DB
    ```

=== "Java"

    ```java
    User user = StormifyJ.getDefault().findById(User.class, 42);
    StormifyJ.getDefault().refresh(user);
    ```

`Stormify.refresh(entity)` is the single direct-call API for forcing a reload.
`hydrate()` is the at-most-once auto-load used by the `db` delegate and by Java
getters/setters of `AutoTable` subclasses.

### Lazy Details (Child Records)

A regular `db` reference (`var customer: Customer? by db(null)`) lets you navigate from a
**child** to its **parent**: given an `Order`, you follow the `customer` reference to
reach the owning customer. `lazyDetails` is the **mirror direction** — it lets you
navigate from a **parent** to all of its **children**: given a `Customer`, you ask for
every `Order` that references it; given an `Order`, you ask for every `OrderItem` that
belongs to it. The relationship itself is the same foreign key on the child side; only
the direction of traversal differs.

=== "Kotlin"

    ```kotlin
    class Order : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var total: Double by db(0.0)
        var customer: Customer? by db(null)            // child → parent
        var items: List<OrderItem> by lazyDetails()    // parent → children
    }
    ```

    The `lazyDetails` delegate **caches** the loaded list on the instance, so
    repeated reads of `order.items` don't re-query. Assigning (`order.items = newList`)
    replaces the cached value in memory without a write — persisting new children is
    still up to you (`stormify.create(newItems)`).

=== "Java"

    ```java
    public class Order extends AutoTable {
        @DbField(primaryKey = true)
        private Integer id;
        private Customer customer;    // child → parent

        @Transient
        private List<OrderItem> items;    // parent → children (cached)

        public List<OrderItem> getItems() {
            if (items == null)
                items = details(OrderItem.class);
            return items;
        }
        // other getters/setters omitted
    }
    ```

On first access, `order.items` runs a query equivalent to:

```sql
SELECT * FROM order_item WHERE order_id = ?
```

Stormify discovers the foreign-key column automatically by scanning the child type
(`OrderItem`) for a property of the parent type (`Order`) — no annotation, no join
table, no configuration.

If the child type has **multiple foreign keys** pointing to the same parent type —
say an `OrderItem` that tracks both its current `order: Order` **and** the
`originalOrder: Order` it was placed in before a split or merge — you must
disambiguate by telling Stormify which of the child-side foreign keys to filter on.
Two forms are supported and they are exactly equivalent at runtime; the first one is
preferred because the compiler catches typos and refactors it for you.

**Type-safe (recommended):** use a reference path generated automatically by the
Stormify Gradle plugin (see [Installation](Installation.md)).
`Tables.OrderItem_.order` is a handle to the
`order` property on `OrderItem` — if you rename or delete the property, the code
stops compiling; if you point it at a scalar field instead of a reference, the
overload doesn't exist and the call fails to resolve.

=== "Kotlin"

    ```kotlin
    class OrderItem : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var order: Order? by db(null)
        var originalOrder: Order? by db(null)
    }

    class Order : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var items: List<OrderItem>
            by lazyDetails(Tables.OrderItem_.order)
        var splitItems: List<OrderItem>
            by lazyDetails(Tables.OrderItem_.originalOrder)
    }
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.generated.Tables;

    public class Order extends AutoTable {
        @DbField(primaryKey = true)
        private Integer id;

        @Transient
        private List<OrderItem> items;
        @Transient
        private List<OrderItem> splitItems;

        public List<OrderItem> getItems() {
            if (items == null)
                items = details(OrderItem.class, Tables.OrderItem_.order());
            return items;
        }

        public List<OrderItem> getSplitItems() {
            if (splitItems == null)
                splitItems = details(OrderItem.class, Tables.OrderItem_.originalOrder());
            return splitItems;
        }
    }
    ```

**String form:** pass the **Kotlin property name on the child class** as a plain
string — the name of the `var`/`val` as declared in the child's source, **not** the
database column name. If you've remapped the column via
`@DbField(name = "original_order_id")`, you still pass `"originalOrder"` here —
Stormify does the `property → column` translation internally.

=== "Kotlin"

    ```kotlin
    class Order : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var items: List<OrderItem> by lazyDetails("order")
        var splitItems: List<OrderItem> by lazyDetails("originalOrder")
    }
    ```

=== "Java"

    ```java
    public class Order extends AutoTable {
        @DbField(primaryKey = true)
        private Integer id;

        @Transient
        private List<OrderItem> items;
        @Transient
        private List<OrderItem> splitItems;

        public List<OrderItem> getItems() {
            if (items == null)
                items = details(OrderItem.class, "order");
            return items;
        }

        public List<OrderItem> getSplitItems() {
            if (splitItems == null)
                splitItems = details(OrderItem.class, "originalOrder");
            return splitItems;
        }
    }
    ```

The value must be a **single field identifier** on the child side. The type-safe
form enforces this statically: `Tables.OrderItem_.order.name` produces a `ScalarPath`
and the `lazyDetails(ReferencePath)` overload rejects it at compile time. The
string form validates the same thing at query time — dotted forms like
`"order.customer"` are rejected with a clear error. `lazyDetails` always follows
exactly one foreign key on the child side; if you need to traverse deeper, chain a
second read or write the query by hand.

For one-off access without declaring a delegate on the parent class, the same
mechanism is available as an imperative call — useful when you don't control the
entity class or want to keep the relationship out of the domain model. Both forms
(type-safe and string) are supported symmetrically:

=== "Kotlin"

    ```kotlin
    // Auto-resolution — child type has exactly one FK back to Order
    val items: List<OrderItem> = stormify.getDetails(order)
    val items2: List<OrderItem> = order.details<OrderItem>()

    // Type-safe with Paths
    val items3: List<OrderItem> = stormify.getDetails(order, Tables.OrderItem_.order)
    val items4: List<OrderItem> = order.details<OrderItem>(Tables.OrderItem_.order)

    // String form with the Kotlin property name
    val items5: List<OrderItem> = stormify.getDetails(order, "order")
    val items6: List<OrderItem> = order.details<OrderItem>("order")
    ```

=== "Java"

    ```java
    StormifyJ stormify = StormifyJ.getDefault();

    // Auto-resolution — child type has exactly one FK back to Order
    List<OrderItem> items =
        stormify.getDetails(order, OrderItem.class);

    // Type-safe with Paths
    List<OrderItem> items2 =
        stormify.getDetails(order, OrderItem.class, Tables.OrderItem_.order());

    // String form with the Kotlin property name
    List<OrderItem> items3 =
        stormify.getDetails(order, OrderItem.class, "order");
    ```

### Sibling Batch Optimization

Every ORM-level read call (`findAll`, `findById`, `read`, indexed `PagedList` access,
and anything else that materialises a page of results before handing it to your code)
runs inside an internal **population context** that lives for the duration of that
single query. As rows come back and their reference fields are resolved into stubs,
each stub is placed into a **sibling group** keyed by the target entity type. All
`Customer` stubs created while reading 100 `Order` rows end up in the same group; all
`Category` stubs end up in another; and so on.

Two things happen inside that context, and they work together to eliminate the classic
"N+1 query" problem:

1. **Deduplication by `(type, id)`.** If ten of the hundred orders happen to reference
   the same `Customer(42)`, only one stub is ever created — the other nine properties
   share the same wrapper instance. You pay for `Customer(42)` exactly once regardless
   of how many rows hold a pointer to it.
2. **Batched lazy loading.** The first time anyone reads a field on any stub in the
   group (e.g. `orders[0].customer.name`), the whole group wakes up and runs **one**
   `SELECT ... WHERE id IN (?, ?, …)` covering up to 32 siblings at a time. The stub
   that triggered the load pays the round-trip; the other 31 get their data for free
   and are marked hydrated, so subsequent field accesses on them don't touch the
   database at all.

The batch size of 32 is a compile-time constant chosen to keep the `IN` list small
enough for every supported dialect (including Oracle's 1,000-element limit) while still
collapsing the round-trip count by more than an order of magnitude. Groups larger than
32 members are simply drained in successive batches, each triggered by the next
unhydrated stub that gets touched.

**A concrete comparison**, reading 100 orders and printing each customer's name:

| Behavior | Main query | FK-resolution queries | Total round trips |
|---|---|---|---|
| Without sibling batching | 1 | 100 | **101** |
| With sibling batching (this mechanism) | 1 | ⌈100 / 32⌉ = 4 | **5** |

The optimisation is **fully automatic** — nothing to configure, nothing to annotate,
no explicit `JOIN` required. It kicks in for every code path that reads a page of
entities in one shot and then lets your code traverse their references.

If some of the requested IDs are not in the database at the time of the batch load,
the references that were found are loaded as usual, and only the missing ones fail:
each access to one of them throws `SQLException("No data found ...")`, so a stale or
dangling reference surfaces loudly without breaking its valid siblings. To have such
references read as `null` instead, see [Dangling References](#dangling-references).

### Dangling References

A foreign key can point at a row that does not exist, typically in a legacy database
without foreign key constraints. `danglingReferences` on the instance decides what such a
reference does:

- `DanglingReferences.THROW` (default): the reference is a lazy entity like any other,
  and loading it throws `SQLException` "No data found", on every access. References to
  existing rows loaded in the same batch are unaffected.
- `DanglingReferences.NULL`: the reference reads as `null`, as with JPA. When a read
  completes (a query, a `refresh`, or the lazy load of a reference), the keys its rows
  refer to are checked with one query per referenced type, and every property that
  points at a missing row is set to `null`. The other references stay lazy. Inside a
  transaction the check runs on its connection, so its own rows count; outside, on a
  connection of its own.
  - The entity remembers the key: an INSERT or UPDATE writes it back while the property
    is still `null`, so reading and saving an entity never turns a dangling foreign key
    into `NULL` in the database. Setting the property through its `db` delegate, to an
    entity or to `null`, replaces the key; the next update writes what was set. A plain
    `var` without the delegate cannot report being set to `null`, and keeps writing the
    remembered key.
  - References with a composite key are not checked: they stay lazy and throw when
    loaded, as under `THROW`.
  - This applies to entities that extend `StormifyEntity` (and so `AutoTable`), which
    can remember the key. A plain class keeps the reference object instead, which holds
    the key and is written back as it is.
  - A reference obtained some other way (a non-nullable property, a reference read while
    the policy was `THROW`) loads as an entity with default field values instead of
    throwing.
  - A streaming read (`readCursor`, `forEachStreaming`) checks its rows once the
    cursor is exhausted and hands them to the consumer after that, so the consumer sees
    the dangling references as `null`. The rows of such a read are therefore all in
    memory before the first one is consumed.

=== "Kotlin"

    ```kotlin
    stormify.danglingReferences = DanglingReferences.NULL
    ```

=== "Java"

    ```java
    stormify.setDanglingReferences(DanglingReferences.NULL);
    ```

### Serialization

On JVM and Android, entities that extend `AutoTable` (or `StormifyEntity`) are
`java.io.Serializable`, together with the `db` and `lazyDetails` delegates that the
Kotlin compiler stores in them. An entity can therefore be kept where the platform
serializes objects, such as an HTTP session or an Android `Bundle`, as long as the
values of its fields are serializable too.

What is serialized is the entity's own state:

- Values already loaded, including `lazyDetails` lists already read, are kept and are
  not reloaded after deserialization.
- A shadow reference that was never loaded stays unloaded, and loads its row on first
  access after deserialization.
- The `Stormify` instance and the sibling group are not serialized. A deserialized
  entity is detached, so the loads above go through `Stormify.defaultInstance`, which
  must be set (see [Configuration](Configuration.md)); an unloaded reference accessed
  without one throws `SQLException`. The rows of a deserialized page no longer share a
  group, so each reference loads alone, and a copy of the update reference kept for
  `dynamicUpdate` is not serialized, so the first update after deserialization writes
  every column.

Native targets have no built-in object serialization; there the marker interface
`PlatformSerializable` is empty. `PagedList` and `PagedQuery` are not serializable:
keep the list in a transient field and rebuild it from its definition and its saved
state (`saveState()`/`restoreState()`).
