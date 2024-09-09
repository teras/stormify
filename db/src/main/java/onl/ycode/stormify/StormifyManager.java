// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify;


import onl.ycode.logger.LogManager;
import onl.ycode.logger.Logger;
import onl.ycode.stormify.FieldInfo.FieldContext;
import onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval;

import javax.sql.DataSource;
import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static onl.ycode.stormify.EntityData.NO_ID_FIELDS;
import static onl.ycode.stormify.EntityData.NULL_ID_FIELDS;
import static onl.ycode.stormify.SPParam.Mode.*;
import static onl.ycode.stormify.SqlDialect.findDialect;
import static onl.ycode.stormify.TypeUtils.*;
import static onl.ycode.stormify.Utils.*;

/**
 * The main controller for the Stormify system. It is the entrance point for all the operations on the database.
 * <p>
 * It is a singleton and r requires a data source to be set before any operations can be performed.
 * The data source is required to be a generic JDBC data source.
 * <p>
 * The controller provides methods to perform queries, create, update, and delete entities, and execute stored procedures.
 * It also provides methods to perform transactions and other database operations, like populating an entity, retrieving all details of a parent object, etc.
 */
public class StormifyManager {
    private static volatile StormifyManager instance;

    private volatile DataSource dataSource;
    private volatile SqlDialect sqlDialect = null;
    private boolean strictMode = false;

