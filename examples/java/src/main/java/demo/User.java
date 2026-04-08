package demo;

import onl.ycode.stormify.AutoTable;
import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

/**
 * User entity using JPA annotations and AutoTable.
 * Stormify supports JPA annotations on the JVM, so you can use the same
 * entity classes you would use with Hibernate or any other JPA provider.
 *
 * <p>Non-primary-key getters/setters call {@code populate()} to trigger lazy loading
 * when the entity was obtained as a reference from another entity's foreign key.
 */
public class User extends AutoTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    private String email;

    public User() {
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { populate(); return name; }
    public void setName(String name) { populate(); this.name = name; }

    public String getEmail() { populate(); return email; }
    public void setEmail(String email) { populate(); this.email = email; }

    @Override
    public String toString() {
        return "User(id=" + id + ", name=" + getName() + ", email=" + getEmail() + ")";
    }
}
