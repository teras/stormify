// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.provider.Property

/**
 * Configuration DSL for the `onl.ycode.stormify` Gradle plugin.
 *
 * Example:
 * ```
 * stormify {
 *     generatedPackage.set("com.example.generated")
 *     registrarClass.set("MyEntities")
 *     pathsClass.set("MyTables")
 * }
 * ```
 *
 * All properties are optional. Defaults:
 * - `generatedPackage`: `onl.ycode.stormify.generated`.
 * - `registrarClass`: `GeneratedEntities`.
 * - `pathsClass`: `Tables`.
 */
abstract class StormifyExtension {

    /**
     * Package name for the generated `EntityRegistrar` and `Tables` classes.
     *
     * Default: `onl.ycode.stormify.generated`.
     */
    abstract val generatedPackage: Property<String>

    /**
     * Class name for the generated entity registrar.
     *
     * Default: `GeneratedEntities`.
     */
    abstract val registrarClass: Property<String>

    /**
     * Class name for the generated table holder object (the namespace under
     * which `User_`, `Task_`, … entries are exposed).
     *
     * Default: `Tables`.
     */
    abstract val pathsClass: Property<String>

    /**
     * Class name for the generated table holder object emitted into test
     * source sets when entities are declared in `commonTest`/`*Test`. Lives
     * in the same package as [pathsClass]; the distinct name avoids a
     * compile-time collision with the production `Tables` on the test
     * classpath.
     *
     * Default: `TablesTest`.
     */
    abstract val testPathsClass: Property<String>

    /**
     * Class name for the generated entity registrar emitted into test
     * source sets.
     *
     * Default: `GeneratedTestEntities`.
     */
    abstract val testRegistrarClass: Property<String>

    /**
     * When `true`, the plugin skips auto-adding the `onl.ycode:stormify` and
     * `onl.ycode:annproc` runtime/processor dependencies — useful for the
     * Stormify project itself (dogfooding the plugin) where those modules
     * live in the same Gradle build and must be referenced via
     * `project(":stormify")` / `project(":annproc")` instead.
     *
     * Default: `false`.
     */
    abstract val selfHosted: Property<Boolean>

    /**
     * Whether to emit the generated `EntityRegistrar` (and the matching
     * `stormifyEntities` shim) alongside the type-safe `Tables` object.
     *
     * - `true` (default): generates the registrar — required on Kotlin/Native,
     *   Android, and iOS where reflection-based entity discovery does not
     *   work. Users construct Stormify with `Stormify(ds, GeneratedEntities)`.
     * - `false`: skips registrar emission. Suitable for JVM-only projects
     *   that prefer reflection-based discovery (`Stormify(ds)`) but still
     *   want the generated `Tables` object for type-safe column references.
     *
     * Default: `true`.
     */
    abstract val generateRegistrar: Property<Boolean>
}
