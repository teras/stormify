// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;

public class DualKey implements CRUDTable {
    private int id1 = 0;
    private int id2 = 0;
    private String data = "";

    public DualKey() {
    }

    public DualKey(int id1, int id2) {
        this.id1 = id1;
        this.id2 = id2;
    }

    public DualKey(int id1, int id2, String data) {
        this.id1 = id1;
        this.id2 = id2;
        this.data = data;
    }

    public int getId1() {
        return id1;
    }

    public void setId1(int id1) {
        this.id1 = id1;
    }

    public int getId2() {
        return id2;
    }

    public void setId2(int id2) {
        this.id2 = id2;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "DualKey(" +
                "id1=" + id1 +
                ", id2=" + id2 +
                ", data=" + data +
                ')';
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (obj == this)
            return true;
        if (obj.getClass() != this.getClass())
            return false;
        DualKey other = (DualKey) obj;
        return id1 == other.id1 && id2 == other.id2;
    }
}
