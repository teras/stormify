package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;

public class StressTable implements CRUDTable {
    @DbField(primaryKey = true)
    private Integer id;
    private String data;

    public StressTable() {
    }

    public StressTable(Integer id) {
        this.id = id;
    }

    public StressTable(Integer id, String data) {
        this.id = id;
        this.data = data;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}

