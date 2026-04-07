# Advanced Topics

## Transaction Management

Stormify provides support for managing database transactions, allowing you to group multiple operations into a single transaction. This ensures data consistency and integrity, especially when dealing with complex operations that must all succeed or fail together.

### Managing Transactions

Use the `transaction` method to group operations. All included operations are committed if they succeed, or rolled back if any operation fails. Stormify also supports nested transactions through savepoints.

#### Basic Transaction Example

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        val user = create(User(email = "test@example.com"))
        create(Profile(userId = user.id, name = "Test User"))
        update(account)
    }
    ```

=== "Java"

    ```java
    stormify.transaction(tx -> {
        User user = tx.create(new User("test@example.com"));
        tx.create(new Profile(user.getId(), "Test User"));
        tx.update(account);
    });
    ```

### Nested Transactions

Nested transactions use database savepoints. If an inner transaction fails, only operations within that savepoint are rolled back.

=== "Kotlin"

    ```kotlin
    stormify.transaction {
        create(record1)

        transaction {  // Creates a savepoint
            create(record2)
            // If this fails, only record2 is rolled back
        }

        create(record3)  // This still executes
    }
    ```

=== "Java"

    ```java
    stormify.transaction(tx -> {
        tx.create(record1);

        tx.transaction(() -> {  // Creates a savepoint
            tx.create(record2);
            // If this fails, only record2 is rolled back
        });

        tx.create(record3);  // This still executes
    });
    ```

### Extracting Transaction Logic

For complex business logic, you can extract operations into reusable functions:

#### Pattern 1: Extension Functions (Kotlin)

Extension functions on `TransactionContext` provide the cleanest syntax:

```kotlin
fun TransactionContext.registerUser(email: String, name: String) {
    val user = create(User(email = email))
    create(Profile(userId = user.id, name = name))
    create(AuditLog(action = "User registered", userId = user.id))
}

fun TransactionContext.transferFunds(from: Account, to: Account, amount: Double) {
    require(from.balance >= amount) { "Insufficient funds" }
    update(from.copy(balance = from.balance - amount))
    update(to.copy(balance = to.balance + amount))
    create(Transaction(fromId = from.id, toId = to.id, amount = amount))
}

// Usage
stormify.transaction {
    registerUser("alice@example.com", "Alice")
    transferFunds(accountA, accountB, 100.0)
}
```

#### Pattern 2: Service Layer Classes

For more structured applications, encapsulate transaction logic in service classes:

=== "Kotlin"

    ```kotlin
    class UserService(private val tx: TransactionContext) {
        fun registerUser(email: String, name: String) {
            val user = tx.create(User(email = email))
            tx.create(Profile(userId = user.id, name = name))
        }
    }

    stormify.transaction {
        val userService = UserService(this)
        userService.registerUser("alice@example.com", "Alice")
    }
    ```

=== "Java"

    ```java
    class UserService {
        private final TransactionContextJ tx;

        UserService(TransactionContextJ tx) { this.tx = tx; }

        void registerUser(String email, String name) {
            User user = tx.create(new User(email));
            tx.create(new Profile(user.getId(), name));
        }
    }

    stormify.transaction(tx -> {
        UserService userService = new UserService(tx);
        userService.registerUser("alice@example.com", "Alice");
    });
    ```

Both patterns ensure that all operations share the same database connection and participate in the same transaction.

## Handling Auto-Increment Fields

Stormify can manage auto-increment fields automatically by leveraging database sequences or letting the database handle the generation of primary key values. You can specify this behavior using the `@DbField` annotation's `primaryKey` and `primarySequence` attributes.

### Using Sequences

If your database uses sequences for generating primary keys, you can specify the sequence name using the `primarySequence` attribute in the `@DbField` annotation.

#### Example

=== "Kotlin"

    ```kotlin
    data class User(
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        var id: Int = 0,
        var name: String = ""
    )
    ```

=== "Java"

    ```java
    public class User {
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        private int id;
        private String name;

        // getters and setters
    }
    ```

In this example, the `id` field uses the sequence named `id_seq` to generate its values.

## Working with Composite Keys

Stormify supports tables with composite primary keys, allowing you to define multiple fields as part of the primary key.

### Defining Composite Keys

To define a composite key, mark all fields involved in the key as primary keys using annotations or a resolver function.

#### Example

=== "Kotlin"

    ```kotlin
    data class CompositeKeyExample(
        @DbField(name = "key_part1", primaryKey = true)
        var part1: Int = 0,

        @DbField(name = "key_part2", primaryKey = true)
        var part2: Int = 0,

        var data: String = ""
    )
    ```

=== "Java"

    ```java
    public class CompositeKeyExample {
        @DbField(name = "key_part1", primaryKey = true)
        private int part1;

        @DbField(name = "key_part2", primaryKey = true)
        private int part2;

        private String data;

        // getters and setters
    }
    ```

In this example, both `part1` and `part2` fields form the composite primary key.

## Strict Mode vs. Lenient Mode

### Strict Mode

Strict mode enforces strict mapping between classes and database tables. When enabled, Stormify throws exceptions if fields are missing or do not match between the class and the database schema.

By default, Stormify operates in strict mode.

=== "Kotlin"

    ```kotlin
    stormify.isStrictMode = true
    ```

=== "Java"

    ```java
    stormify.setStrictMode(true);
    ```

### Lenient Mode

When strict mode is disabled, Stormify operates in lenient mode, logging warnings instead of throwing exceptions for mismatches between classes and database columns. This mode is useful for development or scenarios where flexibility is more important than strict validation.

=== "Kotlin"

    ```kotlin
    stormify.isStrictMode = false
    ```

=== "Java"

    ```java
    stormify.setStrictMode(false);
    ```
