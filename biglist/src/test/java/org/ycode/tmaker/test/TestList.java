// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package org.ycode.tmaker.test;


import onl.ycode.tmaker.CustomReference;
import onl.ycode.tmaker.FilterReference;
import onl.ycode.tmaker.FilteredList;
import onl.ycode.tmaker.TableReference;
import org.junit.jupiter.api.Test;
import tables.T;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


class TestList {
    @Test
    void testList() {
        FilteredList<DetailDetail> list = new FilteredList<>(DetailDetail.class);
        FilterReference f1 = list.addFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.type);
        FilterReference f2 = list.addFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.value);
        FilterReference f3 = list.addAlsoWithFilter(T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.timestamp);
        FilterReference f4 = list.addFilter(T.DetailDetail.toDetail2, T.Detail.toMaster1, T.Master.timestamp);
        FilterReference f5 = list.addFilter(T.DetailDetail.id);
        CustomReference c1 = list.addCustomReference(T.DetailDetail.toDetail2);
        TableReference t1 = list.addSortingOrder(true, T.DetailDetail.toDetail1, T.Detail.toMaster1, T.Master.type);
        TableReference t2 = list.addSortingOrder(true, T.DetailDetail.toDetail1, T.Detail.toMaster2, T.Master.type);

        f5.setSimpleValueConverter(s -> s.length() - 2);

        assertEquals("t3", c1.getTableAlias());
        assertEquals("t2", t1.getTableAlias());
        c1.setActivated(true);
        assertNull(f1.setValue("Hello"));
        assertNull(f2.setValue("34.6 ... 78.9"));
        assertNull(f3.setValue("<2024-01-02T05:02:01"));
        assertNull(f4.setValue("<= 2024-01-02"));
        assertNull(f5.setValue(""));
    }
}