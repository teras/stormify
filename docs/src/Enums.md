# Enum Properties

Enum fields are stored as integers by default and converted back to enum constants when reading. Enums can also be used as query parameters.

## Basic Usage

=== "Kotlin"

    ```kotlin
    enum class Status { ACTIVE, INACTIVE, BANNED }

    data class User(
        @DbField(primaryKey = true) var id: Int = 0,
        var name: String = "",
        var status: Status? = null  // stored as 0, 1, 2 (ordinal)
    )

    // DDL: status INTEGER
    ```

=== "Java"

    ```java
    public enum Status { ACTIVE, INACTIVE, BANNED }

    public class User {
        @DbField(primaryKey = true)
        private int id;
        private String name;
        private Status status;  // stored as 0, 1, 2 (ordinal)
        // getters and setters
    }
    ```

Enum values work seamlessly as query parameters:

=== "Kotlin"

    ```kotlin
    // Single parameter
    val banned = stormify.findAll<User>("WHERE status = ?", Status.BANNED)

    // Collection parameter (IN clause)
    val filtered = stormify.read<User>(
        "SELECT * FROM user WHERE status IN ?",
        listOf(Status.ACTIVE, Status.BANNED)
    )
    ```

=== "Java"

    ```java
    List<User> banned = stormify.findAll(User.class, "WHERE status = ?", Status.BANNED);

    List<User> filtered = stormify.read(User.class,
        "SELECT * FROM user WHERE status IN ?",
        List.of(Status.ACTIVE, Status.BANNED));
    ```

## Custom Integer Values with `DbValue`

By default, enums are stored as their ordinal (position index). If you need stable integer
values that don't change when entries are reordered, implement the `DbValue` interface:

=== "Kotlin"

    ```kotlin
    enum class Priority(override val dbValue: Int) : DbValue {
        LOW(10),
        MEDIUM(20),
        HIGH(30)
    }
    ```

=== "Java"

    ```java
    public enum Priority implements DbValue {
        LOW(10), MEDIUM(20), HIGH(30);

        private final int dbValue;
        Priority(int dbValue) { this.dbValue = dbValue; }

        @Override
        public int getDbValue() { return dbValue; }
    }
    ```

With `DbValue`, `Priority.HIGH` is stored as `30` in the database instead of ordinal `2`.

## String Storage with `enumAsString`

To store the enum name as a string instead of an integer, use `@DbField(enumAsString = true)` or the JPA `@Enumerated(EnumType.STRING)` annotation:

=== "Kotlin"

    ```kotlin
    data class User(
        @DbField(primaryKey = true) var id: Int = 0,
        @DbField(enumAsString = true)
        var status: Status? = null  // stored as "ACTIVE", "INACTIVE", "BANNED"
    )

    // DDL: status VARCHAR(50) or TEXT
    ```

=== "Java"

    ```java
    public class User {
        @DbField(primaryKey = true)
        private int id;

        @DbField(enumAsString = true)
        private Status status;  // stored as "ACTIVE", "INACTIVE", "BANNED"
        // getters and setters
    }
    ```

You can mix ordinal and string fields in the same entity. The storage mode is per-field.
String matching is **case-insensitive** — a database value of `"active"`, `"ACTIVE"`, or `"Active"` all resolve to the same enum constant.

## Unknown Database Values

When the database contains a value that doesn't match any enum constant:

- **Nullable fields** (`Status?`): receive `null`
- **Non-null fields** (`Status`): throw an exception
