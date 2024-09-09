// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.CRUDTable;

public class AutoIncrement extends AutoTable implements CRUDTable {
    private String name = null;
    private int id = 0;

    public AutoIncrement() {
    }

    public AutoIncrement(String name) {
        this.name = name;
    }

    public AutoIncrement(String name, int id) {
        this.name = name;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "AutoIncrement(id=" + id + ", name=" + name + ")";
    }
}
