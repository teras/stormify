// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;

import java.time.LocalDateTime;

public class Time implements CRUDTable {
    private Integer id = null;
    private LocalDateTime time = null;

    public Time() {
    }

    public Time(Integer id, LocalDateTime time) {
        this.id = id;
        this.time = time;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public void setTime(LocalDateTime time) {
        this.time = time;
    }

    @Override
    public String toString() {
        return "Time(" +
                "id=" + id +
                ", time=" + time +
                ')';
    }
}
