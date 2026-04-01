package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

/**
 * A POJO that maps to a custom query result, not a real table.
 * Used to test on-the-fly / aliased column mapping.
 * Has fields that only exist in custom SELECT queries, not in any single table.
 */
@DbTable(name = "fly_test")
public class FlyView implements CRUDTable {
    private int id;
    private String name;
    private String label;  // on-the-fly field: comes from "SELECT name as label"

    public FlyView() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return "FlyView(id=" + id + ", name=" + name + ", label=" + label + ")";
    }
}
