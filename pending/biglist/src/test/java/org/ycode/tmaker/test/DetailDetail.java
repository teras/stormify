// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package org.ycode.tmaker.test;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.DbField;

class DetailDetail extends AutoTable {
    @DbField(primaryKey = true)
    int id = 0;
    private String type;
    private Detail toDetail1;
    private Detail toDetail2;

    public DetailDetail(String type, Detail toDetail1, Detail toDetail2) {
        this.type = type;
        this.toDetail1 = toDetail1;
        this.toDetail2 = toDetail2;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Detail getToDetail1() {
        return toDetail1;
    }

    public void setToDetail1(Detail toDetail1) {
        this.toDetail1 = toDetail1;
    }

    public Detail getToDetail2() {
        return toDetail2;
    }

    public void setToDetail2(Detail toDetail2) {
        this.toDetail2 = toDetail2;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
