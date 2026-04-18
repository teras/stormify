# Entity Mapping

Stormify maps Kotlin classes to database tables using field names that match the
corresponding database column names. This approach minimizes the need for extensive
configuration or annotations, allowing you to work directly with your classes.

## Field Name Matching

- **Automatic Mapping**: Fields in your classes are automatically mapped to database columns with matching names. No annotations are required as long as the field names correspond to the column names.
- **Optional Annotations**: You can use the `@DbTable` and `@DbField` annotations to provide additional information or to customize the mapping between your classes and the database. See [Annotations](Annotations.md).

## Naming Policy

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

### NamingPolicy Enum Options

1. **CAMEL_CASE**
    - Database columns use the same name as Kotlin/Java fields, preserving camel case.
    - Example: Kotlin field `userName` → DB column `userName`, class `UserAccount` → table `UserAccount`.

2. **LOWER_CASE_WITH_UNDERSCORES** (default)
    - Kotlin/Java camelCase names are converted to snake_case for the database.
    - Example: Kotlin field `userName` → DB column `user_name`, class `UserAccount` → table `user_account`.

3. **UPPER_CASE_WITH_UNDERSCORES**
    - Kotlin/Java camelCase names are converted to SCREAMING_SNAKE_CASE for the database.
    - Example: Kotlin field `userName` → DB column `USER_NAME`, class `UserAccount` → table `USER_ACCOUNT`.

`namingPolicy` is a functional interface — you can create custom implementations:

=== "Kotlin"

    ```kotlin
    stormify.namingPolicy = { name -> "tbl_${name.lowercase()}" }
    ```

=== "Java"

    ```java
    stormify.setNamingPolicy(name -> "tbl_" + name.toLowerCase());
    ```

## Blacklist Management

Stormify includes a feature to manage fields that should be ignored during database interactions. This is useful when you want to exclude certain fields from being created, updated, or retrieved.

**Note**: If a field is marked as transient, it will be ignored by default. All three forms are recognized: JPA `@javax.persistence.Transient`, Kotlin `@kotlin.jvm.Transient`, and the Java `transient` keyword.

=== "Kotlin"

    ```kotlin
    stormify.addBlacklistField("temporaryField")
    ```

=== "Java"

    ```java
    stormify.addBlacklistField("temporaryField");
    ```

## Unmatched Column Policy

Controls how Stormify reacts when a result-set column has no matching field on the target entity (typical when the database has extra columns the entity does not declare — audit columns, trigger-populated fields, legacy leftovers).

| Policy   | Behaviour                                    |
| -------- | -------------------------------------------- |
| `THROW`  | Raise `SQLException` on the first mismatch.  |
| `WARN`   | Log a warning and continue.                  |
| `IGNORE` | Silently skip the column. **Default.**       |

=== "Kotlin"

    ```kotlin
    stormify.unmatchedColumnPolicy = UnmatchedColumnPolicy.THROW
    ```

=== "Java"

    ```java
    stormify.setUnmatchedColumnPolicy(UnmatchedColumnPolicy.THROW);
    ```
