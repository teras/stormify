package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;
import onl.ycode.stormify.DbTable;

@DbTable(name = "annotated_test")
public class AnnotatedEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;

    @DbField(name = "custom_col")
    private String renamedField;

    @DbField(creatable = false)
    private String readOnlyOnCreate;

    @DbField(updatable = false)
    private String readOnlyOnUpdate;

    private transient String transientByKeyword;

    @javax.persistence.Transient
    private String transientByAnnotation;

    public AnnotatedEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getRenamedField() { return renamedField; }
    public void setRenamedField(String renamedField) { this.renamedField = renamedField; }
    public String getReadOnlyOnCreate() { return readOnlyOnCreate; }
    public void setReadOnlyOnCreate(String readOnlyOnCreate) { this.readOnlyOnCreate = readOnlyOnCreate; }
    public String getReadOnlyOnUpdate() { return readOnlyOnUpdate; }
    public void setReadOnlyOnUpdate(String readOnlyOnUpdate) { this.readOnlyOnUpdate = readOnlyOnUpdate; }
    public String getTransientByKeyword() { return transientByKeyword; }
    public void setTransientByKeyword(String v) { this.transientByKeyword = v; }
    public String getTransientByAnnotation() { return transientByAnnotation; }
    public void setTransientByAnnotation(String v) { this.transientByAnnotation = v; }
}
