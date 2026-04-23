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

    Implement the `CRUDTable` marker interface on an entity to expose
    `create()`, `update()`, `delete()` as **instance methods** on the entity
    itself. This is the idiomatic choice from Java where Kotlin extension
    functions are not available.

    === "Java"

        ```java
        public class User implements CRUDTable {
            @DbField(primaryKey = true) private int id;
            private String name;
            // Getters, setters...
        }

        // Register a default instance once at startup:
        new StormifyJ(dataSource).asDefault();

        User u = new User();
        u.setName("Alice");
        u.create();      // INSERT
        u.setName("Bob");
        u.update();      // UPDATE
        u.delete();      // DELETE
        ```

    === "Kotlin"

        ```kotlin
        class User(var id: Int = 0, var name: String = "") : CRUDTable

        stormify.asDefault()

        val u = User(name = "Alice")
        u.create()
        u.name = "Bob"
        u.update()
        u.delete()
        ```

=== "Extension (Kotlin)"

    In Kotlin, **any** entity can call `create()`, `update()`, `delete()`, and
    `refresh()` directly — no interface needed. The query helpers (`findById`,
    `findAll`, `details`) are also available at top level.

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
