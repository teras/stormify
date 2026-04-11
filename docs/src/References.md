# References and Lazy Loading

When an entity property's type is another entity (not a primitive, `String`, date, etc.),
Stormify treats it as a **reference field**: the database column stores the primary-key
value of the referenced entity, and Stormify reads or writes the referenced entity as
needed.

## Reference Fields (Foreign Keys)

When a property's type is another entity, the database column stores the primary key
value of the referenced entity:

```kotlin
data class Order(
    @DbField(primaryKey = true)
    var id: Int = 0,
    var customer: Customer? = null  // DB column stores customer's PK
)
```

When reading an `Order`, Stormify creates a `Customer` instance with **only its primary key
set**. If `Customer` extends `AutoTable`, its remaining fields are lazy-loaded on first
access (see below).

## AutoTable: Lazy Loading

`AutoTable` is an abstract base class that provides automatic lazy-loading of entity fields.
When you read a list of entities whose reference fields point to `AutoTable` subclasses,
those references are created with only their primary key set. When you access any non-key
field, the full entity is loaded from the database on demand.

### How It Works in Java

Subclasses must call `populate()` in every getter/setter of non-primary-key fields:

```java
public class User extends AutoTable {
    private Integer id;
    private String name;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() {
        populate();  // Triggers lazy load if needed
        return name;
    }
    public void setName(String name) {
        populate();
        this.name = name;
    }
}
```

### How It Works in Kotlin

In Kotlin, the `db` property delegate eliminates the need to call `populate()` manually:

```kotlin
class User : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var name: String by db("")       // Auto-populated on first access
    var email: String by db("")      // Auto-populated on first access
}
```

Every non-key property that needs lazy loading uses `by db(defaultValue)`.

### Fresh Construction vs. Lazy Stubs

