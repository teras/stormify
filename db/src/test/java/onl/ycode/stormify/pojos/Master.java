// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;

import java.util.List;

public class Master implements CRUDTable {
    private List<Detail> children = null;

    public List<Detail> getChildren() {
        return children;
    }

    public void setChildren(List<Detail> children) {
        this.children = children;
    }
}
