package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;

public class CamelPolicyEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private String firstName;
    private String lastName;

    public CamelPolicyEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
}
