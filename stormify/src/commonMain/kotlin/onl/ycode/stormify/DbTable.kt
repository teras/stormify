// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Marks a Kotlin class as mapping to a specific database table.
 *
 * This annotation is **optional**. If omitted, Stormify uses the class name automatically,
 * converting it according to the configured naming policy (default: camelCase → snake_case).
 *
 * ## When to Use
 * - Table name differs from class name
 * - Table name doesn't follow naming convention
 * - Need explicit mapping for clarity
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Without annotation - automatic mapping
 * data class UserAccount(...)  // Maps to table: user_account (snake_case)
 *
 * // With annotation - explicit mapping
 * @DbTable(name = "users")
 * data class User(...)  // Maps to table: users
 *
 * // Legacy table name
 * @DbTable(name = "tbl_customer_info")
 * data class Customer(...)  // Maps to table: tbl_customer_info
 *
 * // Schema-qualified table (some databases)
 * @DbTable(name = "public.users")
 * data class User(...)
 * ```
 *
 * ## Naming Policy
 *
 * Without `@DbTable`, the table name is derived from the class name using the configured policy:
 *
 * - **LOWER_CASE_WITH_UNDERSCORES** (default): `UserAccount` → `user_account`
 * - **CAMEL_CASE**: `UserAccount` → `userAccount`
 * - **UPPER_CASE_WITH_UNDERSCORES**: `UserAccount` → `USER_ACCOUNT`
 *
 * ```kotlin
 * // Configure naming policy
 * stormify.setNamingPolicy(NamingPolicy.CAMEL_CASE)
 * ```
 *
 * ## Comparison with JPA @Table
 *
 * Stormify's `@DbTable` is compatible with JPA's `@Table` annotation:
 *
 * ```kotlin
 * // Stormify annotation
 * @DbTable(name = "users")
 * data class User(...)
 *
 * // JPA annotation (also supported)
 * @Table(name = "users")
 * data class User(...)
 * ```
 *
 * Both work identically in Stormify. Use `@DbTable` for Stormify-specific projects,
 * or `@Table` for JPA compatibility.
 *
 * ## Important Notes
 * - Annotation is **optional** - only use when needed
 * - Table must exist in the database
 * - Case sensitivity depends on database (MySQL: case-insensitive, PostgreSQL: case-sensitive)
 * - Schema prefixes supported (e.g., `schema.table_name`)
 *
 * @property name The exact table name in the database. If empty/blank, uses class name with naming policy.
 *
 * @see DbField
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class DbTable(
    /**
     * The exact name of the table in the database.
     *
     * If not provided (empty string), Stormify uses the class name converted
     * according to the configured naming policy.
     *
     * **Examples:**
     * - `"users"` - maps to table `users`
     * - `"tbl_user_accounts"` - maps to table `tbl_user_accounts`
     * - `""` (empty) - uses class name with naming policy
     *
     * @return the database table name, or empty string for automatic naming
     */
    val name: String = ""
)
