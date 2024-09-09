// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval.*;
import static onl.ycode.stormify.StormifyManager.stormify;

/**
 * An enumeration of SQL dialects for different databases.
 * <p>
 * This enumeration is used to determine the current SQL dialect and provide helper
 * functions to generate SQL queries. The dialect is determined by the database product name.
 * <p>
 * If the database product name is not recognized, the dialect is set to {@link SqlDialect#UNKNOWN}.
 */
public enum SqlDialect {
    /**
     * The MariaDB dialect for versions older than 10.3.
     */
    MARIA_DB_OLD(s -> null, getOrderById(), getFormatterLimitOffset(), BY_INDEX),
    /**
     * The MariaDB dialect for versions 10.3 and newer.
     */
    MARIA_DB_NEW(getSequenceNextValueFor(), getOrderById(), getFormatterLimitOffset(), BY_INDEX),
    /**
     * The MySQL dialect for versions older than 8.
     */
    MYSQL_OLD(s -> null, getOrderById(), getFormatterLimitOffset(), BY_INDEX),
    /**
     * The MySQL dialect for versions 8 and newer.
     */
    MYSQL_NEW(getSequenceNextValueFor(), getOrderById(), getFormatterLimitOffset(), BY_INDEX),
    /**
     * The Oracle dialect for versions 12 and newer.
     */
    ORACLE_NEW(getSequenceFromDual(), getOrderByCase(), getFormatterRowsFetch(), NONE),
    /**
     * The Oracle dialect for versions older than 12.
     */
    ORACLE_OLD(getSequenceFromDual(), getOrderByCase(), getFormatterRowNumber(), NONE),
    /**
     * The PostgreSQL dialect.
     */
    POSTGRESQL(getSequenceNextval(), getOrderById(), getFormatterLimitOffset(), BY_NAME),
    /**
     * The SQL Server dialect for versions 2012 and newer.
     */
    SQL_SERVER_NEW(getSequenceNextValueFor(), getOrderByCase(), getFormatterRowsFetch(), BY_NAME),
    /**
     * The SQL Server dialect for versions older than 2012.
     */
    SQL_SERVER_OLD(getSequenceNextValueFor(), getOrderByCase(), getFormatterRowNumber(), BY_NAME),
    /**
     * The SQLite dialect.
     */
    SQLITE(s -> null, getOrderById(), getFormatterLimitOffset(), BY_INDEX),
    /**
     * The dialect that is used when the database product name cannot be determined.
     */
    UNKNOWN(s -> null, getOrderByCase(), getFormatterLimitOffset(), NONE),
    /**
     * A failsafe dialect, mostly in case of an error.
     */
    FAILSAFE(s -> null, getOrderByCase(), getFormatterLimitOffset(), NONE);

    /**
     * A query builder for various SQL dialects. The main purpose of this interface is to be able
     * to generate SQL queries with different pagination methods.
     */
    public interface QueryFormatter {
        /**
         * Generate a SQL pagination query with the given parameters.
         *
         * @param distinct    The distinct clause of the query.
         * @param tableName   The name of the table.
         * @param constraints The constraints of the query.
         * @param sorting     The order by part of the query.
         * @param lowBound    The lower bound of the query.
         * @param upperBound  The upper bound of the query.
         * @return The generated SQL query.
         */
        String apply(String distinct, String tableName, String constraints, String sorting, int lowBound, int upperBound);
    }

    enum GeneratedKeyRetrieval {
        BY_INDEX, BY_NAME, NONE
    }

    /**
     * A helper method to ask for sequences on different databases. As input is the name of the
     * sequence and as output the query to get the next value from the sequence.
     */
    public final UnaryOperator<String> sequenceDialect;
    /**
     * A helper method to ask for order by id on different databases. This is used to create a query that,
     * before any other sorting, fetches a specific entity first.
     * <p>
     * The first parameter is the name of the id column, and the second is the value of the id.
     * <p>
     * The result is a string that can be used in the order by part of a query.
     */
    public final BiFunction<String, BigDecimal, String> orderByIdDialect;
    /**
     * A query formatter that generates SQL queries with different pagination methods.
     *
     * @see QueryFormatter
     */
    public final QueryFormatter queryFormatter;

    final GeneratedKeyRetrieval generatedKeyRetrieval;

