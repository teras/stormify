// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package org.ycode.tmaker.test;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.DbField;

class Detail extends AutoTable {
    @DbField(primaryKey = true)
    int id = 0;
    private String type;
    private Master toMaster1;
    private Master toMaster2;

    public Detail(String type, Master parent1, Master parent2) {
        this.type = type;
        this.toMaster1 = parent1;
        this.toMaster2 = parent2;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Master getToMaster1() {
        return toMaster1;
    }

    public void setToMaster1(Master toMaster1) {
        this.toMaster1 = toMaster1;
    }

    public Master getToMaster2() {
        return toMaster2;
    }

    public void setToMaster2(Master toMaster2) {
        this.toMaster2 = toMaster2;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
