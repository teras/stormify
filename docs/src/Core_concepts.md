# Core Concepts

## POJO Mapping and Requirements

Stormify maps Plain Old Java Objects (POJOs) to database tables using field names that match the corresponding database
column names. This approach minimizes the need for extensive configurations or annotations, allowing you to work
directly with your Java objects.

### Field Name Matching

- **Automatic Mapping**: Fields in your Java classes are automatically mapped to database columns with matching names.
  No annotations are required as long as the field names correspond to the column names.
- **Optional Annotations**: You can use the `@DbTable` and `@DbField` annotations to provide additional information or
  to customize the mapping between your Java classes and the database.

### Naming Policy

Stormify provides flexible naming policies to convert class names to table names and field names to column names,
ensuring consistency across your database schema. You can set the naming policy using the `setNamingPolicy` method in
the Stormify manager. By default, the naming policy is set to `LOWER_CASE_WITH_UNDERSCORES` (snake_case). This policy
will only affect tables and fields that are not already registered.

#### NamingPolicy Enum Options

1. **CAMEL_CASE**
    - **Description**: Uses class and field names as they are, preserving camel case.
    - **Example**: A class named `UserAccount` remains `UserAccount`, and a field named `userName` remains `userName`.

2. **LOWER_CASE_WITH_UNDERSCORES**
    - **Description**: Converts class and field names to lower case with underscores (snake_case).
    - **Example**: A class named `UserAccount` becomes `user_account`, and a field named `userName` becomes `user_name`.

3. **UPPER_CASE_WITH_UNDERSCORES**
    - **Description**: Converts class and field names to upper case with underscores (SCREAMING_SNAKE_CASE).
    - **Example**: A class named `UserAccount` becomes `USER_ACCOUNT`, and a field named `userName` becomes `USER_NAME`.

### Custom Primary Key Resolvers

The primary keys in the databases, commonly follow a naming convention. If this is the case, instead of using
annotations, you can register custom primary key resolvers to help Stormify identify primary keys based on their name.

#### How It Works

To set up a primary key resolver, use the `registerPrimaryKeyResolver` method. You provide:

- **Priority**: Determines which resolver is used first if multiple are registered. Higher values mean higher priority.
- **Resolver Function**: A simple function that checks the table and field names to decide if a field is a primary key.

#### Simple Example

If your primary keys follow a specific naming pattern, you can register a resolver that uses your own criteria. For
instance:

```java
    stormifyManager.registerPrimaryKeyResolver(10,(tableName, fieldName) -> fieldName.equalsIgnoreCase("id"));
```

In this example:

- The resolver simply checks if the field name is `id`, a common but not universal pattern.
- The priority is set to `10`, indicating this resolver should be checked early.

By setting up custom primary key resolvers, Stormify can accurately identify primary keys, without relying on
annotations for every class.

## Annotations

### `@DbTable` Annotation

The `@DbTable` annotation is used to specify the database table name associated with a class. This annotation is
optional and is only needed if the table name differs from the class name.

#### Attributes

- **`name`**: Specifies the name of the table in the database. If not provided, the class name will be used, converted
  using the current naming policy.

#### Example

```java
import onl.ycode.stormify.DbTable;

@DbTable(name = "custom_table_name")
public class Test {
    private int id;
    private String name;

    // Getters and setters
}
```

In this example, the `Test` class maps to the `custom_table_name` table in the database.

### `@DbField` Annotation

The `@DbField` annotation is used to provide additional information about a specific field in a class. This annotation
is optional and allows you to customize how fields are mapped to database columns.

#### Attributes

- **`name`**: Specifies the name of the field in the database. If not provided, the field name in the class will be
  used, converted using the current naming policy.
- **`primaryKey`**: Indicates whether the field is a primary key. Defaults to `false`.
- **`primarySequence`**: Specifies the name of the primary key sequence in the database. If not provided, the primary
  key value generation relies on the database.
- **`creatable`**: Determines whether the field can be used when creating a new record. Defaults to `true`.
- **`updatable`**: Determines whether the field can be used when updating a record. Defaults to `true`.

#### Example

```java
import onl.ycode.stormify.DbField;

public class Test {
    @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
    private int id;

    @DbField(creatable = false, updatable = true)
    private String name;

    // Getters and setters
}
```

In this example:

- The `id` field is mapped to the `custom_id` column, marked as a primary key, and uses a sequence named `id_seq`.
- The `name` field is configured to be updatable but not creatable.

### Other supported Annotations

Stormify provides support for several standard annotations from the `javax.persistence` package, making it easy to
integrate with existing Java applications that use these familiar annotations. The following annotations are supported:

- **`@Id`**: Marks a field as the primary key of the entity.

- **`@Table`**: Specifies the table in the database that maps to the entity. Stormify uses the table name from
  this annotation to map your classes to the corresponding database tables.

- **`@Column`**: Maps a field to a specific column in the database table. Stormify retrieves the column name to
  map fields to the correct database columns, focusing primarily on the name attribute.

- **`@JoinColumn`**: Specifies the column used for joining an entity association, typically used with relationships
  like `@ManyToOne` or `@OneToOne`. Again, Stormify focuses only on the name attribute.

- **`@SequenceGenerator`**: Defines a primary key generator that uses a database sequence, allowing you to control how
  IDs
  are generated for new entities.

### Note

These annotations help bridge the gap between your Java objects and database schema, enabling a smooth and familiar
mapping experience with Stormify. By leveraging these standard annotations, Stormify ensures compatibility with existing
JPA setups while providing additional flexibility.

## Blacklist Management

Stormify includes a feature to manage fields that should be ignored during database interactions. This is useful when
you want to exclude certain fields from being creatable, updated, or retrieved.

**Note**: if a fields is marked as @Transient, it will be ignored by default.

- **Add to Blacklist**: Use `addBlacklistField(String fieldName)` to add a field to the blacklist.
- **Remove from Blacklist**: Use `removeBlacklistField(String fieldName)` to remove a field from the blacklist.

Example:

```java
stormify().addBlacklistField("temporaryField");
```
