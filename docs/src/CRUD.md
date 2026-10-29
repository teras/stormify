# CRUD Operations

Stormify supports three styles for Create / Read / Update / Delete on entities. Pick whichever matches your language and taste — they all dispatch to the same underlying implementation and all honour an enclosing `transaction { }` block automatically.

## The Three Styles

=== "Direct"

    Pass entities to the `Stormify` (Kotlin) or `StormifyJ` (Java) instance directly.
    The standard form — works everywhere without any setup.

    === "Kotlin"

        ```kotlin
        val user = stormify.create(User(name = "Alice"))
        val users = stormify.read<User>("SELECT * FROM users")
        stormify.update(user)
        stormify.delete(user)
        ```

    === "Java"

        ```java
        User user = stormify.create(new User("Alice"));
        List<User> users = stormify.read(User.class, "SELECT * FROM users");
        stormify.update(user);
        stormify.delete(user);
        ```

=== "CRUDTable (Java-friendly)"

    Implement `CRUDTable<T>`, with the entity class itself as `T`, to expose
    `create()`, `update()`, `delete()` as **instance methods** on the entity
    itself; `create()` and `update()` return the entity, as `stormify.create(user)`
    does. This is the idiomatic choice from Java where Kotlin extension
    functions are not available.

    === "Java"

        ```java
        public class User implements CRUDTable<User> {
            @DbField(primaryKey = true) private int id;
            private String name;
            // Getters, setters...
        }

        // Register a default instance once at startup:
        new StormifyJ(dataSource).asDefault();

        User u = new User();
        u.setName("Alice");
        u = u.create();  // INSERT, returns the entity with its generated key
        u.setName("Bob");
        u.update();      // UPDATE
        u.delete();      // DELETE
        ```

    === "Kotlin"

        ```kotlin
        class User(var id: Int = 0, var name: String = "") : CRUDTable<User>

        stormify.asDefault()

        val u = User(name = "Alice").create()
        u.name = "Bob"
        u.update()
        u.delete()
        ```

=== "Extension (Kotlin)"

    In Kotlin, **any** entity can call `create()`, `update()`, and `delete()`
    directly — no interface needed. The query helpers (`findById`, `findAll`,
    `details`) are also available at top level. Reloading an entity from the
    database is done via `stormify.refresh(entity)` — there is no extension
    `refresh()`.

    ```kotlin
    stormify.asDefault()

    val user = User(name = "Alice").create()       // INSERT, returns the created entity
    user.name = "Bob"
    user.update()                                   // UPDATE
    user.delete()                                   // DELETE

    val byId = findById<User>(42)
    val active = findAll<User>("WHERE status = ?", "active")
    val lines = order.details<OrderItem>()          // Parent-child query
    ```

## Transaction Participation

All three styles transparently join an enclosing `transaction { }` block — every
call on the same `Stormify` instance inside the block shares one connection and
rolls back together on exception. See [Transactions](Transactions.md) for
details.

```kotlin
stormify.transaction {
    stormify.create(User(name = "Alice"))          // Direct style
    User(name = "Bob").create()                    // Extension style
    someEntity.update()                            // CRUDTable style
}
```

## Batch CRUD Operations

Pass a collection to `create`, `update`, or `delete` to operate on many entities at once:

=== "Kotlin"

    ```kotlin
    val users = listOf(User(name = "Alice"), User(name = "Bob"), User(name = "Carol"))
    stormify.create(users)     // Batch INSERT
    stormify.update(users)     // Batch UPDATE
    stormify.delete(users)     // Batch DELETE
    ```

=== "Java"

    ```java
    List<User> users = List.of(new User("Alice"), new User("Bob"), new User("Carol"));
    stormify.create(users);
    stormify.update(users);
    stormify.delete(users);
    ```

!!! note "Generated keys in batch insert"
    When batch-inserting entities with auto-generated keys, the generated key is populated
    back to the entity **only if a single item** is inserted. For batch inserts, use
    database sequences (`primarySequence`) instead of auto-increment to ensure keys are
    assigned before insertion.

## Updating Only Changed Columns

By default `update` writes every updatable column of the entity. Set
`dynamicUpdate` on the instance to write only the columns that changed since the
entity was loaded:

=== "Kotlin"

    ```kotlin
    stormify.dynamicUpdate = true

    val user = stormify.findById<User>(42)!!
    user.email = "new@example.com"
    stormify.update(user)          // UPDATE users SET email = ? WHERE id = ?
    stormify.update(user)          // nothing changed since the last update: no statement
    ```

=== "Java"

    ```java
    stormify.setDynamicUpdate(true);

    User user = stormify.findById(User.class, 42);
    user.setEmail("new@example.com");
    stormify.update(user);         // UPDATE users SET email = ? WHERE id = ?
    ```

- **Which entities.** Only entities that extend `StormifyEntity` (and so `AutoTable`)
  keep the loaded values. Other entities, and entities that were never loaded while
  `dynamicUpdate` was on, get the full `UPDATE`. After an update the written values
  become the reference for the next one.
- **Batches.** A batch `update` groups the entities by the set of changed columns and
  runs one batched statement per group, on one connection.
- **Mutable values.** The loaded values are copied, so a value changed in place
  (a byte array, or a `java.util.Date` through `setTime`) counts as a change.
- **Cost.** Each loaded entity keeps a second copy of its column values.
- **Transactions.** Inside `transaction { }` the written values become the reference
  only on commit. When the transaction, or the savepoint of a nested one, rolls back,
  the entities it updated lose their reference and their next update writes every
  column, also when `dynamicUpdate` was switched on inside the transaction. On a
  connection whose transaction is managed outside Stormify, the reference is not
  replaced, since the statement may still be rolled back there; the next update writes
  the changed columns again.
- **Primary key.** An entity whose primary key changed gets the full `UPDATE`.
- **Partial reads.** The reference is taken from the object as loaded, not from the
  row. A field that the `SELECT` did not read keeps its default value, and as long as
  it still equals that default it is not written.
- **No statement, no check.** An entity with no changed column issues no statement,
  so nothing checks that its row still exists.
