// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.reflect.KClass

/**
 * Native actual of [SingleAggregator]. Delegates to the shared
 * [SingleAggregatorCore] with no platform-specific additions.
 */
actual class SingleAggregator internal actual constructor(
    private val core: SingleAggregatorCore
) {
    actual fun sum(path: String, alias: String?): MultiAggregator {
        core.add("sum", path, alias)
        return MultiAggregator(core)
    }

    actual fun sum(path: ScalarPath, alias: String?): MultiAggregator = sum(path.toString(), alias)

    actual fun avg(path: String, alias: String?): MultiAggregator {
        core.add("avg", path, alias)
        return MultiAggregator(core)
    }

    actual fun avg(path: ScalarPath, alias: String?): MultiAggregator = avg(path.toString(), alias)

    actual fun min(path: String, alias: String?): MultiAggregator {
        core.add("min", path, alias)
        return MultiAggregator(core)
    }

    actual fun min(path: ScalarPath, alias: String?): MultiAggregator = min(path.toString(), alias)

    actual fun max(path: String, alias: String?): MultiAggregator {
        core.add("max", path, alias)
        return MultiAggregator(core)
    }

    actual fun max(path: ScalarPath, alias: String?): MultiAggregator = max(path.toString(), alias)

    actual fun count(path: String, alias: String?): MultiAggregator {
        core.add("count", path, alias)
        return MultiAggregator(core)
    }

    actual fun count(path: ScalarPath, alias: String?): MultiAggregator = count(path.toString(), alias)

    actual fun countDistinct(path: String, alias: String?): MultiAggregator {
        core.add("countDistinct", path, alias)
        return MultiAggregator(core)
    }

    actual fun countDistinct(path: ScalarPath, alias: String?): MultiAggregator =
        countDistinct(path.toString(), alias)

    actual fun raw(expression: String, alias: String?): MultiAggregator {
        core.add("raw", expression, alias)
        return MultiAggregator(core)
    }

    actual val query: String get() = core.buildQuery()

    actual fun <R : Any> execute(type: KClass<R>): R? = core.executeSingle(type)
}
