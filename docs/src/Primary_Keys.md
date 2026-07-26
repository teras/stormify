# Primary Keys

Stormify supports simple primary keys, composite keys, and auto-generated keys. You can
mark primary-key fields explicitly with annotations, or register a resolver that
identifies them by naming convention.

## Custom Primary Key Resolvers

Primary keys in databases commonly follow a naming convention. If this is the case, instead of using annotations, you can register custom primary key resolvers to help Stormify identify primary keys based on their name.

### How It Works

To set up a primary key resolver, use the `registerPrimaryKeyResolver` method. You provide:

- **Priority**: A numeric value that determines evaluation order when multiple resolvers are registered. Lower values are evaluated first, and the first resolver returning `true` marks the field as a primary key (resolvers are combined with OR, so all of them may be consulted until one matches).
- **Resolver Function**: A function that receives the table name and field name, and returns `true` if the field should be treated as a primary key.

Resolvers are only consulted for entities that declare **no** primary-key annotation at all — if any field carries `@Id` or `@DbField(primaryKey = true)`, annotations alone decide the primary keys and resolvers are ignored for that entity.

### Example

=== "Kotlin"

    ```kotlin
    // Register a resolver that identifies fields named "id" as primary keys
    stormify.registerPrimaryKeyResolver(10) { tableName, fieldName ->
        fieldName.equals("id", ignoreCase = true)
    }
    ```

=== "Java"

    ```java
    // Register a resolver that identifies fields named "id" as primary keys
    stormify.registerPrimaryKeyResolver(10, (tableName, fieldName) ->
        fieldName.equalsIgnoreCase("id")
    );
    ```

In this example:

- The resolver checks if the field name is `id`, a common but not universal pattern.
- The priority `10` determines where it sits relative to other registered resolvers — lower priorities are evaluated first. There is no built-in default resolver; without a registration (and without annotations) no field is auto-detected as a primary key.

By setting up custom primary key resolvers, Stormify can accurately identify primary keys without relying on annotations for every class.

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
