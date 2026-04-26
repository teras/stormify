package test

import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.SortDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PageSpecJsonTest {

    // ========== Happy paths ==========

    @Test fun emptyObjectGivesDefaults() {
        val s = PageSpec.fromJson("{}")
        assertEquals(0, s.page); assertEquals(15, s.pageSize)
        assertEquals(emptyMap(), s.filters)
        assertEquals(emptyMap(), s.sorts)
        assertEquals(emptyMap(), s.caseSensitive)
    }

    @Test fun fullObject() {
        val s = PageSpec.fromJson("""
            {"page": 2, "pageSize": 25,
             "filters": {"name":"acme","city":"Athens"},
             "sorts": {"name":"ASC"},
             "caseSensitive": {"name":true}}
        """.trimIndent())
        assertEquals(2, s.page); assertEquals(25, s.pageSize)
        assertEquals(mapOf("name" to "acme", "city" to "Athens"), s.filters)
        assertEquals(mapOf("name" to SortDir.ASC), s.sorts)
        assertEquals(mapOf("name" to true), s.caseSensitive)
    }

    @Test fun whitespaceTolerance() {
        val s = PageSpec.fromJson("  \n\t{  \"page\"\n:\t 3  ,  \"pageSize\"  :  10  }  \n")
        assertEquals(3, s.page); assertEquals(10, s.pageSize)
    }

    @Test fun leadingWhitespaceOnly() {
        val s = PageSpec.fromJson("          {}          ")
        assertEquals(0, s.page)
    }

    @Test fun allMapsEmpty() {
        val s = PageSpec.fromJson("""{"filters":{},"sorts":{},"caseSensitive":{}}""")
        assertEquals(emptyMap(), s.filters)
        assertEquals(emptyMap(), s.sorts)
        assertEquals(emptyMap(), s.caseSensitive)
    }

    // ========== Escapes ==========

    @Test fun allBasicEscapes() {
        val s = PageSpec.fromJson("""{"filters":{"k":"\"\\\/\n\r\t\b\f"}}""")
        assertEquals("\"\\/\n\r\t\b\u000C", s.filters["k"])
    }

    @Test fun unicodeEscapeSingle() {
        val s = PageSpec.fromJson("""{"filters":{"g":"\u03b1\u03b2\u03b3"}}""")
        assertEquals("αβγ", s.filters["g"])
    }

    @Test fun unicodeEscapeMixedCase() {
        val s = PageSpec.fromJson("""{"filters":{"x":"\u03B1\u00fF"}}""")
        assertEquals("α\u00FF", s.filters["x"])
    }

    @Test fun doubleEscapedBackslash() {
        // Input: {"filters":{"k":"a\\\\b"}}   means value is a\\b (literal backslash + b)
        val s = PageSpec.fromJson("""{"filters":{"k":"a\\\\b"}}""")
        assertEquals("a\\\\b", s.filters["k"])
    }

    @Test fun quotesWithinValue() {
        val s = PageSpec.fromJson("""{"filters":{"q":"say \"hi\""}}""")
        assertEquals("""say "hi"""", s.filters["q"])
    }

    @Test fun rawControlCharInString() {
        // Strictly invalid JSON (raw \n inside string), but our lenient parser accepts it.
        val json = "{\"filters\":{\"k\":\"line1\nline2\"}}"
        val s = PageSpec.fromJson(json)
        assertEquals("line1\nline2", s.filters["k"])
    }

    @Test fun emptyStringValue() {
        val s = PageSpec.fromJson("""{"filters":{"":"","x":""}}""")
        assertEquals(mapOf("" to "", "x" to ""), s.filters)
    }

    @Test fun invalidEscapeThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"\q"}}""") }
    }

    @Test fun truncatedUnicodeEscapeThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"\u03b"}}""") }
    }

    @Test fun truncatedSimpleEscapeThrows() {
        // trailing \ at end of string — must throw
        assertFails { PageSpec.fromJson("{\"filters\":{\"k\":\"foo\\\"}}") }
    }

    @Test fun nonHexInUnicodeEscapeThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"\uZZZZ"}}""") }
    }

    // ========== Truncated / missing structure ==========

    @Test fun emptyInputThrows() {
        assertFails { PageSpec.fromJson("") }
    }

    @Test fun whitespaceOnlyThrows() {
        assertFails { PageSpec.fromJson("   \n\t  ") }
    }

    @Test fun missingClosingBraceThrows() {
        assertFails { PageSpec.fromJson("""{"page":1""") }
    }

    @Test fun missingOpeningBraceThrows() {
        assertFails { PageSpec.fromJson(""""page":1}""") }
    }

    @Test fun truncatedMidKeyThrows() {
        assertFails { PageSpec.fromJson("""{"pag""") }
    }

    @Test fun truncatedMidValueThrows() {
        assertFails { PageSpec.fromJson("""{"page":""") }
    }

    @Test fun truncatedMidMapThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":""") }
    }

    @Test fun truncatedMidMapAfterColonThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"v""") }
    }

    @Test fun missingColonThrows() {
        assertFails { PageSpec.fromJson("""{"page" 1}""") }
    }

    @Test fun trailingCommaThrows() {
        assertFails { PageSpec.fromJson("""{"page":1,}""") }
    }

    @Test fun doubleCommaThrows() {
        assertFails { PageSpec.fromJson("""{"page":1,,"pageSize":10}""") }
    }

    @Test fun extraTrailingContentThrows() {
        assertFails { PageSpec.fromJson("""{"page":3} extra garbage here""") }
    }

    @Test fun extraTrailingWhitespaceAllowed() {
        val s = PageSpec.fromJson("""{"page":3}    """)
        assertEquals(3, s.page)
    }

    @Test fun unterminatedStringThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"value""") }
    }

    // ========== Wrong types ==========

    @Test fun stringForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":"two"}""") }
    }

    @Test fun nullForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":null}""") }
    }

    @Test fun boolForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":true}""") }
    }

    @Test fun intForStringThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":42}}""") }
    }

    @Test fun nullForStringThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":null}}""") }
    }

    @Test fun objectForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":{}}""") }
    }

    @Test fun arrayForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":[1]}""") }
    }

    @Test fun stringForBoolThrows() {
        assertFails { PageSpec.fromJson("""{"caseSensitive":{"k":"yes"}}""") }
    }

    @Test fun numberForBoolThrows() {
        assertFails { PageSpec.fromJson("""{"caseSensitive":{"k":1}}""") }
    }

    @Test fun objectForStringInFilterThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":{"nested":"x"}}}""") }
    }

    @Test fun arrayForFiltersThrows() {
        assertFails { PageSpec.fromJson("""{"filters":[]}""") }
    }

    @Test fun stringForFiltersThrows() {
        assertFails { PageSpec.fromJson("""{"filters":"nope"}""") }
    }

    // ========== Numbers ==========

    @Test fun negativePageThrowsViaInit() {
        // readInt accepts -1, but PageSpec.init rejects page < 0.
        assertFails { PageSpec.fromJson("""{"page":-1}""") }
    }

    @Test fun zeroPageSizeThrowsViaInit() {
        assertFails { PageSpec.fromJson("""{"pageSize":0}""") }
    }

    @Test fun maxIntPage() {
        val s = PageSpec.fromJson("""{"page":2147483647,"pageSize":1}""")
        assertEquals(Int.MAX_VALUE, s.page)
    }

    @Test fun integerOverflowThrows() {
        assertFails { PageSpec.fromJson("""{"page":2147483648}""") }
    }

    @Test fun veryLargeIntegerOverflowThrows() {
        assertFails { PageSpec.fromJson("""{"page":99999999999999999999}""") }
    }

    @Test fun floatForIntThrows() {
        assertFails { PageSpec.fromJson("""{"page":2.5}""") }
    }

    @Test fun scientificNotationThrows() {
        assertFails { PageSpec.fromJson("""{"pageSize":1e3}""") }
    }

    @Test fun plusSignedIntThrows() {
        // We accept only optional leading "-", not "+"
        assertFails { PageSpec.fromJson("""{"page":+5}""") }
    }

    @Test fun leadingZerosAccepted() {
        // Strict JSON disallows leading zeros (except "0" itself); we're lenient.
        val s = PageSpec.fromJson("""{"page":007}""")
        assertEquals(7, s.page)
    }

    // ========== Unknown keys (forward-compat) ==========

    @Test fun unknownKeySilentlyIgnored() {
        val s = PageSpec.fromJson("""{"page":1,"unknownField":"x"}""")
        assertEquals(1, s.page)
    }

    @Test fun unknownObjectKeySkipped() {
        val s = PageSpec.fromJson("""{"page":1,"futureFeature":{"a":1,"b":[2,3,{"nested":"v"}]}}""")
        assertEquals(1, s.page)
    }

    @Test fun unknownNullValueSkipped() {
        val s = PageSpec.fromJson("""{"page":1,"someKey":null}""")
        assertEquals(1, s.page)
    }

    @Test fun unknownArrayValueSkipped() {
        val s = PageSpec.fromJson("""{"page":1,"tags":["a","b",["nested"]]}""")
        assertEquals(1, s.page)
    }

    @Test fun unknownNumberArrayMixedSkipped() {
        val s = PageSpec.fromJson("""{"page":1,"m":[{},[],1,null,true]}""")
        assertEquals(1, s.page)
    }

    // ========== Sort values ==========

    @Test fun unknownSortValueDropped() {
        val s = PageSpec.fromJson("""{"sorts":{"a":"ASC","b":"SIDEWAYS","c":"DESC"}}""")
        assertEquals(mapOf("a" to SortDir.ASC, "c" to SortDir.DESC), s.sorts)
    }

    @Test fun lowercaseSortValueDropped() {
        // SortDir.valueOf is case-sensitive, so "asc" does not match ASC.
        val s = PageSpec.fromJson("""{"sorts":{"a":"asc"}}""")
        assertEquals(emptyMap(), s.sorts)
    }

    @Test fun emptySortValueDropped() {
        val s = PageSpec.fromJson("""{"sorts":{"a":""}}""")
        assertEquals(emptyMap(), s.sorts)
    }

    // ========== Duplicate and ordering ==========

    @Test fun duplicateKeyLastWins() {
        val s = PageSpec.fromJson("""{"page":1,"page":5}""")
        assertEquals(5, s.page)
    }

    @Test fun duplicateFilterKeyLastWins() {
        val s = PageSpec.fromJson("""{"filters":{"k":"a","k":"b"}}""")
        assertEquals("b", s.filters["k"])
    }

    // ========== Scale ==========

    @Test fun manyFilters() {
        val entries = (0..199).joinToString(",") { """"k$it":"v$it"""" }
        val json = "{\"filters\":{$entries}}"
        val s = PageSpec.fromJson(json)
        assertEquals(200, s.filters.size)
        assertEquals("v199", s.filters["k199"])
    }

    @Test fun longFilterValue() {
        val big = "x".repeat(10_000)
        val s = PageSpec.fromJson("""{"filters":{"k":"$big"}}""")
        assertEquals(big, s.filters["k"])
        assertEquals(10_000, s.filters["k"]!!.length)
    }

    @Test fun deeplyNestedUnknownKeyDoesNotStackOverflow() {
        // Build a nested unknown value {a:{a:{a: ... }}} of moderate depth.
        // Kotlin/Native default stack is generous; a few hundred levels is fine.
        val depth = 200
        val sb = StringBuilder("""{"x":""")
        repeat(depth) { sb.append("""{"a":""") }
        sb.append("\"leaf\"")
        repeat(depth) { sb.append("}") }
        sb.append("}")
        val s = PageSpec.fromJson(sb.toString())
        assertEquals(0, s.page)
    }

    // ========== skipValue bracket mismatches (for unknown keys) ==========

    @Test fun unknownValueTruncatedObjectThrows() {
        // "x" is unknown; its value is a truncated object.
        assertFails { PageSpec.fromJson("""{"x":{"a":1""") }
    }

    @Test fun unknownValueTruncatedArrayThrows() {
        assertFails { PageSpec.fromJson("""{"x":[1,2""") }
    }

    @Test fun unknownValueExtraCloseBraceThrows() {
        // Inner closes twice: {"a":1}} — the second '}' would be left over.
        // Our parser sees the extra '}' after the outer closing and doesn't consume it
        // (trailing content is ignored), but the STRUCTURAL issue is the unknown VALUE
        // having a stray brace. Actually: {"x":{"a":1}}} — parsed as {"x":{"a":1}} then
        // leftover "}"; trailing content is allowed. Let's verify with the tighter case:
        // unknown value = {"a":1}} where the inner object closes early and the surplus
        // '}' is where our outer expects ','|}.
        assertFails { PageSpec.fromJson("""{"x":{"a":1}}}""") }
        // But {"x":{"a":1}} is valid (inner + outer close, trailing garbage is extra ']').
    }

    @Test fun unknownValueExtraCloseBracketThrows() {
        assertFails { PageSpec.fromJson("""{"x":[1,2]]}""") }
    }

    @Test fun unknownValueMismatchedBracketForArrayThrows() {
        // Array opened with [ but closed with }
        assertFails { PageSpec.fromJson("""{"x":[1,2}}""") }
    }

    @Test fun unknownValueMismatchedBracketForObjectThrows() {
        // Object opened with { but "closed" with ]
        assertFails { PageSpec.fromJson("""{"x":{"a":1]}""") }
    }

    @Test fun unknownValueOnlyOpenBraceThrows() {
        assertFails { PageSpec.fromJson("""{"x":{""") }
    }

    @Test fun unknownValueOnlyOpenBracketThrows() {
        assertFails { PageSpec.fromJson("""{"x":[""") }
    }

    @Test fun unknownValueOnlyCloseBraceThrows() {
        assertFails { PageSpec.fromJson("""{"x":}""") }
    }

    @Test fun unknownValueOnlyCloseBracketThrows() {
        assertFails { PageSpec.fromJson("""{"x":]""") }
    }

    @Test fun unknownValueDeepNestedMismatchThrows() {
        // Array contains object that never closes
        assertFails { PageSpec.fromJson("""{"x":[1,{"a":2,3]}""") }
    }

    @Test fun unknownValueDeepNestedCorrectlyClosed() {
        // Sanity check: the well-formed version passes.
        val s = PageSpec.fromJson("""{"page":7,"x":[1,{"a":2},3]}""")
        assertEquals(7, s.page)
    }

    @Test fun unknownValueAsymmetricBracketsThrows() {
        // Array containing } instead of ]
        assertFails { PageSpec.fromJson("""{"x":[1,2,3}""") }
    }

    @Test fun unknownValueTrailingCommaInObjectThrows() {
        assertFails { PageSpec.fromJson("""{"x":{"a":1,}}""") }
    }

    @Test fun unknownValueTrailingCommaInArrayThrows() {
        assertFails { PageSpec.fromJson("""{"x":[1,2,]}""") }
    }

    @Test fun unknownValueNestedQuoteInArrayRunsAwayThrows() {
        // Unterminated string inside nested array
        assertFails { PageSpec.fromJson("""{"x":["a","unterminated""") }
    }

    @Test fun trailingCloseBracketThrows() {
        // Top-level trailing ']' after a valid PageSpec.
        assertFails { PageSpec.fromJson("""{"page":1}]""") }
    }

    @Test fun strayCloseBracketInObjectThrows() {
        assertFails { PageSpec.fromJson("""{"page":1]""") }
    }

    @Test fun strayOpenBracketInObjectThrows() {
        // Used as a value — expect it to be interpreted as array start, but there's no matching ]
        assertFails { PageSpec.fromJson("""{"x":[""") }
    }

    @Test fun doubleCloseBracketInArrayThrows() {
        assertFails { PageSpec.fromJson("""{"x":[1,2]]""") }
    }

    @Test fun doubleOpenBracketInArraySanity() {
        // [[1,2]] is a valid nested-array unknown value — should parse OK via skipValue.
        val s = PageSpec.fromJson("""{"page":1,"x":[[1,2],[3,4]]}""")
        assertEquals(1, s.page)
    }

    @Test fun bracketInsideObjectValueThrows() {
        // Object expected, array given → throws.
        assertFails { PageSpec.fromJson("""{"x":[1,"unclosed object inside",{"k":2]}""") }
    }

    @Test fun braceInsideArrayValueThrows() {
        // Array expected (if semantically), but our skipValue doesn't care about semantics —
        // it tracks bracket kinds. Unterminated object inside array:
        assertFails { PageSpec.fromJson("""{"x":[{"a":1}""") }
    }

    @Test fun mixedBracketSoupThrows() {
        assertFails { PageSpec.fromJson("""{"x":[{"a":[1,2}]}""") }
    }

    @Test fun onlyBracketsThrows() {
        assertFails { PageSpec.fromJson("[]") }
    }

    @Test fun arrayAsRootThrows() {
        // Top-level must be an object.
        assertFails { PageSpec.fromJson("""[{"page":1}]""") }
    }

    // ========== Hostile / pathological inputs ==========

    @Test fun triplePlusCommasThrows() {
        assertFails { PageSpec.fromJson("""{"page":1,,,"pageSize":5}""") }
    }

    @Test fun onlyCommasThrows() {
        assertFails { PageSpec.fromJson("""{,,,}""") }
    }

    @Test fun missingValueAfterColonThrows() {
        assertFails { PageSpec.fromJson("""{"page":,"pageSize":5}""") }
    }

    @Test fun keyWithCommaInsteadOfColonThrows() {
        assertFails { PageSpec.fromJson("""{"page",1}""") }
    }

    @Test fun garbageBeforeJsonThrows() {
        assertFails { PageSpec.fromJson("""xxx{"page":1}""") }
    }

    @Test fun garbageBetweenFieldsThrows() {
        assertFails { PageSpec.fromJson("""{"page":1 xxx "pageSize":5}""") }
    }

    @Test fun loneBackslashThrows() {
        // Value is a single backslash, no character follows to form an escape.
        assertFails { PageSpec.fromJson("{\"filters\":{\"k\":\"\\\"}}") }
    }

    @Test fun backslashBeforeClosingBraceRunsAway() {
        // {"filters":{"k":"\"}}   — the \" is an escaped quote, so string is unterminated,
        // and parser runs past the "}}" looking for the closing ". Eventually throws.
        assertFails { PageSpec.fromJson("{\"filters\":{\"k\":\"\\\"}}") }
    }

    @Test fun nonStringKeyThrows() {
        assertFails { PageSpec.fromJson("""{1:"x"}""") }
    }

    @Test fun nonStringKeyInFilterThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{42:"x"}}""") }
    }

    @Test fun keyWithEmbeddedNull() {
        // Key can be empty string; "\u0000" is also a valid key char via escape.
        val s = PageSpec.fromJson("""{"filters":{"k\u0000ey":"value"}}""")
        assertEquals(mapOf("k\u0000ey" to "value"), s.filters)
    }

    @Test fun colonOnlyThrows() {
        assertFails { PageSpec.fromJson("""{:"x"}""") }
    }

    @Test fun unclosedFilterObjectRunsThrough() {
        // {"filters":{"k":"v","unclosed":
        assertFails { PageSpec.fromJson("""{"filters":{"k":"v" """) }
    }

    @Test fun filterValueRawTabCharAccepted() {
        // Lenient: raw tab inside string.
        val s = PageSpec.fromJson("{\"filters\":{\"k\":\"a\tb\"}}")
        assertEquals("a\tb", s.filters["k"])
    }

    @Test fun weirdBinaryBytesInValue() {
        // Embed several ASCII control chars via \u escapes + one raw printable char.
        val s = PageSpec.fromJson("""{"filters":{"k":"\u0001\u001fABC"}}""")
        assertEquals("\u0001\u001fABC", s.filters["k"])
    }

    @Test fun nullInsideCaseSensitiveThrows() {
        assertFails { PageSpec.fromJson("""{"caseSensitive":{"k":null}}""") }
    }

    @Test fun nullFilterMapThrows() {
        // The whole map can't be null.
        assertFails { PageSpec.fromJson("""{"filters":null}""") }
    }

    @Test fun onlyOpenBraceThrows() {
        assertFails { PageSpec.fromJson("{") }
    }

    @Test fun onlyCloseBraceThrows() {
        assertFails { PageSpec.fromJson("}") }
    }

    @Test fun colonWithoutKeyInFilterThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{:"v"}}""") }
    }

    @Test fun commaFirstInFilterThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{,"k":"v"}}""") }
    }

    @Test fun trailingCommaInFilterThrows() {
        assertFails { PageSpec.fromJson("""{"filters":{"k":"v",}}""") }
    }

    // Note: FilterValues / FilterCountedValues toString is tested under a DB-backed
    // test in FilterValuesToStringTest (their lazy-loaded nature requires a live
    // Stormify). FilterCountedValue (the data class) has no custom toString — it
    // inherits the default data-class debug form.
}
