// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.stormify.biglist.PagedListBase

/**
 * Cleanup facade for Stormify's library-wide shared state on the **desktop / JVM**
 * target. Not available on Native, Android, or iOS / macOS — those platforms cannot
 * encounter the problem this class is designed to solve, so it is intentionally
 * absent from their APIs.
 *
 * ### When to call [clear]
 *
 * Call this from webapp undeploy hooks — `ServletContextListener.contextDestroyed`,
 * `@PreDestroy` on a CDI / Spring `@ApplicationScoped` bean, etc. — **only if**
 * Stormify is loaded from a classpath that outlives the webapp:
 *
 * - Payara / GlassFish: `domain/lib/`
 * - Tomcat: `$CATALINA_HOME/lib/`
 * - WildFly / JBoss: a shared module
 *
 * In that topology the library's `object` singletons live in the server's common
 * ClassLoader while your entity classes live in the webapp ClassLoader. Without
 * explicit cleanup, the registries retain references to your entity `KClass`es
 * and block the webapp's ClassLoader from being garbage-collected — a classic
 * metaspace leak across redeploys.
 *
 * ### When you DON'T need to call [clear]
 *
 * The default Jakarta EE deployment puts `stormify-jvm.jar` inside the WAR
 * (`WEB-INF/lib/`). In that case Stormify's singletons live **inside** the
 * webapp ClassLoader alongside your entities, and the entire ClassLoader
 * (registries included) is reclaimed on undeploy. Calling [clear] is harmless
 * but unnecessary.
 *
 * Standalone processes (CLI tools, desktop apps, Spring Boot fat-jars, Ktor
 * servers) never need this either — the process ends and everything is
 * reclaimed by the OS. On **Native / Android / iOS / macOS** this class does
 * not exist at all: those runtimes either have a single-ClassLoader-per-process
 * lifecycle (Android) or no ClassLoader concept whatsoever (AOT-compiled native
 * binaries), so there is nothing to clear.
 *
 * ### What it clears
 *
 * - [Stormify.defaultInstance]
 * - The cached `StormifyJ` wrapper exposed by [StormifyJ.getDefault]
 * - [EntityMeta] registry (compile-time entity metadata populated by annproc)
 * - [EnumRegistry] registry (native enum metadata populated by annproc)
 * - [PagedListBase.defaultInputParser] (resets to [onl.ycode.stormify.biglist.InputParser.NONE])
 *
 * After this call, the library returns to its initial state. To continue using
 * it in the same JVM, construct a fresh [Stormify] or [StormifyJ] instance and
 * call `asDefault()`; annproc-generated `GeneratedEntities.register(...)` will
 * re-populate the registries on next use.
 *
 * ### Implementer's note
 *
 * This file is the **single audit point** for library-wide shared state on JVM.
 * When adding a new `object`, `companion object`, or top-level mutable property
 * that can hold ClassLoader-bound references — such as `KClass<*>` keys or
 * user-supplied lambdas / instances — expose an `internal fun clearX()` on that
 * type and call it from [clear] below. Leaving out the call re-introduces the
 * leak, so keep this list exhaustive.
 */
object StormifyLifecycle {
    /** Clears all registered library-wide shared state. See the class KDoc for when to call. */
    fun clear() {
        Stormify.clearDefault()
        StormifyJ.clearDefaultWrapper()
        EntityMeta.clearRegistry()
        EnumRegistry.clearRegistry()
        PagedListBase.clearDefaultInputParser()
    }
}
