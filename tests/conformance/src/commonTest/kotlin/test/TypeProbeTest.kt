// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal as IonBigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger as IonBigInteger
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Probe of every declared type kdbc supports, read from every column kind it is
 * naturally stored in, on the database under test.
 *
 * A read can go wrong without failing: on the JVM a driver that refuses the class it
 * is asked for throws, kdbc catches it and reads untyped, and the value still comes
 * out right, but the driver (or the pool wrapping it) may already have logged the
 * failure, and every cell pays for a thrown exception. On native a read can fail
 * outright or hand back another value. The probe reports all three: a failed read, a
 * wrong value, and on the JVM a rejection by the driver.
 *
 * Not part of the regular run: it is meant for when a driver or its version changes.
 * Run it with `./testing/test.sh probe [database]`, which sets `-Dstormify.probe=true` on
 * the JVM and `STORMIFY_PROBE=1` on native.
 */
class TypeProbeTest {
    @Test
    fun probe() = TestHelper.withDb("TYPE-PROBE") { s ->
        if (!TypeProbe.enabled) skipTest(SkipReason.DIALECT_QUIRK, "type probe runs only when asked for")
        runTypeProbe(s)
    }
}

/** Platform additions to the probe, installed by the test data source factory of each target. */
object TypeProbe {
    /** Whether the probe was asked for. */
    var enabled = false

    /** Declared types the platform supports on top of the common ones. */
    val platformTypes = mutableListOf<ProbeType>()

    /** Starts recording the classes the driver refuses; JVM only. */
    var startRecording: () -> Unit = {}

    /** The classes the driver refused since [startRecording], with the driver's message. */
    var recordedRejections: () -> List<String> = { emptyList() }
}

/** What a column holds, in the canonical form each declared type is compared in. */
enum class ProbeKind { INTEGRAL, BIGINT, FRACTIONAL, BOOLEAN, CHAR, TEXT, BYTES, DATE, DATETIME, TIME, UUID }

/**
 * A declared read type: how to read a column as it, which column kinds it is meant to
 * read, and how to render what it read in the canonical form of a [ProbeKind].
 * A kind in [unchecked] is read and must not fail or be refused, but its value is not
 * compared, as its rendering is the database's own (a number read as text).
 */
class ProbeType(
    val name: String,
    val kinds: Set<ProbeKind>,
    val unchecked: Set<ProbeKind> = emptySet(),
    val read: (Stormify, String) -> Any?,
    val canon: (Any) -> String,
)

private class ProbeColumn(val name: String, val ddl: String, val literal: String, val kind: ProbeKind, val value: String)

private const val UUID_TEXT = "123e4567-e89b-12d3-a456-426614174000"

private fun probeColumns(): List<ProbeColumn> {
    val d = TestDDL
    val oracle = d.dialect == SqlDialect.ORACLE_NEW || d.dialect == SqlDialect.ORACLE_OLD
    val varchar = if (oracle) "VARCHAR2" else "VARCHAR"
    val blobLiteral = when {
        d.isPostgres -> "'\\x010203'::bytea"
        d.isMssql -> "0x010203"
        oracle -> "TO_BLOB(HEXTORAW('010203'))"
        else -> "X'010203'"
    }
    val dateLiteral = if (oracle) "DATE '2024-03-15'" else "'2024-03-15'"
    val tsLiteral = if (oracle) "TIMESTAMP '2024-03-15 13:45:30.123'" else "'2024-03-15 13:45:30.123'"
    val tsType = when {
        d.isMysqlFamily -> "DATETIME(3)"
        else -> d.timestampType()
    }
    return listOfNotNull(
        ProbeColumn("c_int", d.intType(), "42", ProbeKind.INTEGRAL, "42"),
        ProbeColumn("c_big", d.bigIntType(), "1234567890123", ProbeKind.BIGINT, "1234567890123"),
        ProbeColumn("c_dec", d.decimalType(12, 2), "42.50", ProbeKind.FRACTIONAL, "42.5"),
        ProbeColumn("c_dbl", d.doubleType(), "0.1", ProbeKind.FRACTIONAL, "0.1"),
        ProbeColumn("c_bool", d.booleanType(), if (d.isPostgres || d.isMysqlFamily) "TRUE" else "1", ProbeKind.BOOLEAN, "true"),
        ProbeColumn("c_ch", "CHAR(1)", "'y'", ProbeKind.CHAR, "y"),
        ProbeColumn("c_vc", "$varchar(20)", "'hello'", ProbeKind.TEXT, "hello"),
        ProbeColumn("c_txt", d.textType(), "'hello'", ProbeKind.TEXT, "hello"),
        ProbeColumn("c_uuid", "$varchar(36)", "'$UUID_TEXT'", ProbeKind.UUID, UUID_TEXT),
        ProbeColumn("c_blob", d.blobType(), blobLiteral, ProbeKind.BYTES, "010203"),
        ProbeColumn("c_date", "DATE", dateLiteral, ProbeKind.DATE, "2024-03-15"),
        ProbeColumn("c_ts", tsType, tsLiteral, ProbeKind.DATETIME, "2024-03-15T13:45:30.123"),
        // Oracle has no TIME type
        if (oracle) null else ProbeColumn("c_time", "TIME", "'13:45:30'", ProbeKind.TIME, "13:45:30"),
    )
}

