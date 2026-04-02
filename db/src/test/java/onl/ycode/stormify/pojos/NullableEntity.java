package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;
import onl.ycode.stormify.DbTable;

@DbTable(name = "nullable_test")
public class NullableEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private String name;
    private Integer nullableInt;
    private String nullableString;

    public NullableEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getNullableInt() { return nullableInt; }
    public void setNullableInt(Integer nullableInt) { this.nullableInt = nullableInt; }
    public String getNullableString() { return nullableString; }
    public void setNullableString(String nullableString) { this.nullableString = nullableString; }
}
