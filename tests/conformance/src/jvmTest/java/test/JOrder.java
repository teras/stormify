// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.DbTable;

import java.util.List;

/** A Java entity whose children load through {@code details}, as {@code by lazyDetails()} does. */
@DbTable(name = "java_order")
public class JOrder extends AutoTable {
    private Integer id;
    private List<JItem> items;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public List<JItem> getItems() {
        if (items == null) items = details(JItem.class);
        return items;
    }
}