// --- Canonical renderings ---

/** A number as a plain decimal without trailing zeros: `42`, `42.5`, `1234567890123`. */
fun probeNumber(text: String): String {
    val plain = IonBigDecimal.parseString(text).toStringExpanded()
    return if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Date and time as `2024-03-15T13:45:30.123`, always with milliseconds. */
fun probeDateTime(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, nanos: Int): String =
    "${y.toString().padStart(4, '0')}-${mo.pad()}-${d.pad()}T${h.pad()}:${mi.pad()}:${s.pad()}." +
            (nanos / 1_000_000).toString().padStart(3, '0')

private fun Int.pad() = toString().padStart(2, '0')

private val SMALL = setOf(ProbeKind.INTEGRAL)
private val INTEGRAL = setOf(ProbeKind.INTEGRAL, ProbeKind.BIGINT)
private val NUMERIC = setOf(ProbeKind.INTEGRAL, ProbeKind.BIGINT, ProbeKind.FRACTIONAL)

/** A date also reads into the date-time types, at midnight; a date-time into a date or a time loses part of it. */
val PROBE_DATE_KINDS = setOf(ProbeKind.DATE)
val PROBE_DATETIME_KINDS = setOf(ProbeKind.DATETIME, ProbeKind.DATE)

private fun integral(name: String, kinds: Set<ProbeKind> = INTEGRAL, read: (Stormify, String) -> Any?) =
    ProbeType(name, kinds, read = read, canon = { probeNumber(it.toString()) })

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
private val COMMON_TYPES = listOf(
    integral("Byte", SMALL) { s, q -> s.readOne<Byte>(q) },
    integral("Short", SMALL) { s, q -> s.readOne<Short>(q) },
    integral("Int", SMALL) { s, q -> s.readOne<Int>(q) },
    integral("Long") { s, q -> s.readOne<Long>(q) },
    integral("ion.BigInteger") { s, q -> s.readOne<IonBigInteger>(q) },
    ProbeType("Float", setOf(ProbeKind.INTEGRAL, ProbeKind.FRACTIONAL), unchecked = setOf(ProbeKind.BIGINT),
        read = { s, q -> s.readOne<Float>(q) },
        canon = { probeNumber(IonBigDecimal.fromFloat(it as Float).toStringExpanded()) }),
    ProbeType("Double", NUMERIC, read = { s, q -> s.readOne<Double>(q) },
        canon = { probeNumber(IonBigDecimal.fromDouble(it as Double).toStringExpanded()) }),
    ProbeType("ion.BigDecimal", NUMERIC, read = { s, q -> s.readOne<IonBigDecimal>(q) },
        canon = { probeNumber((it as IonBigDecimal).toStringExpanded()) }),
    ProbeType("Boolean", setOf(ProbeKind.BOOLEAN), read = { s, q -> s.readOne<Boolean>(q) }, canon = { it.toString() }),
    ProbeType("Char", setOf(ProbeKind.CHAR), read = { s, q -> s.readOne<Char>(q) }, canon = { it.toString() }),
    ProbeType("String", setOf(ProbeKind.TEXT, ProbeKind.CHAR, ProbeKind.UUID),
        unchecked = setOf(ProbeKind.INTEGRAL, ProbeKind.BIGINT, ProbeKind.FRACTIONAL, ProbeKind.DATE, ProbeKind.DATETIME, ProbeKind.TIME),
        read = { s, q -> s.readOne<String>(q) }, canon = { it.toString() }),
    ProbeType("CharArray", setOf(ProbeKind.TEXT, ProbeKind.CHAR), read = { s, q -> s.readOne<CharArray>(q) },
        canon = { (it as CharArray).concatToString() }),
    ProbeType("ByteArray", setOf(ProbeKind.BYTES), read = { s, q -> s.readOne<ByteArray>(q) }, canon = { hex(it as ByteArray) }),
    ProbeType("kotlinx.LocalDate", PROBE_DATE_KINDS, unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<LocalDate>(q) }, canon = { it.toString() }),
    ProbeType("kotlinx.LocalDateTime", PROBE_DATETIME_KINDS, read = { s, q -> s.readOne<LocalDateTime>(q) }, canon = {
        val t = it as LocalDateTime
        probeDateTime(t.year, t.month.ordinal + 1, t.day, t.hour, t.minute, t.second, t.nanosecond)
    }),
    ProbeType("kotlinx.LocalTime", setOf(ProbeKind.TIME), unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<LocalTime>(q) }, canon = { (it as LocalTime).let { t -> "${t.hour.pad()}:${t.minute.pad()}:${t.second.pad()}" } }),
    // An instant is read from a column without a zone; its value depends on the zone of
    // the session, so only that it reads is checked.
    ProbeType("kotlin.time.Instant", emptySet(), unchecked = PROBE_DATETIME_KINDS,
        read = { s, q -> s.readOne<Instant>(q) }, canon = { it.toString() }),
    ProbeType("kotlin.uuid.Uuid", setOf(ProbeKind.UUID), read = { s, q -> s.readOne<Uuid>(q) }, canon = { it.toString() }),
)

