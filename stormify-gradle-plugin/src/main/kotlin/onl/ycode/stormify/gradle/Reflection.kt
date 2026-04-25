// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException

/**
 * Bridge to types the plugin doesn't compile against (KSP internals, AGP).
 * Failure is fatal — silently skipping leaves the build in a half-wired
 * state where the user only sees a downstream "no entities" / "unresolved
 * reference" error with no link to the missing reflective hop.
 */
internal fun Any.callGetterOrThrow(name: String, ctx: String): Any {
    val getter = javaClass.methods.firstOrNull { it.name == name }
        ?: throw GradleException("Stormify: $ctx — ${javaClass.simpleName} has no $name().")
    return getter.invoke(this)
        ?: throw GradleException("Stormify: $ctx — $name() returned null.")
}

internal fun Any.callMethodOrThrow(name: String, paramCount: Int, ctx: String, vararg args: Any?): Any? {
    val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
        ?: throw GradleException("Stormify: $ctx — ${javaClass.simpleName} has no $name($paramCount-arg).")
    return method.invoke(this, *args)
}
