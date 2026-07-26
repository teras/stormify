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
                items = StormifyJ.getDefault().getDetails(this, OrderItem.class);
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
                items = StormifyJ.getDefault().getDetails(
                    this, OrderItem.class, Tables.OrderItem_.order());
            return items;
        }

        public List<OrderItem> getSplitItems() {
            if (splitItems == null)
                splitItems = StormifyJ.getDefault().getDetails(
                    this, OrderItem.class, Tables.OrderItem_.originalOrder());
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
                items = StormifyJ.getDefault().getDetails(
                    this, OrderItem.class, "order");
            return items;
        }

        public List<OrderItem> getSplitItems() {
            if (splitItems == null)
                splitItems = StormifyJ.getDefault().getDetails(
                    this, OrderItem.class, "originalOrder");
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

If any of the requested IDs are no longer present in the database at the time of the
batch load, the whole call fails fast with an `SQLException("No data found for <table>
with ids [<missing>]")`. This surfaces stale references (rows deleted in another
session between when you obtained them and when you tried to access them) immediately
instead of silently leaving affected entities empty — a behaviour that matches the
"fail loud" philosophy used elsewhere in the library.
