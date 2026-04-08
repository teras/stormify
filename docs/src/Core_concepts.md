# Core Concepts

## Entity Mapping and Requirements

Stormify maps Kotlin classes to database tables using field names that match the corresponding database column names. This approach minimizes the need for extensive configurations or annotations, allowing you to work directly with your classes.

### Field Name Matching

- **Automatic Mapping**: Fields in your classes are automatically mapped to database columns with matching names. No annotations are required as long as the field names correspond to the column names.
- **Optional Annotations**: You can use the `@DbTable` and `@DbField` annotations to provide additional information or to customize the mapping between your classes and the database.

### Naming Policy

Stormify provides flexible naming policies to convert class names to table names and field names to column names,
ensuring consistency across your database schema. By default, the naming policy is set to
`LOWER_CASE_WITH_UNDERSCORES` (snake_case).

=== "Kotlin"

    ```kotlin
    stormify.namingPolicy = NamingPolicy.CAMEL_CASE
    ```

=== "Java"

    ```java
    stormify.setNamingPolicy(NamingPolicy.CAMEL_CASE);
    ```

#### NamingPolicy Enum Options

1. **CAMEL_CASE**
    - Database columns use the same name as Kotlin/Java fields, preserving camel case.
    - Example: Kotlin field `userName` → DB column `userName`, class `UserAccount` → table `UserAccount`.

2. **LOWER_CASE_WITH_UNDERSCORES** (default)
    - Kotlin/Java camelCase names are converted to snake_case for the database.
    - Example: Kotlin field `userName` → DB column `user_name`, class `UserAccount` → table `user_account`.

3. **UPPER_CASE_WITH_UNDERSCORES**
    - Kotlin/Java camelCase names are converted to SCREAMING_SNAKE_CASE for the database.
    - Example: Kotlin field `userName` → DB column `USER_NAME`, class `UserAccount` → table `USER_ACCOUNT`.

`NamingPolicy` is a functional interface — you can create custom implementations:

=== "Kotlin"

    ```kotlin
    stormify.namingPolicy = NamingPolicy { name -> "tbl_${name.lowercase()}" }
    ```

=== "Java"

    ```java
    stormify.setNamingPolicy(name -> "tbl_" + name.toLowerCase());
    ```

### Custom Primary Key Resolvers

Primary keys in databases commonly follow a naming convention. If this is the case, instead of using annotations, you can register custom primary key resolvers to help Stormify identify primary keys based on their name.

#### How It Works

To set up a primary key resolver, use the `registerPrimaryKeyResolver` method. You provide:

- **Priority**: A numeric value that determines execution order when multiple resolvers are registered. Higher values are checked earlier. The default priority is 0; use values like 10, 20, etc. to run before the default.
- **Resolver Function**: A function that receives the table name and field name, and returns `true` if the field should be treated as a primary key.

#### Example

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
- The priority `10` means this resolver runs earlier than any default (priority 0) resolvers.

By setting up custom primary key resolvers, Stormify can accurately identify primary keys without relying on annotations for every class.

## Annotations

