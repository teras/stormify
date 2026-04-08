package demo;

import onl.ycode.stormify.DbValue;

/**
 * Task priority with custom database values via {@link DbValue}.
 * Without DbValue, the ordinal (0, 1, 2) would be stored instead.
 */
public enum Priority implements DbValue {
    LOW(10),
    MEDIUM(20),
    HIGH(30);

    private final int dbValue;

    Priority(int dbValue) {
        this.dbValue = dbValue;
    }

    @Override
    public int getDbValue() {
        return dbValue;
    }
}
