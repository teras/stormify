package onl.ycode.stormify.pojos;

import onl.ycode.stormify.AutoTable;
import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbTable;

import java.util.List;

@DbTable(name = "parent")
public class AutoParent extends AutoTable implements CRUDTable {
    private Integer id;
    private String other;
    private List<AutoChild> children; // Use appropriate lazy loading mechanism in Java
    public String data;

    // Getter and Setter methods for id
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    // Getter and Setter methods for data
    public String getData() {
        autoPopulate();
        return data;
    }

    public void setData(String data) {
        autoPopulate();
        this.data = data;
    }

    // Getter and Setter methods for other
    public String getOther() {
        autoPopulate();
        return other;
    }

    public void setOther(String other) {
        autoPopulate();
        this.other = other;
    }

    public List<AutoChild> getChildren() {
        if (children == null)
            children = getDetails(AutoChild.class);
        return children;
    }

    public void setChildren(List<AutoChild> children) {
        this.children = children;
    }
}