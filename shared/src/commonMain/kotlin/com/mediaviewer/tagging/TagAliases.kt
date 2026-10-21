package com.mediaviewer.tagging

/** Local `tag_aliases` dictionary (per the tagging spec's "Search Query
 *  Execution and Synonym Mapping" section) — maps a handful of common,
 *  everyday search words onto the canonical e621/Z3D-E621-Convnext tag
 *  vocabulary, so searching "dog" also matches images the model tagged
 *  "canine" or "dog_ears", etc. Deliberately small and easy to extend;
 *  anything not listed here is passed straight through as its own literal
 *  tag term. */
object TagAliases {

    private val aliases: Map<String, List<String>> = mapOf(
        "dog" to listOf("canine", "dog", "dog_ears"),
        "cat" to listOf("feline", "cat", "cat_ears"),
        "wolf" to listOf("canine", "wolf"),
        "fox" to listOf("canine", "fox"),
        "dragon" to listOf("dragon"),
        "bird" to listOf("avian", "bird"),
        "horse" to listOf("equine", "horse"),
        "male" to listOf("male", "anthro_male"),
        "female" to listOf("female", "anthro_female"),
        "duo" to listOf("duo"),
        "group" to listOf("group"),
        "solo" to listOf("solo"),
        "nude" to listOf("nude"),
        "explicit" to listOf("explicit"),
        "safe" to listOf("safe"),
        "questionable" to listOf("questionable")
    )

    /** Turns a raw, space-separated user query into a list of OR-groups —
     *  one group per word the person typed, each expanded to its aliases
     *  (or just itself, if it isn't a known alias) — for
     *  [TagDatabase.searchPostUris] to AND together, e.g.
     *  "dog explicit" -> [[canine, dog, dog_ears], [explicit]].
     *
     *  (This used to build a single FTS5 MATCH expression string instead —
     *  renamed/reshaped when the FTS5 dependency was removed, see
     *  TagDatabase's doc comment for why.) */
    fun toTagGroups(rawQuery: String): List<List<String>> = parse(rawQuery).include

    /**
     * A Tagged search: each word is a whole tag (never part of one — "rape"
     * doesn't find "skyscraper"). Every word must match ([include]: one
     * group per word, any of its aliases); a word starting with "-" leaves
     * out posts with that tag ([exclude], e.g. "-solo"). Words starting
     * with "~" are one group together, as on e621: a post needs any one of
     * them ("~cat ~dog" finds cats and dogs).
     *
     * An account's word is "user:" and its DID ([ACCOUNT_PREFIX]; the search
     * page turns "@handle" into it): the posts by that account.
     */
    class TagQuery(val include: List<List<String>>, val exclude: Set<String>) {
        val isEmpty: Boolean get() = include.isEmpty() && exclude.isEmpty()
    }

    const val ACCOUNT_PREFIX = "user:"

    /** The DID an account's word stands for, or null when it's a tag. */
    fun accountDid(term: String): String? =
        if (term.startsWith(ACCOUNT_PREFIX + "did:")) term.removePrefix(ACCOUNT_PREFIX) else null

    /** [postUri] (at://did/…) is a post by [did]. */
    fun isPostBy(postUri: String, did: String): Boolean = postUri.startsWith("at://$did/")

    fun parse(rawQuery: String): TagQuery {
        val terms = rawQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val include = ArrayList<List<String>>()
        val exclude = LinkedHashSet<String>()
        val either = LinkedHashSet<String>()
        for (term in terms) {
            if (term.startsWith("-") && term.length > 1) {
                val t = term.drop(1)
                exclude += aliases[t] ?: listOf(t)
            } else if (term.startsWith("~") && term.length > 1) {
                val t = term.drop(1)
                either += aliases[t] ?: listOf(t)
            } else if (term != "-" && term != "~") {
                // A word is kept exactly as typed: tags have symbols in
                // them ("male/male", "name_(artist)", "<3").
                include += aliases[term] ?: listOf(term)
            }
        }
        if (either.isNotEmpty()) include += either.toList()
        return TagQuery(include, exclude)
    }
}