    SqlDialect(UnaryOperator<String> sequenceDialect,
               BiFunction<String, BigDecimal, String> orderByIdDialect,
               QueryFormatter queryFormatter,
               GeneratedKeyRetrieval generatedKeyRetrieval
    ) {
        this.sequenceDialect = sequenceDialect;
        this.orderByIdDialect = orderByIdDialect;
        this.queryFormatter = queryFormatter;
        this.generatedKeyRetrieval = generatedKeyRetrieval;
    }

    static SqlDialect findDialect() {
        try (Connection conn = stormify().getDataSource().getConnection()) {
            DatabaseMetaData metadata = conn.getMetaData();
            String productName = metadata.getDatabaseProductName().toLowerCase();
            String productVersion = metadata.getDatabaseProductVersion().toLowerCase();
            int majorVersion = metadata.getDatabaseMajorVersion();
            int minorVersion = metadata.getDatabaseMinorVersion();
            if (productName.contains("oracle"))
                return majorVersion >= 12 ? ORACLE_NEW : ORACLE_OLD;
            else if (productName.contains("sqlserver") || productName.contains("sql server"))
                return majorVersion >= 11 ? SQL_SERVER_NEW : SQL_SERVER_OLD;
            else if (productName.contains("postgresql"))
                return POSTGRESQL;
            else if (productName.contains("sqlite"))
                return SQLITE; // SQLite does not support sequences natively
            else if (productName.contains("mysql")) {
                if (productVersion.contains("mariadb")) {
                    if (majorVersion > 10 || (majorVersion == 10 && minorVersion >= 3))
                        return MARIA_DB_NEW;
                    else
                        return MARIA_DB_OLD;
                } else {
                    if (majorVersion >= 8)
                        return MYSQL_OLD;
                    else
                        return MYSQL_NEW;
                }
            } else
                return UNKNOWN;
        } catch (Exception e) {
            stormify().getLogger().error("Unable to determine SQL dialect", e);
            return FAILSAFE;
        }
    }

    /********************************************************************
     * This part defines the sequence dialects for different databases. *
     ********************************************************************/
    private static UnaryOperator<String> getSequenceFromDual() {
        return sequenceName -> "SELECT " + sequenceName + ".NEXTVAL FROM dual";
    }

    private static UnaryOperator<String> getSequenceNextValueFor() {
        return sequenceName -> "SELECT NEXT VALUE FOR " + sequenceName;
    }

    private static UnaryOperator<String> getSequenceNextval() {
        return sequenceName -> "SELECT nextval('" + sequenceName + "')";
    }

    /**********************************************************************
     * This part defines the pagination dialects for different databases. *
     **********************************************************************/
    private static QueryFormatter getFormatterLimitOffset() {
        return (distinct, tableName, constraints, sorting, lowBound, upperBound) -> "SELECT " + distinct + "* FROM " + tableName + constraints + " ORDER BY " + sorting + " LIMIT " + (upperBound - lowBound) + " OFFSET " + lowBound;
    }

    private static QueryFormatter getFormatterRowsFetch() {
        return (distinct, tableName, constraints, sorting, lowBound, upperBound) -> "SELECT " + distinct + "* FROM " + tableName + constraints + " ORDER BY " + sorting + " OFFSET " + lowBound + " ROWS FETCH NEXT " + (upperBound - lowBound) + " ROWS ONLY";
    }

    private static QueryFormatter getFormatterRowNumber() {
        return (distinct, tableName, constraints, sorting, lowBound, upperBound) -> "SELECT * FROM (SELECT " + distinct + tableName +
                ".*, ROW_NUMBER() OVER (ORDER BY " + sorting + ") rn from " + tableName + constraints
                + ") b WHERE b.rn > " + lowBound + " AND b.rn <= " + upperBound + " ORDER BY rn";
    }

    /***********************************************************************
     * This part defines the order by Id dialects for different databases. *
     ***********************************************************************/
    private static BiFunction<String, BigDecimal, String> getOrderById() {
        return (idColumn, idValue) -> idValue == null ? null : "(" + idColumn + " = " + idValue + ") DESC";
    }

    private static BiFunction<String, BigDecimal, String> getOrderByCase() {
        return (idColumn, idValue) -> idValue == null ? null :
                "CASE WHEN " + idColumn + " = " + idValue + " THEN 0 ELSE 1 END";
    }
}