    private final ClassRegistry registry = new ClassRegistry();
    private Logger logger = LogManager.getLogger("Stormify");
    private final Collection<Runnable> onInit = new ArrayList<>();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);

    /**
     * Returns the singleton instance of the controller.
     *
     * @return the singleton instance of the controller.
     */
    public static StormifyManager stormify() {
        if (instance == null) { // First check (no locking)
            synchronized (StormifyManager.class) {
                if (instance == null) { // Second check (with locking)
                    instance = new StormifyManager();
                }
            }
        }
        return instance;
    }

    /**
     * Returns the data source used by the controller. See {@link #setDataSource(DataSource)}.
     *
     * @return the data source used by the controller.
     */
    public DataSource getDataSource() {
        if (!isInitialized.get() && isInitialized.compareAndSet(false, true))
            onInit.forEach(Runnable::run);
        DataSource ds = dataSource;
        if (ds == null)
            throw new QueryException("Data source not set yet");
        return ds;
    }

    /**
     * Runs the given code block when the controller is initialized.
     *
     * @param runnable the code block to be run when the controller is initialized. More than one code blocks could be added.
     */
    public void onInit(Runnable runnable) {
        if (runnable != null)
            onInit.add(runnable);
    }

    /**
     * Checks if the data source is present in the controller.
     *
     * @return the data source used by the controller, or null if no data source is set.
     */
    public boolean isDataSourcePresent() {
        return dataSource != null;
    }

    /**
     * Sets the data source to be used by the controller.
     * You need to provide a generic JDBC data source, by any means necessary.
     * <p>
     * The data source is required to be set before any operations can be performed.
     * <p>
     * The data source cannot be null. To detach the data source from the controller,
     * use {@link #closeDataSource()} instead.
     *
     * @param dataSource the data source to be used by the controller. Can be null to detach
     *                   the data source from StormifyManager.
     */
    public void setDataSource(DataSource dataSource) {
        requireNonNull(dataSource, "Data source cannot be null");
        synchronized (this) {
            if (this.dataSource != null)
                throw new QueryException("Data source already set");
            this.dataSource = dataSource;
        }
    }

    /**
     * Closes the data source used by the controller.
     * This method should be called when the controller is no longer needed.
     * If no data source is set, a QueryException is thrown.
     */
    public synchronized void closeDataSource() {
        DataSource ds = this.dataSource;
        this.dataSource = null;
        if (ds == null)
            throw new QueryException("Data source not set");
        try {
            if (ds instanceof Closeable)
                ((Closeable) ds).close();
            else if (ds instanceof AutoCloseable)
                ((AutoCloseable) ds).close();
        } catch (Exception e) {
            logger.error("Unable to close data source", e);
        }
    }

    /**
     * Returns the logger used by the controller. See {@link #setLogger(Logger)}.
     *
     * @return the logger used by the controller.
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Sets the logger to be used by the controller. By default, the logger is {@link LogManager#getLogger(String)} with the name "Stormify".
     *
     * @param logger the logger to be used by the controller.
     */
    public void setLogger(Logger logger) {
        requireNonNull(logger, "Logger cannot be null");
        this.logger = logger;
    }

    /**
     * Registers a primary key resolver function that will be used to determine the primary key field name for
     * a given table.
     *
     * @param priority the priority of the resolver. The higher the value, the higher the priority.
     * @param resolver the resolver function that takes the table name and the name of the current field as
     *                 input and returns true if the field is a primary key.
     */
    public void registerPrimaryKeyResolver(int priority, BiPredicate<String, String> resolver) {
        requireNonNull(resolver, "Resolver cannot be null");
        registry.registerPrimaryKeyResolver(priority, resolver);
    }

    /**
     * Sets the naming policy to be used by the controller. By default, the naming policy is
     * {@link NamingPolicy#lowerCaseWithUnderscores} (snake_case). Note that the policy will only update the tables and
     * fields that are not already registered.
     *
     * @param namingPolicy the naming policy to be used by the controller.
     */
    public void setNamingPolicy(NamingPolicy namingPolicy) {
        requireNonNull(namingPolicy, "Naming policy cannot be null");
        registry.setNamingPolicy(namingPolicy);
    }

    /**
     * Returns the naming policy used by the controller. See {@link #setNamingPolicy(NamingPolicy)}.
     *
     * @return the naming policy used by the controller.
     */
    public NamingPolicy getNamingPolicy() {
        return registry.getNamingPolicy();
    }

    private StormifyManager() {
    }

    private static final class FixedParams {
        final String query;
        final List<Object> params;

        private FixedParams(String query, List<Object> params) {
            this.query = query;
            this.params = params;
        }
    }

    private FixedParams fixParams(String givenQuery, Object[] args) {
        if (args == null)
            return new FixedParams(givenQuery, emptyList());
        List<Object> params = new ArrayList<>(args.length);
        StringBuilder query = new StringBuilder(givenQuery.length());
        int countQuestionMarks = 0;
        for (int i = 0; i < givenQuery.length(); i++) {
            // Have to parse the whole query in case Iterables are used as parameters
            if (givenQuery.charAt(i) == '?') {
                if (countQuestionMarks >= args.length)
                    throw new QueryException("The number of placeholders (" + count(givenQuery, '?') + ") in query '" + givenQuery + "' exceeds the number of parameters (" + args.length + ")");
                Object arg = sqlData(args[countQuestionMarks++], true);
                if (arg instanceof List) {
                    query.append("(").append(nCopies("?", ", ", ((List<?>) arg).size())).append(")");
                    params.addAll((List<?>) arg);
                } else {
                    query.append("?");
                    params.add(arg);
                }
            } else
                query.append(givenQuery.charAt(i));
        }
        if (countQuestionMarks != args.length)
            throw new QueryException("The number of placeholders (" + count(givenQuery, '?') + ") in query '" + givenQuery + "' is less than the number of parameters (" + args.length + ")");
        return new FixedParams(query.toString(), params);
    }

    private <T> T performQuery(String givenQuery, Object[] givenParams, boolean generatedKeys, QueryEnvironment<T> code) throws QueryException {
        FixedParams params = fixParams(givenQuery, givenParams);
        dbLog(params.query, params.params.toArray());
        return initConnection(connection -> {
            try (PreparedStatement statement = generatedKeys ? connection.prepareStatement(params.query, Statement.RETURN_GENERATED_KEYS) : connection.prepareStatement(params.query)) {
                for (int i = 0; i < params.params.size(); i++)
                    statement.setObject(i + 1, params.params.get(i));
                return code.execute(statement);
            } catch (Exception e) {
                throw new QueryException("Unable to execute query '" + params.query + "'", e);
            }
        });
    }

    private <T> T initConnection(SafeFunction<Connection, T> connectionRequest) {
        try (TransactionalConnection conn = TransactionContext.getConnection()) {
            return connectionRequest.apply(conn.get());
        } catch (Exception e) {
            throw new QueryException("Unable to initialize connection and execute query", e);
        }
    }

    private Object sqlData(Object value, boolean recursively) {
        Class<?> valueClass = value == null ? null : value.getClass();
        if (valueClass == null || isBaseClass(valueClass))
            return value;
        if (recursively) {
            if (valueClass.isArray())
                return sqlData(Arrays.asList((Object[]) value), true);
            if (Iterable.class.isAssignableFrom(valueClass))
                return map((Iterable<?>) value, it -> sqlData(it, false));
        }
        FieldInfo primaryKey = registry.getTableInfo(valueClass).getPrimaryKey();
        return getOrThrow(() -> primaryKey.getValue(value),
                () -> "Primary key" + primaryKey.getName() + " in class " + valueClass.getSimpleName() + " has no value");
    }

    /**
     * Executes a query and returns the number of rows affected. This call is expected to update the status of the database.
     * It is used when SQL queries like INSERT, UPDATE, DELETE are executed.
     *
     * @param query  the query to be executed.
     * @param params the parameters to be used in the query.
     * @return the number of rows affected.
     */
    public int executeUpdate(String query, Object... params) {
        requireNonNull(query, "Query cannot be null");
        return performQuery(query, params, false, PreparedStatement::executeUpdate);
    }

    /**
     * Executes a read operation and returns the number of rows affected. Use this method when the strategy of parsing
     * the result row by row is preferred, instead of fetching all the results at once. Thus, data are consumed as they are
     * fetched from the database, making it ideal for large data sets.
     *
     * @param <T>       the type of the results.
     * @param baseClass the base class of the results.
     * @param query     the query to be executed.
     * @param consumer  the consumer to be used to process the results. Evey new row is passed to this consumer.
     * @param params    the parameters to be used in the query.
     * @return the number of rows affected.
     */
    public <T> int readCursor(Class<T> baseClass, String query, Consumer<T> consumer, Object... params) {
        requireNonNull(baseClass, "Base class cannot be null");
        requireNonNull(query, "Query cannot be null");
        requireNonNull(consumer, "Consumer cannot be null");
        boolean isBaseClass = isBaseClass(baseClass);
        return performQuery(query, params, false, statement -> {
            Constructor<T> constructor = isBaseClass ? null : baseClass.getDeclaredConstructor();
            ResultSet rs = statement.executeQuery();
            int count = 0;
            while (rs.next()) {
                count++;
                consumer.accept(isBaseClass ? castTo(baseClass, rs.getObject(1)) : forcePopulate(constructor.newInstance(), rs));
            }
            return count;
        });
    }

    /**
     * Executes a read operation and returns the list of results.
     *
     * @param <T>       the type of the results.
     * @param baseClass the base class of the results.
     * @param query     the query to be executed.
     * @param params    the parameters to be used in the query.
     * @return the list of results. This list is never null.
     */
    public <T> List<T> read(Class<T> baseClass, String query, Object... params) {
        List<T> result = new ArrayList<>();
        readCursor(baseClass, query, result::add, params);
        return result;
    }

    private static class Reference<T> {
        T item;
    }

    /**
     * Executes a read operation and returns a single result. If no results are found, null is returned. If multiple
     * results are found, a QueryException is thrown.
     *
     * @param <T>       the type of the result.
     * @param baseClass the base class of the result.
     * @param query     the query to be executed.
     * @param params    the parameters to be used in the query.
     * @return the single result. This result can be null if no data is found.
     */
    public <T> T readOne(Class<T> baseClass, String query, Object... params) {
        Reference<T> result = new Reference<>();
        readCursor(baseClass, query, it -> {
            if (result.item != null)
                throw new QueryException("Multiple results found for query '" + query + "'");
            result.item = it;
        }, params);
        return result.item;
    }

    <T> void forcePopulate(T entity) {
        EntityData<T> info = new EntityData<>(entity, registry);
        if (info.status == EntityData.NO_ID_FIELDS)
            throw new QueryException("No primary key found when populating object " + info.itemClass);
        else if (info.status == NULL_ID_FIELDS)
            return;
        Object[] params = info.idValues.toArray();
        String query = "SELECT * FROM " + info.table + " WHERE " + listOfIds(info.idFields);
        performQuery(query, params, false, statement -> {
            ResultSet rs = statement.executeQuery();
            if (rs.next())
                return forcePopulate(entity, rs);
            else
                throw new QueryException("No data found for " + info.table + " with id" + (params.length == 1 ? "" : "s") + " "
                        + (info.idFields.size() == 1 ? params[0] : Arrays.toString(params)));
        });
    }

    /**
     * Populates the entity with the data from the database.
     *
     * @param entity the entity to be populated.
     * @param <T>    the type of the entity.
     * @return the populated entity. This is the same entity that was passed as an argument.
     */
    public <T> T populate(T entity) {
        requireNonNull(entity, "Entity cannot be null");
        if (entity instanceof AutoTable)
            ((AutoTable) entity).autoPopulate();
        else
            forcePopulate(entity);
        return entity;
    }

    /**
     * Returns the SQL dialect used by the controller. See {@link SqlDialect}.
     *
     * @return the SQL dialect used by the controller.
     */
    public SqlDialect getSqlDialect() {
        if (sqlDialect == null)
            synchronized (this) {
                if (sqlDialect == null)
                    sqlDialect = findDialect();
            }
        return sqlDialect;
    }

    private Object getNextSequence(String sequence) {
        String sqlStatement = getSqlDialect().sequenceDialect.apply(sequence);
        BigInteger seq = sqlStatement == null ? null : readOne(BigInteger.class, sqlStatement);
        if (seq != null)
            dbLog("Sequence " + sequence + " incremented to " + seq, null);
        return seq;
    }

    /**
     * Creates a new entity in the database.
     *
     * @param createdItem the entity to be created.
     * @param <T>         the type of the entity.
     * @return the created entity.
     */
    public <T> T create(T createdItem) {
        requireNonNull(createdItem, "Created item cannot be null");
        EntityData<T> info = new EntityData<>(createdItem, registry);
        if (info.status == NULL_ID_FIELDS)
            for (int i = 0; i < info.idFields.size(); i++)
                if (info.idValues.get(i) == null && info.idFields.get(i).getSequence() != null)
                    info.idFields.get(i).setValue(createdItem, getNextSequence(info.idFields.get(i).getSequence()), registry);
        String fieldNames = info.tableInfo.createFieldNames.get();
        String placeholders = info.tableInfo.createPlaceholders.get();
        Object[] params = mapToArray(info.tableInfo.getFields(FieldContext.CREATE), it -> it.getValue(createdItem), null);
        String query = "INSERT INTO " + info.table + " (" + fieldNames + ") " + "VALUES (" + placeholders + ")";
        boolean supportsGeneratedKeys = getSqlDialect().generatedKeyRetrieval != GeneratedKeyRetrieval.NONE;
        performQuery(query, params, supportsGeneratedKeys, statement -> {
            int affectedRows = statement.executeUpdate();
            if (supportsGeneratedKeys && affectedRows > 0) try (ResultSet rs = statement.getGeneratedKeys()) {
                if (rs.next()) {
                    if (getSqlDialect().generatedKeyRetrieval == GeneratedKeyRetrieval.BY_INDEX)
                        info.tableInfo.getPrimaryKey().setValue(createdItem, rs.getObject(1), registry);
                    else {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i);
                            for (FieldInfo fieldInfo : info.tableInfo.getDbField(columnName))
                                fieldInfo.setValue(createdItem, rs.getObject(columnName), registry);
                        }
                    }
                }
            }
            return affectedRows;
        });
        return createdItem;
    }

    /**
     * Updates an entity in the database.
     *
     * @param updatedItem the entity to be updated.
     * @param <T>         the type of the entity.
     * @return the updated entity.
     */
    public <T> T update(T updatedItem) {
        requireNonNull(updatedItem, "Updated item cannot be null");
        EntityData<T> info = new EntityData<>(updatedItem, registry);
        if (info.status == NO_ID_FIELDS)
            throw new QueryException("No primary key found when updating object " + info.itemClass);
        else if (info.status == NULL_ID_FIELDS)
            throw new QueryException("Primary key value is null when updating object " + info.itemClass);
        String fields = info.tableInfo.updateFieldNames.get();
        Object[] params = mapToArray(info.tableInfo.getFields(FieldContext.UPDATE), it -> it.getValue(updatedItem), info.idValues);
        String query = "UPDATE " + info.table + " SET " + fields + " WHERE " + listOfIds(info.idFields);
        performQuery(query, params, false, PreparedStatement::executeUpdate);
        return updatedItem;
    }

    /**
     * Deletes an entity from the database.
     *
     * @param deletedItem the entity to be deleted.
     * @param <T>         the type of the entity.
     */
    public <T> void delete(T deletedItem) {
        EntityData<T> info = new EntityData<>(deletedItem, registry);
        if (info.status == NO_ID_FIELDS)
            throw new QueryException("No primary key found when deleting object " + info.itemClass);
        else if (info.status == NULL_ID_FIELDS)
            throw new QueryException("Primary key value is null when deleting object " + info.itemClass);
        Object[] params = info.idValues.toArray();
        String query = "DELETE FROM " + info.table + " WHERE " + listOfIds(info.idFields);
        performQuery(query, params, false, PreparedStatement::executeUpdate);
    }

    <T> T forcePopulate(T item, ResultSet resultSet) throws SQLException {
        TableInfo tableInfo = registry.getTableInfo(item.getClass());
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        if (item instanceof AutoTable)
            ((AutoTable) item).markPopulated();
        for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnName(i);
            Collection<FieldInfo> fields = tableInfo.getDbField(columnName);
            if (fields.isEmpty()) {
                if (strictMode)
                    throw new QueryException("Field " + columnName + " not found in " + tableInfo.getTableName());
                else
                    logger.warn("Field " + columnName + " not found in " + tableInfo.getTableName());
                continue;
            }
            Object value = resultSet.getObject(columnName);
            if (value != null) {
                if (Clob.class.isAssignableFrom(value.getClass()))
                    value = ((Clob) value).getSubString(1, (int) ((Clob) value).length());
                if (Blob.class.isAssignableFrom(value.getClass()))
                    value = ((Blob) value).getBytes(1, (int) ((Blob) value).length());
            }
            for (FieldInfo field : fields)
                field.setValue(item, value, registry);
        }
        return item;
    }

    /**
     * Executes a transaction with the given block of code.
     *
     * @param block the block of code to be executed.
     */
    public void transaction(SafeRunnable block) {
        TransactionContext context = null;
        try {
            context = TransactionContext.begin();
            block.run();
            context.commit();
        } catch (Exception e) {
            if (context != null)
                context.failed();
            if (e instanceof QueryException)
                throw (QueryException) e;
            else
                throw new QueryException("Unable to execute transaction", e);
        } finally {
            if (context != null)
                context.close();
        }
    }

    private <T> String findFieldByType(TableInfo info, Class<T> type) {
        String found = null;
        for (FieldInfo field : info.getFields(null))
            if (field.getType() == type) {
                if (found == null)
                    found = field.getName();
                else
                    throw new QueryException("Multiple fields of type " + type.getSimpleName() + " found in " + info.getTableName());
            }
        if (found == null)
            throw new QueryException("Field of type " + type.getSimpleName() + " not found in " + info.getClassType().getSimpleName());
        return found;
    }

    /**
     * Returns the details of the parent object. This method assumes that the details object has only
     * one property field that references the parent object. If more than one field references the parent object,
     * or no
     *
     * @param parent       the parent object.
     * @param detailsClass the class of the details.
     * @param <M>          the type of the parent object.
     * @param <D>          the type of the details.
     * @return the details of the parent object as a list.
     */
    public <M, D> List<D> getDetails(M parent, Class<D> detailsClass) {
        return getDetails(parent, detailsClass, null);
    }

    /**
     * Returns the details of the parent object.
     *
     * @param parent       the parent object.
     * @param detailsClass the class of the details.
     * @param propertyName the name of the reference property in the details class (i.e. the foreign key property name).
     *                     If empty, the first field of the parent class that matches the details class will be used. If
     *                     more than one field matches, an exception will be thrown.
     * @param <M>          the type of the parent object.
     * @param <D>          the type of the details.
     * @return the details of the parent object as a list.
     */
    public <M, D> List<D> getDetails(M parent, Class<D> detailsClass, String propertyName) {
        requireNonNull(parent, "Parent object cannot be null");
        requireNonNull(detailsClass, "Details class cannot be null");

        Class<?> parentClass = parent.getClass();
        TableInfo detailInfo = getTableInfo(detailsClass);
        Collection<FieldInfo> parentPrimaryKeys = getTableInfo(parentClass).getPrimaryKeys();
        if (parentPrimaryKeys.size() != 1)
            throw new QueryException("Parent class " + parentClass.getSimpleName() + " should have exactly one primary key");
        if (propertyName == null || propertyName.isEmpty())
            propertyName = findFieldByType(detailInfo, parentClass);
        FieldInfo field = detailInfo.getField(propertyName);
        if (field == null)
            throw new QueryException("Field " + propertyName + " not found in class " + detailsClass.getSimpleName());
        if (!field.getType().equals(parentClass))
            throw new QueryException("Field " + propertyName + " is not of type " + parentClass.getSimpleName() +
                    " in class " + detailsClass.getSimpleName());
        Object parentPrimaryKeyValue = parentPrimaryKeys.iterator().next().getValue(parent);
        List<D> details = stormify().read(
                detailsClass, "SELECT * FROM " + detailInfo.getTableName() + " WHERE " + field.getDbName() + " = ?",
                parentPrimaryKeyValue
        );
        for (D detail : details)
            field.setValue(detail, parent, registry);
        return details;
    }

    /**
     * Finds all the entities of the given class, while applying the given where clause.
     *
     * @param clazz       the class of the entities.
     * @param whereClause the where clause to be applied. The clause can be empty or null. It should contain the WHERE keyword.
     * @param arguments   the arguments to be used in the where clause, if the where clause exists.
     * @param <T>         the type of the entities.
     * @return the list of entities.
     */
    public <T> List<T> findAll(Class<T> clazz, String whereClause, Object... arguments) {
        requireNonNull(clazz, "Class cannot be null");
        return read(clazz, "SELECT * FROM " + getTableInfo(clazz).getTableName()
                + (whereClause == null || whereClause.isEmpty() ? "" : " " + whereClause), arguments);
    }

    /**
     * Finds the entity of the given class with the given ID.
     *
     * @param clazz the class of the entity.
     * @param id    the ID of the entity.
     * @param <T>   the type of the entity.
     * @return the entity with the given ID or null if not found.
     */
    public <T> T findById(Class<T> clazz, Object id) {
        requireNonNull(clazz, "Class cannot be null");
        requireNonNull(id, "ID cannot be null");
        return readOne(clazz, "SELECT * FROM " + getTableInfo(clazz).getTableName() + " WHERE " + getTableInfo(clazz).getPrimaryKey().getDbName() + " = ?", id);
    }

    /**
     * Returns the table information for the given class. Use this data to retrieve information about the database representation of the class.
     *
     * @param clazz the class for which the table information is required.
     * @return the table information for the given class.
     */
    public TableInfo getTableInfo(Class<?> clazz) {
        return registry.getTableInfo(clazz);
    }

    /**
     * Executes a stored procedure with the given name and parameters.
     *
     * @param name   the name of the stored procedure.
     * @param params the parameters to be used in the stored procedure.
     */
    public void storedProcedure(String name, SPParam<?>... params) {
        requireNonNull(name, "Procedure name cannot be null");
        initConnection(connection -> {
            String placeholders = nCopies("?", ", ", params == null ? 0 : params.length);
            String statement = "CALL " + name + "(" + placeholders + ")";
            dbLog(statement, params);
            try (CallableStatement cs = connection.prepareCall("{" + statement + "}")) {
                if (params != null)
                    for (int i = 0; i < params.length; i++) {
                        SPParam<?> p = params[i];
                        if (p.getMode() == IN || p.getMode() == INOUT)
                            cs.setObject(i + 1, p.getValue());
                        if (p.getMode() == OUT || p.getMode() == INOUT)
                            cs.registerOutParameter(i + 1, convertJavaTypeToSQLType(p.getType()));
                    }
                cs.execute();
                if (params != null)
                    for (int i = 0; i < params.length; i++) {
                        SPParam<?> p = params[i];
                        if (p.getMode() == OUT || p.getMode() == INOUT)
                            p.setResult(cs.getObject(i + 1));
                    }
            }
            return null;
        });
    }

    /**
     * Adds a field to the blacklist. The blacklist is used to prevent certain fields from being used as a column in the database.
     *
     * @param fieldName the name of the field to be added to the blacklist.
     */
    public void addBlacklistField(String fieldName) {
        requireNonNull(fieldName, "Field name cannot be null");
        if (fieldName.trim().isEmpty())
            throw new QueryException("Field name cannot be empty");
        registry.addBlacklistField(fieldName);
    }

    /**
     * Removes a field from the blacklist. The blacklist is used to prevent certain fields from being used as a column in the database.
     *
     * @param fieldName the name of the field to be removed from the blacklist.
     */
    public void removeBlacklistField(String fieldName) {
        requireNonNull(fieldName, "Field name cannot be null");
        if (fieldName.trim().isEmpty())
            throw new QueryException("Field name cannot be empty");
        registry.removeBlacklistField(fieldName);
    }

    /**
     * Set the object mapping to strict mode. In strict mode, the system will throw an exception if a field is not found in the entity.
     * Otherwise, it will log a warning and continue.
     * <p>
     * By default, the system is in strict mode.
     *
     * @return the current strict mode setting.
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Set the object mapping to strict mode. In strict mode, the system will throw an exception if a field is not found in the entity.
     * Otherwise, it will log a warning and continue.
     * <p>
     * By default, the system is in strict mode.
     *
     * @param strictMode the strict mode setting.
     */
    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    private String listOfIds(Collection<FieldInfo> ids) {
        return String.join(" AND ", map(ids, it -> it.getDbName() + " = ?"));
    }

    void dbLog(String query, Object[] params) {
        logger.debug("{}{}", query, (params == null || params.length == 0 ? "" : " -- " + Arrays.toString(params)));
    }
}
