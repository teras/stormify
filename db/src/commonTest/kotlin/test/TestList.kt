// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test


import onl.ycode.tmaker.CustomReference
import onl.ycode.tmaker.FilterReference
import onl.ycode.tmaker.FilteredList
import onl.ycode.tmaker.TableReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tables.T

internal class TestList {
//    @Test
//    fun testList() {
//        val list: FilteredList<DetailDetail> = FilteredList(DetailDetail::class.java)
//        val f1: FilterReference = list.addFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.type)
//        val f2: FilterReference = list.addFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.value)
//        val f3: FilterReference =
//            list.addAlsoWithFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.timestamp)
//        val f4: FilterReference = list.addFilter(T.DetailDetail.toDetail2, T.Detail.toMaster1, T.Master.timestamp)
//        val f5: FilterReference = list.addFilter(T.DetailDetail.id)
//        val c1: CustomReference = list.addCustomReference(T.DetailDetail.toDetail2)
//        val t1: TableReference = list.addSortingOrder(true, T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.type)
//        val t2: TableReference = list.addSortingOrder(true, T.DetailDetail.toDetail1, T.Detail.toMaster2, T.Master.type)
//
//        f5.setSimpleValueConverter { s -> s.length() - 2 }
//
//        assertEquals("t3", c1.getTableAlias())
//        assertEquals("t2", t1.getTableAlias())
//        c1.setActivated(true)
//        assertNull(f1.setValue("Hello"))
//        assertNull(f2.setValue("34.6 ... 78.9"))
//        assertNull(f3.setValue("<2024-01-02T05:02:01"))
//        assertNull(f4.setValue("<= 2024-01-02"))
//        assertNull(f5.setValue(""))
//    }
}