# Advanced Topics

## AutoTable: Lazy Loading

`AutoTable` is an abstract base class that provides automatic lazy-loading of entity fields. When you read a list of
entities whose reference fields point to `AutoTable` subclasses, those references are created with only their primary
key set. When you access any non-key field, the full entity is loaded from the database on demand.

### How It Works

Subclasses must call `autoPopulate()` in every getter/setter of non-primary-key fields:

```java
public class User extends AutoTable {
    private Integer id;
    private String name;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        autoPopulate();  // Triggers lazy load if needed
        return name;
    }

    public void setName(String name) {
        autoPopulate();  // Triggers lazy load if needed
        this.name = name;
    }
}
```

`AutoTable` also provides implementations of `equals()`, `hashCode()`, and `toString()` based on primary key values.

### Sibling Batch Optimization

When multiple `AutoTable` references of the same type are created during a single read operation (e.g., many `Order`
rows each referencing a `Customer`), those references are grouped into a **sibling group**. When any one of them
triggers `autoPopulate()`, all siblings in the group are loaded in a single `SELECT ... WHERE id IN (...)` query
instead of individual queries per entity. Duplicate references (same type and ID) are also deduplicated automatically.

### populate() vs autoPopulate()

- **`autoPopulate()`** (called from within the entity): Uses sibling batch loading when available. This is the normal
  lazy-loading path.
- **`populate()`** (called from outside via `stormify().populate(entity)`): **Detaches** the entity from its sibling
  group and loads it individually. Use this when you want to force a fresh load of a specific entity.

### markPopulated()

Call `markPopulated()` from a subclass constructor or initialization code to signal that the entity already has its
data and does not need to be loaded from the database.

## CRUDTable Interface

`CRUDTable` is a convenience interface that adds CRUD methods directly to entity objects, reducing the need to call
`stormify()` explicitly:

```java
public class Test implements CRUDTable {
    private int id;
    private String name;
    // Getters and setters
}

Test record = new Test();
record.setId(1);
record.setName("Entry");
record.create();       // INSERT
record.update();       // UPDATE
record.delete();       // DELETE
record.populate();     // Load from DB by ID
record.tableName();    // Get the mapped table name

// Parent-child:
List<Detail> details = record.getDetails(Detail.class);
List<Detail> byField = record.getDetails(Detail.class, "propertyName");
```

`CRUDTable` can be combined with `AutoTable`:

```java
public class User extends AutoTable implements CRUDTable {
    // Gets both lazy loading and direct CRUD methods
}
```

## Handling Auto-Increment Fields

### Using Sequences

If your database uses sequences for generating primary keys, specify the sequence name. The primary key field must
use a **boxed type** (e.g., `Integer` instead of `int`) so it can be `null` before the sequence value is assigned:

```java
public class Test {
    @DbField(primaryKey = true, primarySequence = "id_seq")
    private Integer id;  // Boxed type — null triggers sequence fetch

    private String name;
    // Getters and setters
}
```

When creating a new entity, if the primary key is `null`, Stormify fetches the next value from the sequence before
inserting. For batch inserts, all sequence values are fetched in a single query.

### Database Auto-Increment

If no sequence is specified and the primary key is `null`, Stormify relies on the database's auto-increment mechanism.
The generated key is populated back to the entity after a single insert (not available for batch inserts).

## Working with Composite Keys

Stormify supports tables with composite primary keys. Mark all fields involved in the key as primary keys:

```java
public class CompositeKeyExample {
    @DbField(primaryKey = true)
    private int part1;

    @DbField(primaryKey = true)
    private int part2;

    private String data;
    // Getters and setters
}
```

**Note**: `findById` and sequence-based ID generation require a single primary key and are not available for composite
keys. Use `read` or `findAll` with a WHERE clause instead.

## Stored Procedures

Stormify supports calling stored procedures with IN, OUT, and INOUT parameters:

=== "Java"

    ```java
    import static onl.ycode.stormify.SPParam.*;

    SPParam<String> output = out(String.class);
    stormify().storedProcedure("my_procedure",
        in(Integer.class, 42),
        output,
        inout(String.class, "input_value")
    );
    String result = output.getResult();
    ```

=== "Kotlin"

    ```kotlin
    import onl.ycode.stormify.*

    val output = OUT<String>()
    "my_procedure".storedProcedure(
        IN(42),
        output,
        INOUT("input_value")
    )
    val result = output.result
    ```

## Supported Data Types

Stormify automatically converts between the following types when reading from or writing to the database:

| Category | Types |
|----------|-------|
| **Numeric** | `byte`, `short`, `int`, `long`, `float`, `double`, `BigInteger`, `BigDecimal` (and their boxed equivalents) |
| **Boolean** | `boolean` / `Boolean` (also converts from numeric: 0=false, non-zero=true) |
| **String** | `String`, `char` / `Character` |
| **Binary** | `byte[]` (maps to BLOB), `char[]` |
| **Date/Time** | `java.util.Date`, `java.sql.Date`, `Timestamp`, `Time`, `Instant`, `LocalDateTime`, `LocalDate`, `LocalTime`, `ZonedDateTime`, `OffsetDateTime` |
| **LOB** | `CLOB` → automatically converted to `String`, `BLOB` → automatically converted to `byte[]` |

All date/time types are interconvertible. For example, a `Timestamp` column can be read into a `LocalDateTime` field
and vice versa.

### Custom Type Conversions

To register additional conversions between types:

```java
TypeUtils.registerConversion(MyType.class, String.class, obj -> obj.serialize());
TypeUtils.registerConversion(String.class, MyType.class, str -> MyType.parse(str));
```

The first argument is the source type, the second is the target type, and the third is the conversion function.

## TableInfo Introspection

You can inspect the metadata Stormify generates for any entity class:

```java
TableInfo info = stormify().getTableInfo(Test.class);

// Table name
String tableName = info.getTableName();

// All fields
for (FieldInfo field : info.getFields()) {
    String javaName = field.getName();       // Java property name
    String dbName = field.getDbName();       // Database column name
    Class<?> type = field.getType();         // Field type
    boolean pk = field.isPrimaryKey();       // Is primary key?
    boolean ref = field.isReference();       // Is foreign key reference?
    String seq = field.getSequence();        // Sequence name (or null)
    boolean ins = field.isInsertable();      // Included in INSERT?
    boolean upd = field.isUpdatable();       // Included in UPDATE?
}

// Primary key(s)
List<FieldInfo> pks = info.getPrimaryKeys();
FieldInfo singlePk = info.getPrimaryKey();  // Throws if not exactly one

// Validate consistency (no duplicate insertable/updatable columns)
boolean valid = info.checkConsistency();
```
