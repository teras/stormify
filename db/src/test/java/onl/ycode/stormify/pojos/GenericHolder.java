package onl.ycode.stormify.pojos;

import onl.ycode.stormify.DbField;
import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

@DbTable(name = "generic_test")
public class GenericHolder<T> implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private T value;

    public GenericHolder() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public T getValue() { return value; }
    public void setValue(T value) { this.value = value; }

    @Override
    public String toString() {
        return "GenericHolder(id=" + id + ", value=" + value + ")";
    }
}
