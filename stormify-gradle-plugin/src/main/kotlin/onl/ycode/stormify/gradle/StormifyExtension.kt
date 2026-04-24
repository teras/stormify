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
     * Forwarded to the annproc processor as the `stormify.generatedPackage`
     * KSP option.
     *
     * Default: `onl.ycode.stormify.generated`.
     */
    abstract val generatedPackage: Property<String>

    /**
     * Class name for the generated entity registrar. Forwarded to annproc
     * as the `stormify.registrarClass` KSP option.
     *
     * Default: `GeneratedEntities`.
     */
    abstract val registrarClass: Property<String>

    /**
     * Class name for the generated table holder object (the namespace under
     * which `User_`, `Task_`, … entries are exposed). Forwarded to annproc
     * as the `stormify.pathsClass` KSP option.
     *
     * Default: `Tables`.
     */
    abstract val pathsClass: Property<String>

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
