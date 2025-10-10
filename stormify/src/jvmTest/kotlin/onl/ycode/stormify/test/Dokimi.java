package onl.ycode.stormify.test;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.StormifyJ;

import java.util.List;

public class Dokimi {
    public static void main(String[] args) {

        StormifyJ st = new StormifyJ(null);

        st.read(Integer.class, "select * from table where id = ?", 1);
        st.readCursor(int.class, "select * from table where id = ?", q -> {
            throw new Exception();
        }, 3, 4, 6);

        st.getDetails("parent", String.class);
        st.getDetails("parent", String.class, "name");

        st.transaction(tc -> {
            tc.executeUpdate("insert into table (id, name) values (?, ?)", 1, "name");
            tc.transaction(() -> {
                List<Integer> read = tc.read(Integer.class, "select * from table where id = ?", 1);
                throw new Exception();
            });
            throw new Exception();
        });


        AutoTable q = new AutoTable() {
        };
        System.out.println(q);
    }
}
