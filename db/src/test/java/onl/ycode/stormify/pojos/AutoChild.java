package onl.ycode.stormify.pojos;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

@DbTable(name = "child")
public class AutoChild extends AutoTable implements CRUDTable {
    private Integer id;
    private String data; // Assuming `db("")` translates to handling as a String with default empty value
    private AutoParent parent; // Assuming `db(null)` translates to handling with nullable parent

    // Getter and Setter methods for id
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    // Getter and Setter methods for data
    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    // Getter and Setter methods for parent
    public AutoParent getParent() {
        return parent;
    }

    public void setParent(AutoParent parent) {
        this.parent = parent;
    }
}
