package test

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric wrappers for the common test suite.
 *
 * Each common test class needs a trivial subclass annotated with
 * [RobolectricTestRunner] so that Android's SQLiteDatabase is backed by
 * a real SQLite implementation (provided by Robolectric) instead of the
 * stub classes in android.jar.
 *
 * When a new common test class is added, add a one-liner here.
 * All @Test methods are inherited automatically.
 */
@RunWith(RobolectricTestRunner::class) class AndroidCrudTest : CrudTest()
@RunWith(RobolectricTestRunner::class) class AndroidQueryTest : QueryTest()
@RunWith(RobolectricTestRunner::class) class AndroidTransactionTest : TransactionTest()
@RunWith(RobolectricTestRunner::class) class AndroidEnumTest : EnumTest()
@RunWith(RobolectricTestRunner::class) class AndroidConfigTest : ConfigTest()
@RunWith(RobolectricTestRunner::class) class AndroidTypesTest : TypesTest()
@RunWith(RobolectricTestRunner::class) class AndroidAutocommitTest : AutocommitTest()
@RunWith(RobolectricTestRunner::class) class AndroidAutoTableLazyLoadTest : AutoTableLazyLoadTest()
@RunWith(RobolectricTestRunner::class) class AndroidEncodingTest : EncodingTest()
@RunWith(RobolectricTestRunner::class) class AndroidStringPkTest : StringPkTest()
@RunWith(RobolectricTestRunner::class) class AndroidSuspendTransactionTest : SuspendTransactionTest()
@RunWith(RobolectricTestRunner::class) class AndroidTemporalConversionTest : TemporalConversionTest()
@RunWith(RobolectricTestRunner::class)
class AndroidPagedListTest : PagedListTest() {
    // Robolectric uses sqlite4java (bundled SQLite ~3.24) which does not apply
    // numeric affinity to bound TEXT parameters when the other operand is a
    // computed expression like (col * 2). Real Android's system SQLite and every
    // other supported DB handle this correctly — verified in the probe.
    @Test @Ignore("sqlite4java: no text→numeric affinity on computed expression RHS")
    override fun testSqlFacetNumericComputed() {}

    @Test @Ignore("sqlite4java: no text→numeric affinity on computed expression RHS")
    override fun testSqlFacetNumericRange() {}
}
@RunWith(RobolectricTestRunner::class) class AndroidProcedureTest : ProcedureTest()

// --- jvmBasedTest classes (shared JVM/Android, need Robolectric on Android) ---
@RunWith(RobolectricTestRunner::class) class AndroidJavaTemporalConversionTest : JavaTemporalConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidReflectionTest : onl.ycode.stormify.test.ReflectionTest()
// PagedListPathTest: KSP-generated Paths resolve per-target — jvmBasedTest
// can't see them. The underlying PagedList functionality is fully covered by
// AndroidPagedListTest (119 tests); the path tests add type-safe column
// resolution which is platform-independent.
