// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test;

import onl.ycode.stormify.DbTable;

/** The child of {@link JOrder}. */
@DbTable(name = "java_item")
public class JItem {
    private Integer id;
    private JOrder parent;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public JOrder getParent() { return parent; }
    public void setParent(JOrder parent) { this.parent = parent; }
}