An `AutoTable` instance exists in one of three states, and `populate()` behaves accordingly.
A `Stormify` instance is considered "available" when it is either directly attached to the
entity (by a prior Stormify operation) **or** when a [default
instance](CRUD.md#default-instance) has been registered via `Stormify.asDefault()`.

| State | `Stormify` available? | User touched any `db` field? | Behavior on read |
|-------|----------------------|-------------------------------|------------------|
| **Fresh construct (detached)** — `User().apply { id=1; name="Alice" }` with no default instance | No | Yes (e.g., `name="Alice"`) | Returns in-memory value; no DB access |
| **Manual stub / lazy reference** — `User().apply { id=1 }` where only the ID is set | Yes (via default instance) | No | First access triggers a `SELECT` to load the row |
| **FK reference stub** — came from a foreign-key read; Stormify auto-attaches | Yes (directly) | No | First access triggers a `SELECT` to load the row |
| **Populated** — came from `findById`/`findAll`/`create`, or has been lazy-loaded already | Yes | — | Returns in-memory value; no further DB access |
| **Only-ID, no Stormify anywhere** — `User().apply { id=1 }` with no default instance | No | No | **Throws** `SQLException` — no way to load, and the library refuses to hand back silent defaults |

Stormify distinguishes these states automatically. You don't need to mark anything — the
combination of "is a Stormify reachable?" and "has the user written any delegated field?"
is enough to pick the right behavior.

#### Manual Stubs (Pattern)

You can construct lazy stubs yourself — useful when you already have an ID in hand (e.g.
from a URL parameter, a cache, or another table). Either attach a Stormify instance
explicitly via `stormify.attach(...)`, or register a default via `asDefault()` and let
the library pick it up:

=== "Kotlin"

    ```kotlin
    // Explicit attach — works without a default instance
    val user = stormify.attach(User().apply { id = userId })
    println(user.name)    // triggers SELECT, returns the DB value
    println(user.email)   // already loaded; no second query

    // Or with a default instance — even simpler
    stormify.asDefault()
    val user2 = User().apply { id = userId }
    println(user2.name)   // default instance picks up lazy-load
    ```

=== "Java"

    ```java
    // Explicit attach — works without a default instance
    User user = stormify.attach(new User());
    user.setId(userId);
    System.out.println(user.getName());    // triggers SELECT
    System.out.println(user.getEmail());   // already loaded

    // Or with a default instance
    stormify.asDefault();
    User user2 = new User();
    user2.setId(userId);
    System.out.println(user2.getName());
    ```

This is exactly what `findById(userId)` does internally, with one difference: `findById`
eagerly runs the `SELECT` and returns the populated entity, while the manual-stub pattern
defers the query until the first field access. For "I might not actually read this" code
paths, manual stubs save a round trip.

The same `stormify.attach(target)` API works for any `StormifyAware` object — both
entities and `PagedList` instances — so you only need to remember one pattern.

### Lazy Details (Child Records)

A regular `db` reference (`var customer: Customer by db()`) lets you navigate from a
**child** to its **parent**: given an `Order`, you follow the `customer` reference to
reach the owning customer. `lazyDetails` is the **mirror direction** — it lets you
navigate from a **parent** to all of its **children**: given a `Customer`, you ask for
every `Order` that references it; given an `Order`, you ask for every `OrderItem` that
belongs to it. The relationship itself is the same foreign key on the child side; only
the direction of traversal differs.

```kotlin
class Order : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var total: Double by db(0.0)
    var customer: Customer by db()                 // child → parent (regular reference)
    var items: List<OrderItem> by lazyDetails()    // parent → children (lazy collection)
}
```

On first access, `order.items` transparently runs a query equivalent to:

```sql
SELECT * FROM order_item WHERE order_id = ?
```

Stormify discovers the foreign-key column **automatically** by scanning the child type
(`OrderItem`) for a property whose type is the parent (`Order`). No annotation, no
configuration, no join table — the relationship is inferred from the existing child →
parent reference that you've already declared on the child entity.

The returned list is **cached** on the delegate instance, so repeated reads of
`order.items` don't re-query the database. Assigning to the property
(`order.items = newList`) replaces the cached value in memory without issuing a write —
persistence of the new children is still your responsibility (e.g. via
`stormify.create(newItems)`).

If the child type has **multiple foreign keys** pointing to the same parent type —
e.g., an `AuditEntry` with both `createdBy: User` and `modifiedBy: User` — you must
disambiguate by telling Stormify which of the child-side foreign keys to filter on.
Two forms are supported and they are exactly equivalent at runtime; the first one is
preferred because the compiler catches typos and refactors it for you.

**Type-safe (recommended):** use an annotation-processor-generated reference path.
`Paths.AuditEntry_.createdBy` is a handle to the `createdBy` property on `AuditEntry`
— if you rename or delete the property, the code stops compiling; if you point it at
a scalar field instead of a reference, the overload doesn't exist and the call fails
to resolve.

=== "Kotlin"

    ```kotlin
    class AuditEntry : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var createdBy: User by db()
        var modifiedBy: User by db()
    }

    class User : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var createdEntries: List<AuditEntry>
            by lazyDetails(Paths.AuditEntry_.createdBy)
        var modifiedEntries: List<AuditEntry>
            by lazyDetails(Paths.AuditEntry_.modifiedBy)
    }
    ```

=== "Java"

    Java doesn't have Kotlin property delegates, so the type-safe reference path is
    used with the imperative `getDetails` API from `StormifyJ` (or the equivalent
    method on `TransactionContextJ` inside a transaction).

    ```java
    import db.stormify.Paths;

    public class User extends AutoTable {
        @DbField(primaryKey = true)
        private Integer id;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }

        public List<AuditEntry> getCreatedEntries() {
            return stormify.getDetails(this, AuditEntry.class,
                Paths.AuditEntry_.createdBy());
        }

        public List<AuditEntry> getModifiedEntries() {
            return stormify.getDetails(this, AuditEntry.class,
                Paths.AuditEntry_.modifiedBy());
        }
    }
    ```

**String form:** pass the **Kotlin property name on the child class** as a plain
string — the name of the `var`/`val` as declared in the child's source, **not** the
database column name. If you've remapped the column via
`@DbField(name = "modified_by_id")`, you still pass `"modifiedBy"` here — Stormify
does the `property → column` translation internally.

=== "Kotlin"

    ```kotlin
    class User : AutoTable() {
        @DbField(primaryKey = true)
        var id: Int? = null
        var createdEntries: List<AuditEntry> by lazyDetails("createdBy")
        var modifiedEntries: List<AuditEntry> by lazyDetails("modifiedBy")
    }
    ```

=== "Java"

    ```java
    public class User extends AutoTable {
        @DbField(primaryKey = true)
        private Integer id;

        public List<AuditEntry> getCreatedEntries() {
            return stormify.getDetails(this, AuditEntry.class, "createdBy");
        }

        public List<AuditEntry> getModifiedEntries() {
            return stormify.getDetails(this, AuditEntry.class, "modifiedBy");
        }
    }
    ```

The value must be a **single field identifier** on the child side. The type-safe
form enforces this statically: `Paths.AuditEntry_.modifiedBy.name` produces a
`ScalarPath` and the `lazyDetails(ReferencePath)` overload rejects it at compile
time. The string form validates the same thing at query time — dotted forms like
`"user.address"` are rejected with a clear error. `lazyDetails` always follows
exactly one foreign key on the child side; if you need to traverse deeper, chain a
second read or write the query by hand.

For one-off access without declaring a delegate on the parent class, the same mechanism
is available as an imperative call — useful when you don't control the entity class or
want to keep the relationship out of the domain model. Both forms (type-safe and
string) are supported symmetrically:

```kotlin
// Auto-resolution — child type has exactly one FK back to Order
val items: List<OrderItem> = stormify.getDetails(order)
val items2: List<OrderItem> = order.details<OrderItem>()

// Type-safe with Paths
val items3: List<OrderItem> = stormify.getDetails(order, Paths.OrderItem_.order)
val items4: List<OrderItem> = order.details<OrderItem>(Paths.OrderItem_.order)

// String form with the Kotlin property name
val items5: List<OrderItem> = stormify.getDetails(order, "order")
val items6: List<OrderItem> = order.details<OrderItem>("order")
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
   and are marked populated, so subsequent field accesses on them don't touch the
   database at all.

The batch size of 32 is a compile-time constant chosen to keep the `IN` list small
enough for every supported dialect (including Oracle's 1,000-element limit) while still
collapsing the round-trip count by more than an order of magnitude. Groups larger than
32 members are simply drained in successive batches, each triggered by the next
unpopulated stub that gets touched.

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
