package com.lumo.studio.engine

import com.lumo.studio.model.Assignment
import com.lumo.studio.model.Photo
import com.lumo.studio.model.Role

/**
 * Groups photos into "original + derived versions".
 * Largest images are considered first, so originals usually come before their edits.
 * Manual decisions are never overwritten; only automatic ones are recomputed.
 */
object Matcher {

    fun similarity(a: Fingerprint, b: Fingerprint): Float {
        val dh = 1f - java.lang.Long.bitCount(a.dHash xor b.dHash) / 64f
        val ph = 1f - java.lang.Long.bitCount(a.pHash xor b.pHash) / 64f
        var dot = 0f
        for (i in a.edges.indices) dot += a.edges[i] * b.edges[i]
        val ec = dot.coerceIn(0f, 1f)
        val base = 0.3f * dh + 0.3f * ph + 0.4f * ec
        // Without structural agreement, a high hash score is not trusted (e.g. flat images).
        return if (ec < 0.2f) base * 0.6f else base
    }

    fun run(
        photos: List<Photo>,
        prints: Map<String, Fingerprint>,
        sensitivity: Float,
        manual: Map<String, Assignment>,
    ): Map<String, Assignment> {
        // sensitivity 0 = fewer errors (strict), 1 = more matches (lenient)
        val threshold = 0.85f - 0.20f * sensitivity.coerceIn(0f, 1f)
        val result = HashMap<String, Assignment>()
        val originals = LinkedHashSet<String>()

        for ((id, a) in manual) result[id] = a

        // Groups referenced by manual links must exist as originals.
        for (a in manual.values) {
            val g = a.groupId ?: continue
            if (a.role == Role.UNSORTED) continue
            originals += g
            if (result[g]?.manual != true) {
                result[g] = Assignment(g, g, Role.ORIGINAL, 1f, 1f, false, false)
            }
        }

        val candidates = photos
            .filter { !result.containsKey(it.id) && prints.containsKey(it.id) }
            .sortedWith(compareByDescending<Photo> { it.area }.thenBy { it.name })

        for (p in candidates) {
            val fp = prints.getValue(p.id)
            var best = -1f
            var second = -1f
            var bestGroup: String? = null
            for (g in originals) {
                val gp = prints[g] ?: continue
                val s = similarity(fp, gp)
                if (s > best) {
                    second = best
                    best = s
                    bestGroup = g
                } else if (s > second) {
                    second = s
                }
            }
            if (bestGroup != null && best >= threshold) {
                val gap = if (second < 0f) 1f else best - second
                val review = gap < 0.03f || best < threshold + 0.04f
                result[p.id] = Assignment(p.id, bestGroup, Role.VERSION, best, gap, review, false)
            } else {
                originals += p.id
                result[p.id] = Assignment(p.id, p.id, Role.ORIGINAL, 1f, 1f, false, false)
            }
        }
        return result
    }
}
