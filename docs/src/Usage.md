# Usage

## Raw SQL

All query methods use `?` placeholders for parameters. When a parameter is a `Collection` or array, it is
automatically expanded (e.g., `WHERE id IN ?` with a list of 3 becomes `WHERE id IN (?, ?, ?)`).

Use `executeUpdate` for INSERT, UPDATE, DELETE, or DDL statements:

=== "Java"

    ```java
    int rowsAffected = stormify().executeUpdate("UPDATE test SET name = ? WHERE id = ?", "new name", 1);
    ```

=== "Kotlin"

    ```kotlin
    val rowsAffected = "UPDATE test SET name = ? WHERE id = ?".executeUpdate("new name", 1)
    ```

For raw SELECT queries, use `read`, `readOne`, or `readCursor` described below.

## Reading Data

### Read a List

Returns all matching rows as a list:

=== "Java"

    ```java
    List<Test> results = stormify().read(Test.class, "SELECT * FROM test WHERE name LIKE ?", "%Entry%");
    ```

=== "Kotlin"

    ```kotlin
    val results = "SELECT * FROM test WHERE name LIKE ?".read<Test>("%Entry%")
    ```

### Read a Single Row

Returns one result or `null`. Throws `QueryException` if multiple rows are found:

=== "Java"

    ```java
    Test result = stormify().readOne(Test.class, "SELECT * FROM test WHERE id = ?", 1);
    ```

=== "Kotlin"

    ```kotlin
    val result = "SELECT * FROM test WHERE id = ?".readOne<Test>(1)
    ```

### Cursor-Based Reading

For large result sets, use `readCursor` to process rows one at a time without loading them all into memory:

=== "Java"

    ```java
    int count = stormify().readCursor(Test.class, "SELECT * FROM test", row -> {
        System.out.println(row.getName());
    });
    ```

=== "Kotlin"

    ```kotlin
    val count = "SELECT * FROM test".readCursor<Test> { row ->
        println(row.name)
    }
    ```

### Reading by Multiple IDs

Pass a `Collection` or array as a parameter — the `?` placeholder is expanded automatically:

=== "Java"

    ```java
    List<Integer> ids = List.of(1, 2, 3);
    List<Test> results = stormify().read(Test.class, "SELECT * FROM test WHERE id IN ?", ids);
    ```

=== "Kotlin"

    ```kotlin
    val results = "SELECT * FROM test WHERE id IN ?".read<Test>(listOf(1, 2, 3))
    ```

### Convenience Query Methods

`findAll` and `findById` provide shortcuts for common queries:

=== "Java"

    ```java
    // Find all with a WHERE clause
    List<Test> filtered = stormify().findAll(Test.class, "WHERE name LIKE ?", "%Entry%");

    // Find all without filtering
    List<Test> all = stormify().findAll(Test.class, null);

    // Find by ID (returns null if not found)
    Test record = stormify().findById(Test.class, 1);
    ```

=== "Kotlin"

    ```kotlin
    val filtered = findAll<Test>("WHERE name LIKE ?", "%Entry%")
    val all = findAll<Test>()
    val record = findById<Test>(1)
    ```

## Populating Entities

If you have an entity with only its primary key set, you can load its remaining fields from the database:

=== "Java"

    ```java
    Test record = new Test();
    record.setId(1);
    stormify().populate(record);  // Loads name and other fields from DB
    ```

=== "Kotlin"

    ```kotlin
    val record = Test().apply { id = 1 }
    record.populate()
    ```

!!! note
    For `AutoTable` entities, `populate()` detaches the entity from any sibling batch group and loads it
    individually. See [AutoTable](Advanced_topics.md#autotable-lazy-loading) for details on batch loading.

## CRUD Operations

### Single Entity

=== "Java"

    ```java
    Test record = new Test();
    record.setId(1);
    record.setName("Entry");

    stormify().create(record);  // INSERT

    record.setName("Updated");
    stormify().update(record);  // UPDATE

    stormify().delete(record);  // DELETE
    ```

=== "Kotlin"

    ```kotlin
    val record = Test().apply { id = 1; name = "Entry" }

    record.create()

    record.name = "Updated"
    record.update()

    record.delete()
    ```

### Batch Operations

All CRUD methods accept collections for batch processing. Batch operations use JDBC batching for better performance.

=== "Java"

    ```java
    List<Test> items = List.of(record1, record2, record3);

    stormify().create(items);  // Batch insert
    stormify().update(items);  // Batch update
    stormify().delete(items);  // Batch delete
    ```

=== "Kotlin"

    ```kotlin
    val items = listOf(record1, record2, record3)

    stormify().create(items)
    stormify().update(items)
    stormify().delete(items)
    ```

!!! warning "Batch insert with auto-generated keys"
    When inserting multiple entities without pre-assigned IDs, the generated keys will **not** be populated
    back to the entities (JDBC does not guarantee key ordering for batch inserts). To reliably get generated
    IDs in batch inserts, use database sequences configured via `@DbField(primarySequence = "seq_name")`,
    which are fetched in bulk before the insert.

## Transaction Management

All operations inside a transaction share a single connection. If the block completes successfully, the transaction
is committed. If an exception is thrown, the transaction is rolled back.

### Basic Transaction

=== "Java"

    ```java
    stormify().transaction(() -> {
        Test record = new Test();
        record.setId(1);
        record.setName("Entry 1");
        stormify().create(record);

        record.setName("Updated Entry 1");
        stormify().update(record);
    });
    ```

=== "Kotlin"

    ```kotlin
    transaction {
        val record = Test().apply { id = 1; name = "Entry 1" }
        record.create()

        record.name = "Updated Entry 1"
        record.update()
    }
    ```

### Nested Transactions

Nested calls to `transaction()` use database savepoints. If an inner transaction fails, only the inner transaction is
rolled back to its savepoint; the outer transaction can continue.

=== "Java"

    ```java
    stormify().transaction(() -> {
        stormify().create(record1);

        try {
            stormify().transaction(() -> {
                stormify().create(record2); // If this fails...
            });
        } catch (QueryException e) {
            // ...only record2 is rolled back. record1 is still pending.
        }
    });
    // record1 is committed here.
    ```

=== "Kotlin"

    ```kotlin
    stormify().transaction {
        record1.create()

        try {
            stormify().transaction {
                record2.create() // If this fails...
            }
        } catch (e: QueryException) {
            // ...only record2 is rolled back. record1 is still pending.
        }
    }
    // record1 is committed here.
    ```

## Parent-Child Relationships

Use `getDetails` to retrieve child records of a parent entity:

=== "Java"

    ```java
    // Automatically finds the foreign key field in OrderItem that references Order
    List<OrderItem> items = stormify().getDetails(order, OrderItem.class);

    // Or specify the foreign key property name explicitly
    List<OrderItem> byField = stormify().getDetails(order, OrderItem.class, "parentOrder");
    ```

=== "Kotlin"

    ```kotlin
    val items = order.details<OrderItem>()
    val byField = order.details<OrderItem>("parentOrder")
    ```

!!! info
    The parent class must have exactly one primary key. If `propertyName` is not specified, Stormify finds
    the field in the details class whose type matches the parent class. If multiple fields match, or none
    matches, a `QueryException` is thrown.
