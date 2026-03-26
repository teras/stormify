// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static onl.ycode.stormify.StormifyManager.stormify;

class SiblingGroup {
    static final int DEFAULT_BATCH_SIZE = 32;

    private final List<WeakReference<AutoTable>> members = new ArrayList<>();

    void add(AutoTable member) {
        members.add(new WeakReference<>(member));
    }

    synchronized void batchPopulate(AutoTable trigger) {
        if (trigger.siblingGroup != this)
            return;
        trigger.siblingGroup = null;
        List<AutoTable> toPopulate = new ArrayList<>();
        toPopulate.add(trigger);
        for (WeakReference<AutoTable> ref : members) {
            if (toPopulate.size() >= DEFAULT_BATCH_SIZE)
                break;
            AutoTable member = ref.get();
            if (member != null && member != trigger && member.siblingGroup == this) {
                member.markPopulated();
                member.siblingGroup = null;
                toPopulate.add(member);
            }
        }
        if (toPopulate.size() == 1)
            stormify().forcePopulate(trigger);
        else
            stormify().batchPopulate(toPopulate);
    }
}
