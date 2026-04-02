package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;

public class BaseEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private String createdBy;

    public BaseEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(id=" + id + ", createdBy=" + createdBy + ")";
    }
}
