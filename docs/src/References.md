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

### Sibling Batch Optimization

When multiple `AutoTable` references of the same type are created during a single read
operation (e.g., many `Order` rows each referencing a `Customer`), those references are
grouped into a **sibling group**. When any one of them triggers `populate()`, all
siblings in the group are loaded in a single `SELECT ... WHERE id IN (...)` query.

If any of the requested IDs are no longer present in the database at the time of the
batch load, the whole call fails fast with an `SQLException("No data found for <table>
with ids [<missing>]")`. This surfaces stale references (rows deleted in another session
between when you obtained them and when you tried to access them) immediately instead of
silently leaving affected entities empty.

### Lazy Details (Child Records)

For parent-child relationships, the `lazyDetails` delegate loads child records on first access:

```kotlin
class Order : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var total: Double by db(0.0)
    var items: List<OrderItem> by lazyDetails()  // Loaded on first access
}
```
