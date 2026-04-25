// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify.gradle

/** Transitive closure of [start]'s parents in [parents] (excluding [start]). */
internal fun transitiveAncestors(parents: Map<String, List<String>>, start: String): Set<String> {
    val out = LinkedHashSet<String>()
    val q = ArrayDeque(parents[start] ?: return emptySet())
    while (q.isNotEmpty()) {
        val p = q.removeFirst()
        if (out.add(p)) q.addAll(parents[p] ?: emptyList())
    }
    return out
}

/** First letter uppercased — used for Gradle task naming (`kspKotlin<Target>`). */
internal val String.cap: String get() = replaceFirstChar { it.uppercase() }
