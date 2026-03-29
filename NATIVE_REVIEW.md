# Native Branch Review — Εκκρεμότητες

## Features που χρειάζονται Reflection (LATER)

### 1. NamingPolicy + ClassRegistry (reflection-based entity discovery)
`NamingPolicy.java`, `ClassRegistry.java`, `BeanHelper.java`, `BeanInfo.java`, `FieldInfo.java`, `AnnotationUtils.java` — πλήρες σύστημα reflection-based discovery. Auto-register entities, snake_case ↔ camelCase conversion, `setNamingPolicy()`, `registerPrimaryKeyResolver()`.
Στο native δεν υπάρχει — entities πρέπει να γίνουν register μέσω `TableInfo.register()` (annproc ή manually).
**Θα υλοποιηθεί στο JVM source set** ως reflection-based convenience.

### 2. addBlacklistField / removeBlacklistField
Runtime field exclusion. Δεμένο με reflection-based field management (queries pre-built στο TableInfo).
**Θα υλοποιηθεί μαζί με reflection.**

### 3. registerPrimaryKeyResolver
Runtime PK detection. Χρησιμεύει κατά reflection-based entity discovery.
**Θα υλοποιηθεί μαζί με reflection.**

---

## Native-Only Gaps (μη-JVM targets)

1. **addBatch/executeBatch σε native drivers** — υλοποιημένο ως buffer-replay, όχι real batch
2. **Blob/Clob** — passthrough στο native (native drivers δεν χρησιμοποιούν java.sql.Blob/Clob)
3. **BigDecimal** — native χρησιμοποιεί ionspin BigDecimal, JVM χρησιμοποιεί java.math.BigDecimal
