// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package org.ycode.tmaker.test;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.DbField;

import java.sql.Timestamp;

class Master extends AutoTable {
    @DbField(primaryKey = true)
    int id = 0;
    private String type;
    private int value;
    private Timestamp timestamp;

    Master(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Timestamp timestamp) {
        this.timestamp = timestamp;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
