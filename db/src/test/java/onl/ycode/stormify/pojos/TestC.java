// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

@DbTable(name = "test")
public class TestC extends AutoTable implements CRUDTable {
    private int id = 0;
    private String name = null;

    public TestC() {
    }

    public TestC(int id) {
        this.id = id;
    }

    public TestC(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "TestC(" +
                "id=" + id +
                ", name=" + name +
                ')';
    }
}
