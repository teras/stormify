package onl.ycode.stormify.schemasync.entity

/**
 * Captured layout style across the user's existing entities. Used by the
 * F2 Apply view to add new properties (and emit brand-new files) in the
 * style the user already prefers.
 */
data class EntityStyle(
    val constructorStyle: ConstructorStyle,
    /** True when body declarations should be preceded by a blank line; only
     *  meaningful when properties land in the class body. */
    val bodyBlankLine: Boolean,
) {
    companion object {
        val DEFAULT = EntityStyle(ConstructorStyle.ALL, bodyBlankLine = false)
    }
}

/** Where new property declarations should land for an entity. */
enum class ConstructorStyle {
    /** All properties as primary-constructor val/var parameters. */
    ALL,
    /** Only the primary key in the constructor; everything else in the body. */
    ID_ONLY,
    /** Nothing in the constructor; all properties in the class body. */
    NONE,
}

/**
 * Detects [EntityStyle] from the entities the scanner already produced. Each
 * entity casts a single vote based on its own field layout; the majority wins,
 * tied buckets break in the order ALL > ID_ONLY > NONE (the most common
 * convention and the safest fallback for empty corpora).
 */
object EntityStyleDetector {

    fun detect(entities: List<KotlinEntity>): EntityStyle {
        if (entities.isEmpty()) return EntityStyle.DEFAULT
        val votes = mutableMapOf<ConstructorStyle, Int>()
        for (entity in entities) {
            classify(entity)?.let { votes.merge(it, 1, Int::plus) }
        }
        val winner = listOf(ConstructorStyle.ALL, ConstructorStyle.ID_ONLY, ConstructorStyle.NONE)
            .maxByOrNull { votes[it] ?: 0 } ?: ConstructorStyle.ALL

        // For body-blank-line: tally hints across all body fields seen.
        val blankHints = entities.flatMap { it.fields }
            .mapNotNull { it.precedingBlankLine }
        val bodyBlank = if (blankHints.isEmpty()) false
                        else blankHints.count { it } * 2 >= blankHints.size
        return EntityStyle(winner, bodyBlankLine = bodyBlank)
    }

    /** Returns the style that best fits a single entity, or null if it has no fields. */
    private fun classify(entity: KotlinEntity): ConstructorStyle? {
        if (entity.fields.isEmpty()) return null
        val ctorFields = entity.fields.count { it.inConstructor }
        val bodyFields = entity.fields.size - ctorFields
        if (ctorFields == 0) return ConstructorStyle.NONE
        if (bodyFields == 0) return ConstructorStyle.ALL
        // Mixed: vote ID_ONLY when only the PK is in the constructor; otherwise
        // fall back to whichever bucket has more fields.
        val ctorOnlyPk = entity.fields.filter { it.inConstructor }.all { it.primaryKey }
        if (ctorOnlyPk) return ConstructorStyle.ID_ONLY
        return if (ctorFields >= bodyFields) ConstructorStyle.ALL else ConstructorStyle.NONE
    }
}
