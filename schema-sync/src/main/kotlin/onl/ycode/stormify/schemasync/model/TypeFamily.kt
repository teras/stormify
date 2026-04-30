package onl.ycode.stormify.schemasync.model

/**
 * Coarse-grained type bucket used by the diff engine to flag genuine
 * incompatibilities (e.g. `String` ↔ `INTEGER`, `LocalDate` ↔ `BIGINT`).
 *
 * Resolution differences inside the same family (Int vs Long, VARCHAR(50) vs
 * VARCHAR(100), nullable vs not-null) are NOT mismatches — schema-sync's
 * reduced scope does not emit ALTER COLUMN TYPE/NULLABILITY.
 */
enum class TypeFamily {
    TEXT, INTEGRAL, DECIMAL, BOOLEAN, DATE, TIME, TIMESTAMP, BINARY, UUID,
}
