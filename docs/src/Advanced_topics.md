# Advanced Topics

## Transaction Management

Stormify provides support for managing database transactions, allowing you to group multiple operations into a single transaction. This is useful for ensuring data consistency and integrity, especially when dealing with complex operations that must all succeed or fail together.

### Managing Transactions

To perform operations within a transaction, use the `transaction` method. This method ensures that all included operations are committed if they succeed, or rolled back if any operation fails. Stormify also supports nested transactions, allowing you to manage transactions within transactions seamlessly.

#### Example

```java
import static onl.ycode.stormify.StormifyManager.stormify;

stormify().transaction(() -> {
    Test record1 = new Test();
    record1.setId(1);
    record1.setName("Entry 1");
    stormify().create(record1);

    stormify().transaction(() -> {
        Test record2 = new Test();
        record2.setId(2);
        record2.setName("Entry 2");
        stormify().create(record2);
    });
});
```

In this example, both the outer and inner transactions are managed independently. If any operation fails in the inner transaction, only the inner transaction will be rolled back, while the outer transaction can continue.

## Handling Auto-Increment Fields

Stormify can manage auto-increment fields automatically by leveraging database sequences or letting the database handle the generation of primary key values. You can specify this behavior using the `@DbField` annotation's `primaryKey` and `primarySequence` attributes.

### Using Sequences

If your database uses sequences for generating primary keys, you can specify the sequence name using the `primarySequence` attribute in the `@DbField` annotation.

#### Example

```java
import onl.ycode.stormify.DbField;

public class Test {
    @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
    private int id;

    private String name;

    // Getters and setters
}
```

In this example, the `id` field uses the sequence named `id_seq` to generate its values.

## Working with Composite Keys

Stormify supports tables with composite primary keys, allowing you to define multiple fields as part of the primary key.

### Defining Composite Keys

To define a composite key, simply mark all fields involved in the key as primary keys using any method of your choice (e.g., annotations, resolver function, etc.).

#### Example

```java
import onl.ycode.stormify.DbField;

public class CompositeKeyExample {
    @DbField(name = "key_part1", primaryKey = true)
    private int part1;

    @DbField(name = "key_part2", primaryKey = true)
    private int part2;

    private String data;

    // Getters and setters
}
```

In this example, both `part1` and `part2` fields form the composite primary key for the `CompositeKeyExample` class.

## Strict Mode vs. Lenient Mode

### Strict Mode

Strict mode enforces strict mapping between Java objects and database tables. When enabled, Stormify throws exceptions if fields are missing or do not match between the Java object and the database schema. This can be useful for ensuring data integrity and preventing accidental discrepancies.

#### Enabling Strict Mode

```java
stormify().setStrictMode(true);
```

### Lenient Mode

When strict mode is disabled, Stormify operates in lenient mode, logging warnings instead of throwing exceptions for mismatches between Java objects and database columns. This mode is useful for development or scenarios where flexibility is more important than strict validation.

#### Disabling Strict Mode

```java
stormify().setStrictMode(false);
```
