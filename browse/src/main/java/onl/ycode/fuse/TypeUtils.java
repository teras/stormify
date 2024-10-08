// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.fuse;

import java.math.BigDecimal;
import java.sql.*;

/**
 * Utility class for handling Java types.
 */
public class TypeUtils {
    /**
     * Get the Java type for a given SQL type.
     *
     * @param sqlType The SQL type.
     * @return The Java type.
     */
    public static Class<?> getJavaType(int sqlType) {
        switch (sqlType) {
            case Types.ARRAY:
                return Array.class;
            case Types.BIGINT:
                return Long.class;
            case Types.BINARY:
            case Types.LONGVARBINARY:
            case Types.VARBINARY:
                return byte[].class;
            case Types.BIT:
            case Types.BOOLEAN:
                return Boolean.class;
            case Types.BLOB:
                return Blob.class;
            case Types.CHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.VARCHAR:
                return String.class;
            case Types.CLOB:
                return Clob.class;
            case Types.DATE:
                return Date.class;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return BigDecimal.class;
            case Types.DOUBLE:
            case Types.FLOAT:
                return Double.class;
            case Types.INTEGER:
                return Integer.class;
            case Types.JAVA_OBJECT:
            case Types.OTHER:
                return Object.class;
            case Types.REAL:
                return Float.class;
            case Types.REF:
                return Ref.class;
            case Types.ROWID:
                return RowId.class;
            case Types.SMALLINT:
                return Short.class;
            case Types.SQLXML:
                return SQLXML.class;
            case Types.STRUCT:
                return Struct.class;
            case Types.TIME:
                return Time.class;
            case Types.TIMESTAMP:
                return Timestamp.class;
            case Types.TINYINT:
                return Byte.class;
            case Types.NULL:
                return Void.class;
            default:
                return Object.class;
        }
    }

    /**
     * Convert a snake_case string to camelCase.
     *
     * @param name       The snake_case string.
     * @param firstUpper Whether the first letter should be uppercase.
     * @return The camelCase string.
     */
    public static String snakeToCamel(String name, boolean firstUpper) {
        name = name.toLowerCase();
        StringBuilder out = new StringBuilder();
        boolean upper = firstUpper;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /**
     * Conert a boxed Java type to its primitive type.
     *
     * @param clazz The boxed Java type.
     * @return The primitive Java type.
     */
    public static Class<?> unbox(Class<?> clazz) {
        if (clazz == Boolean.class) return boolean.class;
        if (clazz == Byte.class) return byte.class;
        if (clazz == Short.class) return short.class;
        if (clazz == Integer.class) return int.class;
        if (clazz == Long.class) return long.class;
        if (clazz == Float.class) return float.class;
        if (clazz == Double.class) return double.class;
        if (clazz == Character.class) return char.class;
        return clazz;
    }
}
