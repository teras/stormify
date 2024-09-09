// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

import java.time.LocalDate;

@DbTable(name = "time")
public class Time2 implements CRUDTable {
    private final int id;
    private final LocalDate time;

    public Time2(int id, LocalDate time) {
        this.id = id;
        this.time = time;
    }

    public int getId() {
        return id;
    }

    public LocalDate getTime() {
        return time;
    }
}

