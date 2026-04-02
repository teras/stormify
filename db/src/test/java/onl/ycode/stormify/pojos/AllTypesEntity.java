package onl.ycode.stormify.pojos;

import onl.ycode.stormify.CRUDTable;
import onl.ycode.stormify.DbField;
import onl.ycode.stormify.DbTable;

import java.math.BigDecimal;
import java.math.BigInteger;

@DbTable(name = "all_types")
public class AllTypesEntity implements CRUDTable {
    @DbField(primaryKey = true)
    private int id;
    private byte byteVal;
    private short shortVal;
    private int intVal;
    private long longVal;
    private float floatVal;
    private double doubleVal;
    private boolean boolVal;
    private String stringVal;
    private BigDecimal bigDecimalVal;
    private BigInteger bigIntegerVal;

    public AllTypesEntity() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public byte getByteVal() { return byteVal; }
    public void setByteVal(byte byteVal) { this.byteVal = byteVal; }
    public short getShortVal() { return shortVal; }
    public void setShortVal(short shortVal) { this.shortVal = shortVal; }
    public int getIntVal() { return intVal; }
    public void setIntVal(int intVal) { this.intVal = intVal; }
    public long getLongVal() { return longVal; }
    public void setLongVal(long longVal) { this.longVal = longVal; }
    public float getFloatVal() { return floatVal; }
    public void setFloatVal(float floatVal) { this.floatVal = floatVal; }
    public double getDoubleVal() { return doubleVal; }
    public void setDoubleVal(double doubleVal) { this.doubleVal = doubleVal; }
    public boolean isBoolVal() { return boolVal; }
    public void setBoolVal(boolean boolVal) { this.boolVal = boolVal; }
    public String getStringVal() { return stringVal; }
    public void setStringVal(String stringVal) { this.stringVal = stringVal; }
    public BigDecimal getBigDecimalVal() { return bigDecimalVal; }
    public void setBigDecimalVal(BigDecimal bigDecimalVal) { this.bigDecimalVal = bigDecimalVal; }
    public BigInteger getBigIntegerVal() { return bigIntegerVal; }
    public void setBigIntegerVal(BigInteger bigIntegerVal) { this.bigIntegerVal = bigIntegerVal; }
}
