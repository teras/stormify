// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
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
@RunWith(RobolectricTestRunner::class) class AndroidAmbientInstanceTest : AmbientInstanceTest()
@RunWith(RobolectricTestRunner::class) class AndroidCrossFacetFilterValuesTest : CrossFacetFilterValuesTest()
@RunWith(RobolectricTestRunner::class) class AndroidQueryCancelTest : QueryCancelTest() {
    override val cancelSupported = false
}
@RunWith(RobolectricTestRunner::class) class AndroidQueryTest : QueryTest()
@RunWith(RobolectricTestRunner::class) class AndroidTransactionTest : TransactionTest()
@RunWith(RobolectricTestRunner::class) class AndroidEnumTest : EnumTest()
@RunWith(RobolectricTestRunner::class) class AndroidConfigTest : ConfigTest()
@RunWith(RobolectricTestRunner::class) class AndroidTypesTest : TypesTest()
@RunWith(RobolectricTestRunner::class) class AndroidAutocommitTest : AutocommitTest()
@RunWith(RobolectricTestRunner::class) class AndroidAutoTableLazyLoadTest : AutoTableLazyLoadTest()
@RunWith(RobolectricTestRunner::class) class AndroidEncodingTest : EncodingTest()
@RunWith(RobolectricTestRunner::class) class AndroidBooleanTokenTest : BooleanTokenTest()
@RunWith(RobolectricTestRunner::class) class AndroidReadAgreementTest : ReadAgreementTest()
@RunWith(RobolectricTestRunner::class) class AndroidReadMatrixTest : ReadMatrixTest()
@RunWith(RobolectricTestRunner::class) class AndroidLazyDetailsBatchTest : LazyDetailsBatchTest()
@RunWith(RobolectricTestRunner::class) class AndroidLargeTextRowTest : LargeTextRowTest()
@RunWith(RobolectricTestRunner::class) class AndroidStringPkTest : StringPkTest()
@RunWith(RobolectricTestRunner::class) class AndroidSuspendTransactionTest : SuspendTransactionTest()
@RunWith(RobolectricTestRunner::class) class AndroidTemporalConversionTest : TemporalConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidBookkeepingColumnsTest : BookkeepingColumnsTest()
@RunWith(RobolectricTestRunner::class) class AndroidCrudTableTest : CrudTableTest()
@RunWith(RobolectricTestRunner::class) class AndroidDecimalKeyBatchTest : DecimalKeyBatchTest()
@RunWith(RobolectricTestRunner::class) class AndroidDynamicUpdateTest : DynamicUpdateTest()
@RunWith(RobolectricTestRunner::class) class AndroidEscapedNameTest : EscapedNameTest()
@RunWith(RobolectricTestRunner::class) class AndroidFacetConverterTest : FacetConverterTest()
@RunWith(RobolectricTestRunner::class) class AndroidFilterRawValuesTest : FilterRawValuesTest()
@RunWith(RobolectricTestRunner::class) class AndroidFilterValuesToStringTest : FilterValuesToStringTest()
@RunWith(RobolectricTestRunner::class) class AndroidFluentPagedListTest : FluentPagedListTest()
@RunWith(RobolectricTestRunner::class) class AndroidHydrationFailureTest : HydrationFailureTest()
@RunWith(RobolectricTestRunner::class) class AndroidKotlinUuidConversionTest : KotlinUuidConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidNestedEntityTest : NestedEntityTest()
@RunWith(RobolectricTestRunner::class) class AndroidNestedTransactionTest : NestedTransactionTest()
@RunWith(RobolectricTestRunner::class) class AndroidOrphanReferenceTest : OrphanReferenceTest()
@RunWith(RobolectricTestRunner::class) class AndroidPageBoundsBindTest : PageBoundsBindTest()
@RunWith(RobolectricTestRunner::class) class AndroidPageSpecJsonTest : PageSpecJsonTest()
@RunWith(RobolectricTestRunner::class) class AndroidPagedQueryTest : PagedQueryTest()
@RunWith(RobolectricTestRunner::class) class AndroidPgBinaryDecodeTest : PgBinaryDecodeTest()
@RunWith(RobolectricTestRunner::class) class AndroidPrimaryKeyMappingTest : PrimaryKeyMappingTest()
@RunWith(RobolectricTestRunner::class) class AndroidReadFidelityTest : ReadFidelityTest()
@RunWith(RobolectricTestRunner::class) class AndroidReadOnlyPropertyTest : ReadOnlyPropertyTest()
@RunWith(RobolectricTestRunner::class) class AndroidReadRowsTest : ReadRowsTest()
@RunWith(RobolectricTestRunner::class) class AndroidSQLExceptionFieldsTest : SQLExceptionFieldsTest()
@RunWith(RobolectricTestRunner::class) class AndroidSharedColumnTest : SharedColumnTest()
@RunWith(RobolectricTestRunner::class) class AndroidStatementCacheTest : StatementCacheTest()
@RunWith(RobolectricTestRunner::class) class AndroidSuspendStressTest : SuspendStressTest()
@RunWith(RobolectricTestRunner::class) class AndroidSuspendWithConnectionTest : SuspendWithConnectionTest()
@RunWith(RobolectricTestRunner::class) class AndroidTableRefTest : TableRefTest()
@RunWith(RobolectricTestRunner::class) class AndroidTextFoldingTest : TextFoldingTest()
@RunWith(RobolectricTestRunner::class) class AndroidTransactionExtensionsTest : TransactionExtensionsTest()
@RunWith(RobolectricTestRunner::class) class AndroidTypeMatrixTest : TypeMatrixTest()
@RunWith(RobolectricTestRunner::class)
class AndroidPagedListTest : PagedListTest() {
    // Robolectric uses sqlite4java (bundled SQLite ~3.24) which does not apply
    // numeric affinity to bound TEXT parameters when the other operand is a
    // computed expression like (col * 2). Real Android's system SQLite and every
    // other supported DB handle this correctly.
    @Test @Ignore("sqlite4java: no text→numeric affinity on computed expression RHS")
    override fun testSqlFacetNumericComputed() {}

    @Test @Ignore("sqlite4java: no text→numeric affinity on computed expression RHS")
    override fun testSqlFacetNumericRange() {}
}
@RunWith(RobolectricTestRunner::class) class AndroidProcedureTest : ProcedureTest()
@RunWith(RobolectricTestRunner::class) class AndroidCursorStreamingTest : CursorStreamingTest()

// jvmBasedTest classes — shared JVM/Android, need Robolectric on Android.
@RunWith(RobolectricTestRunner::class) class AndroidJavaTemporalConversionTest : JavaTemporalConversionTest()
@RunWith(RobolectricTestRunner::class) class AndroidReflectionTest : onl.ycode.stormify.test.ReflectionTest()
