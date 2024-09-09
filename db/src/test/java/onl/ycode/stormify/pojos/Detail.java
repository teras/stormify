// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.pojos;

public class Detail {
    private Master parent = null;
    private Master parent2 = null;

    public Master getParent() {
        return parent;
    }

    public void setParent(Master parent) {
        this.parent = parent;
    }

    public Master getParent2() {
        return parent2;
    }

    public void setParent2(Master parent2) {
        this.parent2 = parent2;
    }
}
