package test

import onl.ycode.stormify.biglist.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderedQueryTest {

    @Test
    fun equalNode() {
        assertEquals(EqualNode("42"), OrderedQuery.parse("42"))
    }

    @Test
    fun equalNodeWithSpaces() {
        assertEquals(EqualNode("42"), OrderedQuery.parse("  42  "))
    }

    @Test
    fun greaterThan() {
        assertEquals(GreaterThanNode("5"), OrderedQuery.parse(">5"))
    }

    @Test
    fun greaterThanWithSpace() {
        assertEquals(GreaterThanNode("5"), OrderedQuery.parse("> 5"))
    }

    @Test
    fun lessThan() {
        assertEquals(LessThanNode("10"), OrderedQuery.parse("<10"))
    }

    @Test
    fun greaterOrEqual() {
        assertEquals(GreaterOrEqualNode("100"), OrderedQuery.parse(">=100"))
    }

    @Test
    fun lessOrEqual() {
        assertEquals(LessOrEqualNode("3"), OrderedQuery.parse("<=3"))
    }

    @Test
    fun between() {
        assertEquals(BetweenNode("5", "10"), OrderedQuery.parse("5...10"))
    }

    @Test
    fun betweenWithSpaces() {
        assertEquals(BetweenNode("5", "10"), OrderedQuery.parse(" 5 ... 10 "))
    }

    @Test
    fun rangeWithOperatorThrows() {
        assertFailsWith<Exception> { OrderedQuery.parse(">5...10") }
    }

    @Test
    fun emptyInputThrows() {
        assertFailsWith<Exception> { OrderedQuery.parse("") }
    }

    @Test
    fun blankInputThrows() {
        assertFailsWith<Exception> { OrderedQuery.parse("   ") }
    }

    @Test
    fun invalidRangeFormatThrows() {
        assertFailsWith<Exception> { OrderedQuery.parse("1...2...3") }
    }

    @Test
    fun decimalValues() {
        assertEquals(GreaterThanNode("3.14"), OrderedQuery.parse(">3.14"))
    }

    @Test
    fun negativeValues() {
        assertEquals(EqualNode("-5"), OrderedQuery.parse("-5"))
    }
}
