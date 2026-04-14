package test

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
@RunWith(RobolectricTestRunner::class) class AndroidSuspendTransactionTest : SuspendTransactionTest() {
    // Android's SQLiteConnectionPool does not evict a connection whose transaction
    // was interrupted via coroutine cancellation, so a follow-up read would hang
    // forever in waitForConnection(). The same test still runs on JVM and native.
    @org.junit.Ignore("Platform limitation: Android SQLiteConnectionPool holds cancelled transaction connection")
    override fun cancellationRollsBackTransaction() { /* skipped on Android */ }
}
@RunWith(RobolectricTestRunner::class) class AndroidTemporalConversionTest : TemporalConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidPagedListTest : PagedListTest()
@RunWith(RobolectricTestRunner::class) class AndroidProcedureTest : ProcedureTest()

// --- jvmBasedTest classes (shared JVM/Android, need Robolectric on Android) ---
@RunWith(RobolectricTestRunner::class) class AndroidJavaTemporalConversionTest : JavaTemporalConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidReflectionTest : onl.ycode.stormify.test.ReflectionTest()
// PagedListPathTest: KSP-generated Paths resolve per-target — jvmBasedTest
// can't see them. The underlying PagedList functionality is fully covered by
// AndroidPagedListTest (119 tests); the path tests add type-safe column
// resolution which is platform-independent.
