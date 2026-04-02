package onl.ycode.stormify.pojos;

import onl.ycode.stormify.DbTable;

@DbTable(name = "user_entity")
public class UserEntity extends BaseEntity {
    private String name;
    private String email;

    public UserEntity() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @Override
    public String toString() {
        return "UserEntity(id=" + getId() + ", name=" + name + ", email=" + email + ", createdBy=" + getCreatedBy() + ")";
    }
}