Stormify supports both its own annotations (`@DbTable`, `@DbField`) and standard JPA annotations (`@Id`, `@Table`, `@Column`, etc.) — see [JPA annotations](#other-supported-annotations) below.

### `@DbTable` Annotation

The `@DbTable` annotation specifies the database table name associated with a class. This annotation is
optional and is only needed if the table name differs from the class name.

#### Attributes

- **`name`**: Specifies the name of the table in the database. If not provided, the class name will be used, converted
  using the current naming policy.

#### Example

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.DbTable
    import onl.ycode.stormify.DbField

    @DbTable(name = "custom_table_name")
    data class User(
        @DbField(primaryKey = true)
        var id: Int = 0,
        var name: String = ""
    )
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.DbTable;
    import onl.ycode.stormify.DbField;

    @DbTable(name = "custom_table_name")
    public class User {
        @DbField(primaryKey = true)
        private int id;
        private String name;

        // getters and setters
    }
    ```

In this example, the `User` class maps to the `custom_table_name` table in the database.

### `@DbField` Annotation

The `@DbField` annotation provides additional information about a specific field. This annotation
is optional and allows you to customize how fields are mapped to database columns.

#### Attributes

- **`name`**: Specifies the name of the field in the database. If not provided, the field name in the class will be
  used, converted using the current naming policy.
- **`primaryKey`**: Indicates whether the field is a primary key. Defaults to `false`.
- **`primarySequence`**: Specifies the name of the primary key sequence in the database. If not provided, the primary
  key value generation relies on the database.
- **`autoIncrement`**: Indicates the field value is auto-generated by the database (AUTO_INCREMENT, SERIAL, IDENTITY).
  When true, the field is excluded from INSERT statements and its value is read back after insertion. Defaults to `false`.
- **`creatable`**: Determines whether the field can be used when creating a new record. Defaults to `true`.
- **`updatable`**: Determines whether the field can be used when updating a record. Defaults to `true`.

#### Example

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.DbField

    data class User(
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        var id: Int = 0,

        @DbField(creatable = false, updatable = true)
        var name: String = ""
    )
    ```

=== "Java"

    ```java
    import onl.ycode.stormify.DbField;

    public class User {
        @DbField(name = "custom_id", primaryKey = true, primarySequence = "id_seq")
        private int id;

        @DbField(creatable = false, updatable = true)
        private String name;

        // getters and setters
    }
    ```

In this example:

- The `id` field is mapped to the `custom_id` column, marked as a primary key, and uses a sequence named `id_seq`.
- The `name` field is configured to be updatable but not creatable.

### Other Supported Annotations

Stormify provides support for several standard annotations from the `javax.persistence` package (JPA), making it easy to integrate with existing applications. The following annotations are supported:

- **`@Id`**: Marks a field as the primary key of the entity.

- **`@Table`**: Specifies the table in the database that maps to the entity. Stormify uses the table name from this annotation to map your classes to the corresponding database tables.

- **`@Column`**: Maps a field to a specific column. Stormify reads the `name`, `insertable`, and `updatable` attributes.

- **`@JoinColumn`**: Specifies the column used for joining an entity association. Stormify reads the `name`, `insertable`, and `updatable` attributes.

- **`@SequenceGenerator`**: Defines a primary key generator that uses a database sequence, using the `name` attribute to specify the sequence name.

- **`@GeneratedValue`**: When used with `strategy = GenerationType.IDENTITY`, marks a field as auto-generated by the database (equivalent to `@DbField(autoIncrement = true)`).

- **`@Transient`**: Marks a field to be ignored during database operations.

These annotations help bridge the gap between your classes and the database schema. By leveraging standard JPA annotations, Stormify ensures compatibility with existing JPA setups while providing additional flexibility.

## Annotation Processor (annproc)

Stormify needs entity metadata (field names, types, primary keys) to perform ORM operations.
There are two ways to provide this metadata:

- **Reflection** (JVM only): `kotlin-reflect` discovers metadata at runtime. This is the
  default — `kotlin-reflect` is included as a transitive dependency of `stormify-jvm`.
- **Annotation Processor**: The `annproc` KSP processor generates metadata at compile time
  by scanning `@DbTable` and JPA `@Entity` annotations.

On **Native/Android/iOS**, reflection is not available — `annproc` is required.
On **JVM**, `annproc` is optional but offers faster startup since metadata is pre-computed.

### Setup

Add the KSP plugin and `annproc` dependency:

=== "Gradle (Kotlin)"

    ```kotlin
    plugins {
        id("com.google.devtools.ksp") version "2.2.20-2.0.2"
    }

    dependencies {
        ksp("onl.ycode:annproc:2.0.0")
    }
    ```

=== "Gradle (Java)"

    ```groovy
    plugins {
        id 'com.google.devtools.ksp' version '2.2.20-2.0.2'
    }

    dependencies {
        ksp 'onl.ycode:annproc:2.0.0'
    }
    ```

KSP requires the Kotlin compiler, so pure Java/Maven projects without a Kotlin compilation
step cannot use `annproc` — they rely on `kotlin-reflect` instead.

The processor generates an `EntityRegistrar` object. Pass it to the `Stormify` constructor:

```kotlin
import db.stormify.GeneratedEntities

val stormify = Stormify(dataSource, GeneratedEntities)
```

### Excluding kotlin-reflect

When using `annproc` on JVM, `kotlin-reflect` is no longer needed at runtime. You can
exclude it to reduce the dependency footprint:

=== "Gradle (Kotlin)"

    ```kotlin
    implementation("onl.ycode:stormify-jvm:2.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    ```

=== "Gradle (Java)"

    ```groovy
    implementation('onl.ycode:stormify-jvm:2.0.0') {
        exclude group: 'org.jetbrains.kotlin', module: 'kotlin-reflect'
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>onl.ycode</groupId>
        <artifactId>stormify-jvm</artifactId>
        <version>2.0.0</version>
        <exclusions>
            <exclusion>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-reflect</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    ```

## Reference Fields (Foreign Keys)

When a property's type is another entity (not a primitive, String, date, etc.), Stormify
treats it as a **reference field**. The database column stores the primary key value of
the referenced entity:

```kotlin
data class Order(
    @DbField(primaryKey = true)
    var id: Int = 0,
    var customer: Customer? = null  // DB column stores customer's PK
)
```

When reading an `Order`, Stormify creates a `Customer` instance with **only its primary key
set**. If `Customer` extends `AutoTable`, its remaining fields are lazy-loaded on first
access (see [AutoTable](Advanced_topics.md#autotable-lazy-loading)).

## Working with Entities

There are three ways to perform CRUD operations, depending on your language and style:

### 1. Direct Stormify calls

The standard way — pass entities to a `Stormify` (Kotlin) or `StormifyJ` (Java) instance:

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

### 2. CRUDTable Interface (recommended for Java)

Implement `CRUDTable` to add CRUD methods directly on the entity. This is especially
useful in Java, where extension functions are not available:

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

!!! warning "Default instance required"
    CRUDTable (and the Kotlin extensions below) operate without an explicit `Stormify`
    reference. They require a **default instance**, registered once at startup:

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

### 3. Kotlin Extension Functions

In Kotlin, **any** entity can call `create()`, `update()`, `delete()` directly — no
interface needed (also **requires** the default instance above):

```kotlin
val user = User(name = "Alice").create()
user.name = "Bob"
user.update()
user.delete()
```

SQL can be executed directly from strings:

```kotlin
val users = "SELECT * FROM users WHERE age > ?".read<User>(25)
"DELETE FROM inactive_users".executeUpdate()
```

These also require a default Stormify instance (`stormify.asDefault()`). See
[Kotlin Extension Functions](Advanced_topics.md#kotlin-extension-functions) for the
full list.

## Blacklist Management

Stormify includes a feature to manage fields that should be ignored during database interactions. This is useful when you want to exclude certain fields from being created, updated, or retrieved.

**Note**: If a field is marked as `@Transient`, it will be ignored by default.

=== "Kotlin"

    ```kotlin
    stormify.addBlacklistField("temporaryField")
    ```

=== "Java"

    ```java
    stormify.addBlacklistField("temporaryField");
    ```