// --- The run ---

/** The column's value as [type] renders it: a date read as a date-time is at midnight. */
private fun expected(type: ProbeType, column: ProbeColumn): String =
    if (column.kind == ProbeKind.DATE && ProbeKind.DATETIME in type.kinds) "${column.value}T00:00:00.000"
    else column.value

private fun runTypeProbe(s: Stormify) {
    val columns = probeColumns()
    TestDDL.dropTable("type_probe")
    s.executeUpdate(TestDDL.createTable("type_probe", columns.joinToString(", ") { "${it.name} ${it.ddl}" }))
    s.executeUpdate(
        "INSERT INTO type_probe (${columns.joinToString(", ") { it.name }}) " +
                "VALUES (${columns.joinToString(", ") { it.literal }})"
    )
    val db = TestDDL.dialect.name
    val findings = mutableListOf<String>()
    var cells = 0
    try {
        for (type in COMMON_TYPES + TypeProbe.platformTypes)
            for (column in columns) {
                val checked = column.kind in type.kinds
                if (!checked && column.kind !in type.unchecked) continue
                cells++
                TypeProbe.startRecording()
                val result = runCatching { type.read(s, "SELECT ${column.name} FROM type_probe") }
                val rejected = TypeProbe.recordedRejections()
                val cell = "${type.name} <- ${column.name} ${column.ddl}"
                val status = result.fold(
                    onSuccess = { v ->
                        val got = v?.let { runCatching { type.canon(it) }.getOrElse { e -> "unrenderable ${it::class.simpleName}: ${e.message}" } }
                        when {
                            v == null -> "WRONG null"
                            checked && got != expected(type, column) -> "WRONG $got, expected ${expected(type, column)}"
                            else -> "OK $got"
                        }
                    },
                    onFailure = { "FAILED ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}" }
                )
                val line = if (rejected.isEmpty()) status else "$status; REJECTED by driver ${rejected.joinToString(" / ")}"
                println("PROBE|$db|$cell|$line")
                if (!line.startsWith("OK") || rejected.isNotEmpty()) findings += "$cell: $line"
            }
    } finally {
        TestDDL.dropTable("type_probe")
    }
    println("PROBE|$db|$cells cells, ${findings.size} findings")
    if (findings.isNotEmpty()) fail("Type probe on $db, ${findings.size} findings:\n" + findings.joinToString("\n"))
}
