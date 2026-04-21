# Raw Queries

For situations where the high-level ORM API is not enough — reporting queries, dynamic
SQL, stored procedures — Stormify exposes a low-level SQL API that works on any platform
with no entity registration required. This page covers the patterns you use alongside the
plain `read` / `executeUpdate` methods.

## Reading and Executing SQL

The direct methods take a query string and bind parameters positionally:

=== "Direct"

    === "Kotlin"

        ```kotlin
        val users = stormify.read<User>("SELECT * FROM users WHERE age > ?", 25)
        val one = stormify.readOne<User>("SELECT * FROM users WHERE id = ?", 1)

        stormify.readCursor<User>("SELECT * FROM users") { user -> processUser(user) }

        stormify.executeUpdate("DELETE FROM users WHERE age < ?", 18)
        ```

    === "Java"

        ```java
        List<User> users = stormify.read(User.class, "SELECT * FROM users WHERE age > ?", 25);
        User one = stormify.readOne(User.class, "SELECT * FROM users WHERE id = ?", 1);

        stormify.readCursor(User.class, "SELECT * FROM users", user -> processUser(user));

        stormify.executeUpdate("DELETE FROM users WHERE age < ?", 18);
        ```

=== "Extension"

    ```kotlin
    // With a default Stormify instance registered via stormify.asDefault(),
    // SQL strings gain read / readOne / readCursor / executeUpdate extensions.
    val users = "SELECT * FROM users WHERE age > ?".read<User>(25)
    val one = "SELECT * FROM users WHERE id = ?".readOne<User>(1)

    "SELECT * FROM users".readCursor<User> { user -> processUser(user) }

    "DELETE FROM users WHERE age < ?".executeUpdate(18)
    ```

## Collection Parameter Expansion

When a `?` placeholder receives a `Collection` or array, Stormify automatically expands
it into multiple placeholders:

=== "Kotlin"

    ```kotlin
    val ids = listOf(1, 2, 3)
    val users = stormify.read<User>("SELECT * FROM users WHERE id IN ?", ids)
    // Expands to: SELECT * FROM users WHERE id IN (?, ?, ?)
    ```

=== "Java"

    ```java
    List<Integer> ids = List.of(1, 2, 3);
    List<User> users = stormify.read(User.class, "SELECT * FROM users WHERE id IN ?", ids);
    // Expands to: SELECT * FROM users WHERE id IN (?, ?, ?)
    ```

This also works with entity collections — Stormify extracts each entity's primary key
automatically:

=== "Kotlin"

    ```kotlin
    val admins = stormify.findAll<User>("WHERE role = ?", "admin")
    val tasks = stormify.read<Task>("SELECT * FROM task WHERE user_id IN ?", admins)
    // Expands to: SELECT * FROM task WHERE user_id IN (?, ?, ...) with user PKs
    ```

=== "Java"

    ```java
    List<User> admins = stormify.findAll(User.class, "WHERE role = ?", "admin");
    List<Task> tasks = stormify.read(Task.class, "SELECT * FROM task WHERE user_id IN ?", admins);
    // Expands to: SELECT * FROM task WHERE user_id IN (?, ?, ...) with user PKs
    ```

This works with any `Iterable` or array type.

## Stored Procedures

Stormify supports calling stored procedures with IN, OUT, and INOUT parameters:

=== "Direct"

    === "Kotlin"

        ```kotlin
        val count = spOut<Int>()
        val msg = spOut<String>()
        stormify.procedure("tally", 42, count, msg)
        println("count=${count.value}, msg=${msg.value}")
        ```

    === "Java"

        ```java
        Sp.Out<Integer> count = Sp.outParam(Integer.class);
        Sp.Out<String> msg = Sp.outParam(String.class);
        stormify.procedure("tally", 42, count, msg);
        System.out.println("count=" + count.getValue() + ", msg=" + msg.getValue());
        ```

=== "Extension"

    ```kotlin
    // With a default Stormify instance, the procedure name itself is callable.
    val count = spOut<Int>()
    val msg = spOut<String>()
    "tally".procedure(42, count, msg)
    ```

Parameter types:

| Type | Kotlin | Java |
|------|--------|------|
| Input | `spIn(value)` or raw value | `Sp.In(value)` or raw value |
| Output | `spOut<T>()` | `Sp.outParam(Type.class)` |
| Bidirectional | `spInOut(value)` | `Sp.inOutParam(Type.class, value)` |
