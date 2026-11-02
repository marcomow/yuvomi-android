package io.github.marcomow.yuvomi.feature.settings

import java.text.Normalizer

/**
 * `searchSettings` and `orderSettingsHits` of `registry.js`, over sheets and sections (the
 * per-option index stays in the web app): case and diacritics are ignored, and a hit whose
 * label starts with the query comes first (SET-R3).
 */
object SettingsSearch {
    data class Hit(val key: String, val label: String, val leaf: SettingsRegistry.Leaf, val rank: Int)

    /** `normalizeSettingsSearch`. */
    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun search(query: String, viewer: SettingsRegistry.Viewer, t: (String) -> String): List<Hit> {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return emptyList()
        val domains = SettingsRegistry.domainsFor(viewer).associate { it.id to t(it.labelKey) }
        val visible = SettingsRegistry.leaves.filter { it.domainId in domains && SettingsRegistry.leafVisible(it, viewer) }
        fun hit(text: String) = normalize(text).contains(needle)
        fun rank(label: String) = normalize(label).let { if (it.startsWith(needle)) 0 else if (it.contains(needle)) 1 else 2 }
        val hits = mutableListOf<Hit>()
        val leafHits = mutableSetOf<String>()
        for (leaf in visible) {
            val label = t(leaf.labelKey)
            if (hit("$label ${t(leaf.descriptionKey)} ${domains[leaf.domainId]}")) {
                hits += Hit("leaf:${leaf.id}", label, leaf, rank(label))
                leafHits += leaf.id
            }
        }
        for (leaf in visible) {
            for (section in SettingsRegistry.sectionsOf(leaf, viewer)) {
                if (leaf.id in leafHits || section.id == leaf.id) continue
                val label = t(section.labelKey)
                if (hit("$label ${t(section.descriptionKey)}")) hits += Hit("section:${section.id}", label, leaf, rank(label))
            }
        }
        return hits.withIndex().sortedWith(compareBy({ it.value.rank }, { it.index })).map { it.value }
    }
}
