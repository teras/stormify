# Advanced Topics

## Transaction Management

Stormify provides support for managing database transactions, allowing you to group multiple operations into a single transaction. This is useful for ensuring data consistency and integrity, especially when dealing with complex operations that must all succeed or fail together.

### Managing Transactions

To perform operations within a transaction, use the `transaction` method. This method ensures that all included operations are committed if they succeed, or rolled back if any operation fails. Stormify also supports nested transactions through savepoints, allowing you to manage transactions within transactions seamlessly.

#### Basic Transaction Example

```kotlin
val stormify = Stormify(dataSource)

stormify.transaction {
    val user = create(User(email = "test@example.com"))
    create(Profile(userId = user.id, name = "Test User"))
    update(account)
}
```

### Nested Transactions

Nested transactions use database savepoints. If an inner transaction fails, only operations within that savepoint are rolled back.

#### Example

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

### Extracting Transaction Logic

For complex business logic, you can extract operations into reusable functions using two patterns:

#### Pattern 1: Extension Functions (Recommended)

Extension functions on `TransactionContext` provide the cleanest syntax:

```kotlin
// Define extension functions for your business logic
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

// Usage - clean and readable
stormify.transaction {
    registerUser("alice@example.com", "Alice")
    transferFunds(accountA, accountB, 100.0)
}
```

#### Pattern 2: Service Layer Classes

For more structured applications, encapsulate transaction logic in service classes:

```kotlin
class UserService(private val tx: TransactionContext) {
    fun registerUser(email: String, name: String) {
        val user = tx.create(User(email = email))
        tx.create(Profile(userId = user.id, name = name))
        tx.create(AuditLog("User registered", user.id))
    }

    fun deleteUser(userId: Int) {
        val user = tx.findById<User>(userId)
        tx.delete(user)
        tx.create(AuditLog("User deleted", userId))
    }
}

class AccountService(private val tx: TransactionContext) {
    fun transferFunds(fromId: Int, toId: Int, amount: Double) {
        val from = tx.findById<Account>(fromId)
        val to = tx.findById<Account>(toId)
        require(from.balance >= amount) { "Insufficient funds" }

        tx.update(from.copy(balance = from.balance - amount))
        tx.update(to.copy(balance = to.balance + amount))
        tx.create(Transaction(fromId = fromId, toId = toId, amount = amount))
    }
}

// Usage - organized service layer
stormify.transaction {
    val userService = UserService(this)
    val accountService = AccountService(this)

    userService.registerUser("alice@example.com", "Alice")
    accountService.transferFunds(1, 2, 100.0)
}
```

Both patterns ensure that all operations share the same database connection and participate in the same transaction.

## Handling Auto-Increment Fields

Stormify can manage auto-increment fields automatically by leveraging database sequences or letting the database handle the generation of primary key values. You can specify this behavior using the `@DbField` annotation's `primaryKey` and `primarySequence` attributes.

### Using Sequences

If your database uses sequences for generating primary keys, you can specify the sequence name using the `primarySequence` attribute in the `@DbField` annotation.

#### Example

```kotlin
import onl.ycode.stormify.DbField

data class User(
    @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
    var id: Int = 0,
    var name: String = ""
)
```

In this example, the `id` field uses the sequence named `id_seq` to generate its values.

## Working with Composite Keys

Stormify supports tables with composite primary keys, allowing you to define multiple fields as part of the primary key.

### Defining Composite Keys

To define a composite key, simply mark all fields involved in the key as primary keys using any method of your choice (e.g., annotations, resolver function, etc.).

#### Example

```kotlin
import onl.ycode.stormify.DbField

data class CompositeKeyExample(
    @DbField(name = "key_part1", primaryKey = true)
    var part1: Int = 0,

    @DbField(name = "key_part2", primaryKey = true)
    var part2: Int = 0,

    var data: String = ""
)
```

In this example, both `part1` and `part2` fields form the composite primary key for the `CompositeKeyExample` class.

## Strict Mode vs. Lenient Mode

### Strict Mode

Strict mode enforces strict mapping between Kotlin classes and database tables. When enabled, Stormify throws exceptions if fields are missing or do not match between the class and the database schema. This is useful for ensuring data integrity and preventing accidental discrepancies.

By default, Stormify operates in strict mode.

#### Enabling Strict Mode

```kotlin
val stormify = Stormify(dataSource)
stormify.isStrictMode = true
```

### Lenient Mode

When strict mode is disabled, Stormify operates in lenient mode, logging warnings instead of throwing exceptions for mismatches between classes and database columns. This mode is useful for development or scenarios where flexibility is more important than strict validation.

#### Disabling Strict Mode

```kotlin
stormify.isStrictMode = false
```
