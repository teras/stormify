// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;

public class Child implements CRUDTable {
    private Integer id = null;
    private String name = null;
    private TestC parent = null;

    public Child() {}

    public Child(Integer id, String name, TestC parent) {
        this.id = id;
        this.name = name;
        this.parent = parent;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TestC getParent() {
        return parent;
    }

    public void setParent(TestC parent) {
        this.parent = parent;
    }

    @Override
    public String toString() {
        return "Child(" +
                "id=" + id +
                ", name=" + name +
                ", parent=" + parent +
                ')';
    }
}
