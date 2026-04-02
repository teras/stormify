package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;
import onl.ycode.stormify.DbTable;

@DbTable(name = "blob_test")
public class BlobEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private byte[] blobData;
    private char[] clobAsChars;
    private String clobAsString;

    public BlobEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public byte[] getBlobData() { return blobData; }
    public void setBlobData(byte[] blobData) { this.blobData = blobData; }
    public char[] getClobAsChars() { return clobAsChars; }
    public void setClobAsChars(char[] clobAsChars) { this.clobAsChars = clobAsChars; }
    public String getClobAsString() { return clobAsString; }
    public void setClobAsString(String clobAsString) { this.clobAsString = clobAsString; }
}
