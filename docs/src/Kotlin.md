# Kotlin Integration

The `kotlin` module provides idiomatic Kotlin extensions for Stormify. Add it alongside the `db` module:

```kotlin
implementation("onl.ycode.stormify:db:1.2.0")
implementation("onl.ycode.stormify:kotlin:1.2.0")
```

All extensions are in the `onl.ycode.stormify` package.

## Extension Functions Overview

The Kotlin module adds extension functions so you can call Stormify operations directly on objects and strings,
without calling `stormify()` explicitly. These extensions mirror the Java API documented in
[Usage](Usage.md) and [Advanced Topics](Advanced_topics.md), using Kotlin idioms.

### SQL on Strings

```kotlin
"UPDATE user SET active = ?".executeUpdate(true)
"SELECT * FROM user".read<User>()
"SELECT * FROM user WHERE id = ?".readOne<User>(1)
"SELECT * FROM user".readCursor<User> { println(it.name) }
"my_procedure".storedProcedure(IN(42), OUT<String>())
```

### CRUD on Objects

```kotlin
user.create()       // stormify().create(user)
user.update()       // stormify().update(user)
user.delete()       // stormify().delete(user)
user.populate()     // stormify().populate(user)
```

### Query Helpers

```kotlin
findAll<User>()                              // All rows
findAll<User>("WHERE active = ?", true)      // With filter
findById<User>(1)                            // By primary key
customer.details<Order>()                    // Child records
```

### Transactions

```kotlin
transaction {
    // All operations here share a single connection
    // Nested transaction {} calls use savepoints
}
```

### Stored Procedure Helpers

The `IN()`, `OUT()`, and `INOUT()` functions use reified type parameters, so you don't need to pass the class:

```kotlin
val output = OUT<String>()           // instead of SPParam.out(String.class)
IN(42)                               // instead of SPParam.in(Integer.class, 42)
INOUT("value")                       // instead of SPParam.inout(String.class, "value")
```

## Property Delegation

These features are unique to Kotlin and have no Java equivalent.

### Auto-Populating Properties

The `db` delegate auto-populates an entity from the database on first property access. Use it together
with `AutoTable` so that the entity is loaded only once (via the `isDirty` flag):

```kotlin
class User : AutoTable() {
    @DbField(primaryKey = true)
    var id: Int? = null
    var name: String by db("")    // Loaded from DB on first get or set
}
```

When `user.name` is accessed, `stormify().populate(user)` is called automatically. This is the Kotlin-idiomatic
replacement for manually calling `autoPopulate()` in every getter and setter.

!!! warning
    The `db` delegate requires extending `AutoTable`. Without it, there is no `isDirty` flag to track
    whether the entity has already been loaded, so every property access would trigger a database query.

### Lazy Details

The `lazyDetails` delegate loads child records on first access:

```kotlin
class Order {
    var id: Int = 0
    var items: List<OrderItem> by lazyDetails()               // Auto-detect FK field
    var notes: List<Note> by lazyDetails("orderRef")          // Explicit FK property name
}

val order = findById<Order>(1)
println(order.items)   // First access triggers: SELECT * FROM order_item WHERE order_id = ?
println(order.items)   // Subsequent access returns cached result
```

The list is loaded once and cached. You can also assign a value directly to skip the lazy load.

## Table Name Access

Get the mapped database table name for any entity class:

```kotlin
val tableName = User::class.db  // e.g., "user" (with default snake_case policy)
```
