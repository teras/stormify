// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

@DbTable(name = "test")
public class Test2 implements CRUDTable {
    private Integer id = null;
    private String name = null;
    private String extra = null;

    public Test2() {}

    public Test2(Integer id, String name, String extra) {
        this.id = id;
        this.name = name;
        this.extra = extra;
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

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    @Override
    public String toString() {
        return "Test2(" +
                "id=" + id +
                ", name=" + name +
                ", extra=" + extra +
                ')';
    }
}
