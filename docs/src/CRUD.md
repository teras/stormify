# CRUD Operations

Stormify supports three styles for performing basic Create / Read / Update / Delete
operations on entities. Pick whichever matches your language and taste — they all
dispatch to the same underlying implementation.

## The Three Styles

=== "Direct"

    Pass entities to the `Stormify` (Kotlin) or `StormifyJ` (Java) instance directly.
    This is the standard form and works everywhere without any extra setup.

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

=== "CRUDTable"

    Implement the `CRUDTable` marker interface on an entity to gain `create()`,
    `update()`, and `delete()` methods on the entity itself. This is especially useful
    from Java, where extension functions are not available.

    === "Java"

        ```java
        public class User implements CRUDTable {
            @DbField(primaryKey = true)
            private int id;
            private String name;
            // Getters and setters
        }

        User user = new User();
        user.setName("Alice");
        user.create();       // INSERT
        user.update();       // UPDATE
        user.delete();       // DELETE
        ```

    === "Kotlin"

        ```kotlin
        class User : CRUDTable {
            @DbField(primaryKey = true)
            var id: Int = 0
            var name: String = ""
        }

        val user = User().apply { name = "Alice" }
        user.create()
        user.update()
        user.delete()
        ```

=== "Extension"

    In Kotlin, **any** entity can call `create()`, `update()`, `delete()`, and
    `refresh()` directly — no interface needed. The query helpers (`findById`,
    `findAll`, `details`) are also available at top level.

    ```kotlin
    val user = User(name = "Alice").create()       // INSERT, returns the created entity
    user.name = "Bob"
    user.update()                                   // UPDATE
    user.delete()                                   // DELETE

    val byId = findById<User>(42)
    val active = findAll<User>("WHERE status = ?", "active")
    val lines = order.details<OrderItem>()          // Parent-child query
    ```

## Default Instance

The **CRUDTable** and **Extension** styles operate without an explicit `Stormify`
reference. They require a *default instance*, registered once at startup:

=== "Kotlin"

    ```kotlin
    val stormify = Stormify(dataSource)
    stormify.asDefault()
    ```

=== "Java"

    ```java
    StormifyJ stormify = new StormifyJ(dataSource);
    stormify.asDefault();
    ```

The same default instance is picked up by [AutoTable lazy stubs](References.md#fresh-construction-vs-lazy-stubs)
and [`PagedList`](PagedList.md#quick-start) when no instance is explicitly attached.

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
