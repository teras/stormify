// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

import org.gradle.api.GradleException

/**
 * Decides where the generated `Tables` declaration (and matching `Ref`
 * classes) lives across a Kotlin Multiplatform source-set hierarchy.
 *
 * The placement minimises duplication:
 *  - if every entity ultimately collapses to a single source set, that
 *    source set gets a plain `object Tables` (no `expect`/`actual`);
 *  - if entity-bearing chains are disjoint (no entity is visible to two
 *    different leaf groups), each anchor emits its own plain `object`;
 *  - otherwise the lowest common ancestor of the anchors holds an
 *    `expect`, and each anchor holds an `actual`.
 *
 * Topologies that have no legal Kotlin placement (for example shared
 * entities that would have to be declared in a source set that does not
 * see them) abort with a [GradleException].
 */
internal data class PlannerInputs(
    val entities: List<EntityMeta>,
    /** Direct `dependsOn` parents. `commonMain` maps to an empty list. */
    val parents: Map<String, List<String>>,
    /** Source sets that correspond to a `KotlinTarget` (the leafs). */
    val leafSourceSets: Set<String>,
)

internal sealed class Role {
    abstract val visibleEntities: List<EntityMeta>

    /** Plain `object Tables` (no `expect`/`actual`). */
    data class Plain(override val visibleEntities: List<EntityMeta>) : Role()

    /** `expect object Tables` holding the entities shared across anchors. */
    data class Expect(override val visibleEntities: List<EntityMeta>) : Role()

    /** `actual object Tables`. [actualForExpect] are matched 1:1 with the expect. */
    data class Actual(
        override val visibleEntities: List<EntityMeta>,
        val actualForExpect: Set<EntityMeta>,
    ) : Role()
}

internal class Planner(private val inp: PlannerInputs) {

    private val ancestorsCache = HashMap<String, Set<String>>()

    fun ancestors(ss: String): Set<String> =
        ancestorsCache.getOrPut(ss) { transitiveAncestors(inp.parents, ss) }

    fun ancestorsInclusive(ss: String): Set<String> = ancestors(ss) + ss

    /** Entities visible to source set [ss] via its `dependsOn` chain. */
    private fun visibleAt(ss: String): List<EntityMeta> {
        val chain = ancestorsInclusive(ss)
        return inp.entities.filter { it.sourceSet in chain }
    }

    /** Per-leaf visible entities, computed once and cached. */
    val visibleByLeaf: Map<String, List<EntityMeta>> by lazy {
        inp.leafSourceSets.associateWith { visibleAt(it) }.filterValues { it.isNotEmpty() }
    }

    /**
     * The actual anchor for [leaf]: the topmost ancestor (closest to
     * commonMain) whose every leaf-descendant shares the same visible-entity
     * set. Walks the full multi-parent ancestor closure — diamond
     * intermediates work as long as some ancestor satisfies the invariant.
     * Falls back to [leaf] itself when no ancestor qualifies.
     */
    private fun anchorFor(leaf: String): String {
        val target = visibleByLeaf[leaf]!!.toSet()
        fun valid(x: String): Boolean = inp.leafSourceSets
            .filter { it == x || x in ancestors(it) }
            .all { (visibleByLeaf[it] ?: emptyList()).toSet() == target }
        return ancestorsInclusive(leaf)
            .filter(::valid)
            .minWithOrNull(compareBy({ ancestors(it).size }, { it }))
            ?: leaf
    }

    private val anchorByLeaf: Map<String, String> by lazy {
        visibleByLeaf.keys.associateWith { anchorFor(it) }
    }

    private val distinctAnchors: Set<String> by lazy { anchorByLeaf.values.toSet() }

    private val entitiesAtAnchor: Map<String, List<EntityMeta>> by lazy {
        anchorByLeaf.entries
            .groupBy({ it.value }, { visibleByLeaf[it.key]!! })
            .mapValues { (_, leafVisibles) -> leafVisibles.first() }
    }

    /**
     * Lowest common ancestor of [set] in the `dependsOn` DAG, or null when
     * the source sets share no common ancestor. Deterministic across runs:
     * candidates are tie-broken by name when more than one minimal element
     * survives (rare — only triggered by diamond hierarchies whose minimum
     * isn't unique).
     */
    private fun lca(set: Set<String>): String? {
        if (set.isEmpty()) return null
        val intersection = set.map(::ancestorsInclusive).reduce { a, b -> a.intersect(b) }
        return intersection.sorted().firstOrNull { cand ->
            intersection.none { other -> other != cand && cand in ancestors(other) }
        }
    }

    /**
     * Returns the role this source set plays in the placement plan, or
     * `null` if it should emit nothing. Throws [GradleException] when the
     * topology has no legal placement.
     */
    fun roleOf(ss: String): Role? {
        if (visibleByLeaf.isEmpty()) return null

        if (distinctAnchors.size == 1) {
            val anchor = distinctAnchors.first()
            return if (ss == anchor) Role.Plain(entitiesAtAnchor[anchor]!!) else null
        }

        val anchorsHolding = HashMap<String, MutableSet<String>>() // entity qn -> anchors
        distinctAnchors.forEach { a ->
            entitiesAtAnchor[a]!!.forEach { e ->
                anchorsHolding.getOrPut(e.qualifiedName) { mutableSetOf() }.add(a)
            }
        }
        val shared: List<EntityMeta> = inp.entities.filter {
            (anchorsHolding[it.qualifiedName]?.size ?: 0) > 1
        }

        if (shared.isEmpty()) {
            return if (ss in distinctAnchors) Role.Plain(entitiesAtAnchor[ss]!!) else null
        }

        val expectAnchor = lca(distinctAnchors) ?: throw GradleException(
            "Stormify: cannot find a common ancestor source set for anchors " +
                "$distinctAnchors. Place shared entities in a source set visible to all targets."
        )
        val expectAnchorChain = ancestorsInclusive(expectAnchor)
        val unplaceable = shared.filter { it.sourceSet !in expectAnchorChain }
        if (unplaceable.isNotEmpty()) {
            val list = unplaceable.joinToString("\n") { e ->
                "  - ${e.qualifiedName}  (declared in '${e.sourceSet}')"
            }
            throw GradleException(
                "Stormify: the following entities are shared across distinct leaf " +
                    "anchors ($distinctAnchors) but live in source sets that the chosen " +
                    "expect anchor '$expectAnchor' cannot see:\n$list\n\n" +
                    "Move them up to '$expectAnchor' (or higher), or restructure the " +
                    "source-set hierarchy so that every leaf with leaf-specific entities " +
                    "is placed below the source set that holds the shared entities."
            )
        }

        return when (ss) {
            expectAnchor -> Role.Expect(shared)
            in distinctAnchors -> {
                val visible = entitiesAtAnchor[ss]!!
                val sharedHere = visible.filter { e -> shared.any { it.qualifiedName == e.qualifiedName } }.toSet()
                Role.Actual(visible, sharedHere)
            }
            else -> null
        }
    }
}
