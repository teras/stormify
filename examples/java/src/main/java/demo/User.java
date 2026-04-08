package demo;

import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

/**
 * User entity — a plain class without AutoTable.
 * When obtained as a reference (e.g. from Task.user), only the ID is set.
 * Call stormify.populate(user) explicitly to load the remaining fields.
 *
 * <p>Compare with Task, which extends AutoTable and loads fields automatically.
 */
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;

    private String email;

    public User() {
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    @Override
    public String toString() {
        return "User(id=" + id + ", name=" + name + ", email=" + email + ")";
    }
}
