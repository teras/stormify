package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;
import onl.ycode.stormify.DbTable;

@DbTable(name = "blacklist_test")
public class BlacklistEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private String name;
    private String secret;

    public BlacklistEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
}
