package test

import onl.ycode.stormify.biglist.ScalarPath
import onl.ycode.stormify.biglist.SingleAggregator
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Java sees no Kotlin default arguments: every aggregation of [SingleAggregator] must also
 * exist without the alias, as it does on MultiAggregator and PagedAggregator.
 */
class SingleAggregatorJavaOverloadsTest {
    @Test
    fun everyAggregationHasAnOverloadWithoutAlias() {
        val type = SingleAggregator::class.java
        for (name in listOf("sum", "avg", "min", "max", "count", "countDistinct")) {
            assertNotNull(type.getMethod(name, String::class.java), "$name(String)")
            assertNotNull(type.getMethod(name, ScalarPath::class.java), "$name(ScalarPath)")
        }
        assertNotNull(type.getMethod("raw", String::class.java), "raw(String)")
    }
}
