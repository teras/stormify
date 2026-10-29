// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test;

import onl.ycode.stormify.Stormify;
import onl.ycode.stormify.StormifyJ;
import onl.ycode.stormify.TableInfo;
import onl.ycode.stormify.biglist.FilterSyntax;
import onl.ycode.stormify.biglist.PagedList;

import java.util.ArrayList;
import java.util.List;

/** The Java side of the API, written as a Java user writes it. */
public final class JavaApiProbe {
    private JavaApiProbe() {}

    /** The item count of every order, loaded through {@code details}. */
    public static List<Integer> itemCounts(StormifyJ stormify) {
        List<Integer> counts = new ArrayList<>();
        for (JOrder order : stormify.findAll(JOrder.class, "ORDER BY id")) counts.add(order.getItems().size());
        return counts;
    }

    /** The ids streamed by {@code forEachStreaming} with a plain Java lambda. */
    public static List<Integer> streamedIds(StormifyJ stormify) {
        List<Integer> ids = new ArrayList<>();
        PagedList<JOrder> list = stormify.pagedList(JOrder.class);
        list.forEachStreaming(order -> ids.add(order.getId()));
        return ids;
    }

    /** The ids read with a cursor of one row per round trip. */
    public static List<Integer> cursorIds(StormifyJ stormify) {
        List<Integer> ids = new ArrayList<>();
        stormify.readCursor(JOrder.class, "SELECT * FROM java_order ORDER BY id", 1, order -> ids.add(order.getId()));
        stormify.readRowsCursor("SELECT id FROM java_order ORDER BY id", 1, row -> {});
        return ids;
    }

    /** The table of an entity, typed by its class. */
    public static String tableName(StormifyJ stormify) {
        TableInfo<JOrder> info = stormify.getTableInfo(JOrder.class);
        return info.getTableName();
    }

    /** A filter syntax given only its first parameter. */
    public static FilterSyntax orOnly() {
        return new FilterSyntax("|");
    }

    /** The Kotlin instance behind a Java wrapper, and a wrapper of a Kotlin instance. */
    public static boolean sharesTheInstance(Stormify kotlin) {
        StormifyJ wrapper = new StormifyJ(kotlin);
        kotlin.asDefault();
        return wrapper.getStormify() == kotlin && StormifyJ.getDefault().getStormify() == kotlin;
    }
}
