// check:jvm
package com.mediaviewer.tagging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local-only SQLite store for the AI Tagging feature — schema adapted from
 *  the "Local On-Device AI Content Tagging Architecture" spec's
 *  liked_media / media_tags tables. Nothing in here ever touches the
 *  network or the account's AT Protocol PDS; it's purely a local index
 *  keyed by post URI, built by [com.mediaviewer.tagging.TaggingRepository]
 *  from images that are downloaded into memory, tagged, and immediately
 *  discarded (see that class's own doc comment — the actual media files are
 *  never written to disk by this feature).
 *
 *  Bug fix: this originally also created an `fts5`-backed virtual table for
 *  search (`tag_search_fts`), on the assumption that the FTS5 module is
 *  always compiled into the SQLite build Android ships. That's not actually
 *  guaranteed — it's an optional SQLite compile-time module, and plenty of
 *  real-world OEM system-image SQLite builds omit it (this crashed with
 *  `no such module: fts5` on a real device). Since every tag here is
 *  already a single discrete token (e.g. "dog_ears", not prose), full-text
 *  tokenized search was never actually buying anything over plain exact
 *  matching anyway — so search is now just an indexed `tag_name` lookup on
 *  a normal table (see [searchPostUris]), which needs nothing beyond core
 *  SQLite and is if anything more precise for this data. */
class TagDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    /** Which datasets each post and each tag came from ('' = this device's
     *  own tagging). A post or tag shared by several datasets is stored
     *  once, with one source row per dataset, so deleting a dataset removes
     *  only what no other dataset (or this device) also has. */
    private fun createSourceTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS post_sources (post_uri TEXT NOT NULL, dataset_id TEXT NOT NULL, PRIMARY KEY (post_uri, dataset_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS tag_sources (post_uri TEXT NOT NULL, tag_name TEXT NOT NULL, dataset_id TEXT NOT NULL, PRIMARY KEY (post_uri, tag_name, dataset_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_post_sources_dataset ON post_sources(dataset_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_sources_dataset ON tag_sources(dataset_id)")
    }

    /** Adds [tag] to [postUri] from [source]: one row per tag (the higher
     *  score kept), plus the source. A tag added by hand stays as it is. */
    private fun mergeTag(db: SQLiteDatabase, postUri: String, tag: String, confidence: Float, source: String) {
        db.execSQL("UPDATE media_tags SET confidence = MAX(confidence, ?) WHERE post_uri = ? AND tag_name = ? AND manual = 0", arrayOf(confidence, postUri, tag))
        db.execSQL(
            "INSERT INTO media_tags (post_uri, tag_name, confidence) SELECT ?, ?, ? " +
                "WHERE NOT EXISTS (SELECT 1 FROM media_tags WHERE post_uri = ? AND tag_name = ?)",
            arrayOf(postUri, tag, confidence, postUri, tag)
        )
        db.execSQL("INSERT OR IGNORE INTO tag_sources (post_uri, tag_name, dataset_id) VALUES (?, ?, ?)", arrayOf(postUri, tag, source))
    }

    /** Tags no source has any more (and not added by hand) are dropped. */
    private fun dropOrphanTags(db: SQLiteDatabase, postFilter: String, args: Array<out Any>) {
        db.execSQL(
            "DELETE FROM media_tags WHERE manual = 0 AND post_uri IN ($postFilter) AND NOT EXISTS " +
                "(SELECT 1 FROM tag_sources s WHERE s.post_uri = media_tags.post_uri AND s.tag_name = media_tags.tag_name)",
            args
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS liked_media (
                post_uri TEXT PRIMARY KEY,
                cid TEXT NOT NULL,
                media_url TEXT NOT NULL,
                indexed_timestamp INTEGER NOT NULL,
                dataset_id TEXT NOT NULL DEFAULT '',
                ai_tagged INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_tags (
                post_uri TEXT NOT NULL,
                tag_name TEXT NOT NULL,
                confidence REAL NOT NULL,
                manual INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(post_uri) REFERENCES liked_media(post_uri) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        // Import/Export feature: one row per *imported* dataset (a JSON file
        // someone else exported from their own device — see
        // TaggingRepository.importDataset/exportAllPosts). The on-device
        // dataset built by the person's own "Locally Tag All Liked Posts"/
        // realtime tagging never gets a row here — it's identified purely by
        // liked_media.dataset_id being the empty-string sentinel (see
        // LOCAL_DATASET_ID below), so there's nothing to list/delete for it
        // beyond the existing "Delete Tagged Post Database" button. Imported
        // datasets each get their own id here so Settings can list them and
        // delete one without touching any other dataset's (or the local
        // dataset's) rows — everything in liked_media/media_tags is still
        // queried together with no dataset filter for search/browse/counts,
        // which is what "use them all as one" means in practice.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS datasets (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                imported_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_post_uri ON media_tags(post_uri)")
        // The index that actually matters for search speed: every query in
        // searchPostUris filters by tag_name first.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_liked_media_dataset_id ON liked_media(dataset_id)")
        createSourceTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Migrating off a v1 database that may have partially included
            // (or attempted and failed to include) the old fts5 table.
            db.execSQL("DROP TABLE IF EXISTS tag_search_fts")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tags_tag_name ON media_tags(tag_name)")
        }
        if (oldVersion < 3) {
            // Import/Export feature: every pre-existing row belongs to the
            // person's own on-device dataset, so backfilling the new column
            // with the '' default (already what DEFAULT '' gives every
            // existing row) is exactly correct with no data migration needed
            // beyond adding the column itself.
            db.execSQL("ALTER TABLE liked_media ADD COLUMN dataset_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS datasets (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    imported_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_liked_media_dataset_id ON liked_media(dataset_id)")
        }
        if (oldVersion < 4) {
            // Hand tags vs the AI's: whether the AI has tagged a post yet
            // (hand tags alone don't count, so it still gets tagged), and
            // which tags were added by hand (kept when the AI tags it).
            // Before this, a hand-added tag scored exactly 1.0 — a post
            // whose every tag is 1.0 was only ever tagged by hand.
            db.execSQL("ALTER TABLE liked_media ADD COLUMN ai_tagged INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE media_tags ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE liked_media SET ai_tagged = 0 WHERE dataset_id = '' AND post_uri IN " +
                    "(SELECT post_uri FROM media_tags GROUP BY post_uri HAVING MIN(confidence) = 1.0 AND MAX(confidence) = 1.0)"
            )
            db.execSQL("UPDATE media_tags SET manual = 1 WHERE post_uri IN (SELECT post_uri FROM liked_media WHERE ai_tagged = 0)")
        }
        if (oldVersion < 5) {
            // Tags from several datasets on one post: every existing post
            // and AI/imported tag belonged to its row's one dataset.
            createSourceTables(db)
            db.execSQL("INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) SELECT post_uri, dataset_id FROM liked_media")
            db.execSQL(
                "INSERT OR IGNORE INTO tag_sources (post_uri, tag_name, dataset_id) " +
                    "SELECT t.post_uri, t.tag_name, m.dataset_id FROM media_tags t JOIN liked_media m ON m.post_uri = t.post_uri WHERE t.manual = 0"
            )
        }
        if (oldVersion < 6) {
            // Posts this device's AI tagged before taggers added their own
            // tag get it now (there was only ever the one tagger).
            val tagged = "SELECT m.post_uri FROM liked_media m WHERE m.ai_tagged = 1 " +
                "AND EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = m.post_uri AND p.dataset_id = '') " +
                "AND EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = m.post_uri) " +
                "AND NOT EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = m.post_uri AND t.tag_name LIKE 'ai:%')"
            db.execSQL(
                "INSERT INTO tag_sources (post_uri, tag_name, dataset_id) SELECT post_uri, ?, '' FROM ($tagged)",
                arrayOf(AiTaggers.CURRENT)
            )
            db.execSQL(
                "INSERT INTO media_tags (post_uri, tag_name, confidence) SELECT s.post_uri, s.tag_name, ? FROM tag_sources s " +
                    "WHERE s.tag_name = ? AND NOT EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = s.post_uri AND t.tag_name = s.tag_name)",
                arrayOf(AiTaggers.CONFIDENCE, AiTaggers.CURRENT)
            )
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    /** True if this post has already been scanned/tagged (so a resumed
     *  "tag all liked posts" run, or the realtime tag-on-like path, can
     *  skip work it already did). */
    fun isIndexed(postUri: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM liked_media WHERE post_uri = ? LIMIT 1", arrayOf(postUri)).use {
            return it.moveToFirst()
        }
    }

    /** True if this post came from an *imported* dataset (as opposed to being
     *  tagged by this device's own model). The on-device tagger treats these
     *  as already tagged and leaves them alone — see
     *  [TaggingRepository.tagOnLike]. */
    /** True if the AI has already tagged this post (or it came with an
     *  imported dataset) — a post with only hand-added tags isn't. */
    fun isAiTagged(postUri: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM liked_media WHERE post_uri = ? AND (ai_tagged = 1 OR EXISTS " +
                "(SELECT 1 FROM post_sources p WHERE p.post_uri = liked_media.post_uri AND p.dataset_id != ?)) LIMIT 1",
            arrayOf(postUri, LOCAL_DATASET_ID)
        ).use { return it.moveToFirst() }
    }

    /** See TaggingService.isTaggedByCurrentModel. */
    fun isTaggedByCurrentModel(postUri: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM liked_media m WHERE m.post_uri = ? AND (" +
                "EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = m.post_uri AND t.tag_name = ?) " +
                "OR (m.ai_tagged = 1 AND EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = m.post_uri AND p.dataset_id = ?)) " +
                "OR (EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = m.post_uri AND p.dataset_id != ?) " +
                "AND NOT EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = m.post_uri AND t.tag_name LIKE 'ai:%'))) LIMIT 1",
            arrayOf(postUri, AiTaggers.CURRENT, LOCAL_DATASET_ID, LOCAL_DATASET_ID)
        ).use { return it.moveToFirst() }
    }

    /** Every tag in the dataset, once each. */
    fun allTagNames(): List<String> {
        val results = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT tag_name FROM media_tags", null).use { c ->
            while (c.moveToNext()) results.add(c.getString(0))
        }
        return results
    }

    fun isInImportedDataset(postUri: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM post_sources WHERE post_uri = ? AND dataset_id != ? LIMIT 1",
            arrayOf(postUri, LOCAL_DATASET_ID)
        ).use { return it.moveToFirst() }
    }

    /** Records one post + its tags. Called once per successfully-tagged
     *  image; a post with zero tags above the confidence threshold still
     *  gets a liked_media row (so it counts as "scanned" and isn't retried
     *  forever) but no media_tags rows.
     *
     *  [datasetId] defaults to [LOCAL_DATASET_ID] — every call from the
     *  normal on-device tagging pipeline (TaggingRepository.inferAndStore/
     *  tagOnePost/tagBatch's text-only shortcut) leaves this at the
     *  default, so only [importDataset] ever passes a real dataset id. */
    fun storeTags(postUri: String, cid: String, mediaUrl: String, tags: List<Pair<String, Float>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Update in place (an INSERT OR REPLACE would delete the row
            // first, and with it — ON DELETE CASCADE — any hand-added tags).
            // A post that came with a dataset keeps that as its first source.
            val values = android.content.ContentValues().apply {
                put("cid", cid); put("media_url", mediaUrl)
                put("indexed_timestamp", System.currentTimeMillis()); put("ai_tagged", 1)
            }
            if (db.update("liked_media", values, "post_uri = ?", arrayOf(postUri)) == 0) {
                values.put("post_uri", postUri)
                values.put("dataset_id", LOCAL_DATASET_ID)
                db.insertOrThrow("liked_media", null, values)
            }
            db.execSQL("INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) VALUES (?, ?)", arrayOf(postUri, LOCAL_DATASET_ID))
            // Replace this device's AI tags from any earlier pass; hand-added
            // ones, and tags imported datasets gave it, stay.
            db.execSQL("DELETE FROM tag_sources WHERE post_uri = ? AND dataset_id = ?", arrayOf(postUri, LOCAL_DATASET_ID))
            dropOrphanTags(db, "?", arrayOf(postUri))
            tags.forEach { (tag, confidence) -> mergeTag(db, postUri, tag, confidence, LOCAL_DATASET_ID) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun scannedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM liked_media", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun taggedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT post_uri) FROM media_tags", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** Posts in this device's own dataset only — the same set
     *  [localPostsForExport] exports, counted the same way
     *  [listImportedDatasets] counts each imported one. (scannedCount
     *  counts every dataset's posts together.) */
    fun ownDatasetCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM post_sources WHERE dataset_id = ?", arrayOf(LOCAL_DATASET_ID)).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** Size on disk of the dataset as it's being built — shown live in the
     *  tagging overlay per the "total storage size" request. SQLite keeps
     *  writes in -wal/-shm files until checkpointed, so this sums all three. */
    fun datasetSizeBytes(): Long {
        val base = readableDatabase.path?.let { java.io.File(it) } ?: return 0L
        val wal = java.io.File(base.path + "-wal")
        val shm = java.io.File(base.path + "-shm")
        return (if (base.exists()) base.length() else 0L) +
            (if (wal.exists()) wal.length() else 0L) +
            (if (shm.exists()) shm.length() else 0L)
    }

    /** Search, without FTS: [termGroups] is a list of alias-expanded OR
     *  groups (see [TagAliases.toTagGroups]) — one group per word the
     *  person typed, e.g. searching "dog explicit" produces
     *  `[[canine, dog, dog_ears], [explicit]]`. A post has to have at least
     *  one tag from *every* group (AND across groups, OR within a group) —
     *  the same semantics the old FTS5 MATCH query had. Computed as a plain
     *  set intersection across per-group queries rather than one large SQL
     *  statement, since the number of groups is small (a handful of search
     *  words at most) and this keeps each query simple and index-friendly.
     *
     *  Bug fix (item 5 — "typing numbers or symbols... shows no results"):
     *  each term used to match only via an exact `tag_name IN (...)`
     *  lookup. Most real e621 tags aren't bare numbers/symbols on their own
     *  — they're a *part* of a compound tag (`rule_34`, `69_position`,
     *  `3d`) — so an exact-match-only query correctly, but unhelpfully,
     *  came back empty for exactly the kind of short numeric/symbolic term
     *  someone would type expecting a substring match. Every term now also
     *  tries a `LIKE '%term%'` match alongside the exact one (still OR'd
     *  together within the same group, still AND'd across groups), so
     *  "34" now finds "rule_34" the way a person typing it would expect,
     *  without changing behavior for a term that *is* an exact/aliased tag
     *  (that still matches first, via the same query). */
    fun searchPostUris(query: TagAliases.TagQuery, limit: Int = 200): List<String> {
        if (query.isEmpty) return emptyList()
        val db = readableDatabase
        // Whole tags only (exact names, never part of one): each include
        // group must match; posts with any excluded tag are left out.
        // (A word can also be an account: every tagged post by it.)
        val allTagged by lazy { allTaggedPostUris(Int.MAX_VALUE) }
        fun postsFor(terms: Collection<String>): LinkedHashSet<String> {
            val matches = LinkedHashSet<String>()
            val tags = terms.filter { TagAliases.accountDid(it) == null }
            val dids = terms.mapNotNull { TagAliases.accountDid(it) }
            if (tags.isNotEmpty()) {
                val placeholders = tags.joinToString(",") { "?" }
                db.rawQuery("SELECT DISTINCT post_uri FROM media_tags WHERE LOWER(tag_name) IN ($placeholders)", tags.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) matches.add(cursor.getString(0))
                }
            }
            if (dids.isNotEmpty()) allTagged.filterTo(matches) { uri -> dids.any { TagAliases.isPostBy(uri, it) } }
            return matches
        }
        var resultSet: LinkedHashSet<String>? = null
        for (group in query.include) {
            val matches = postsFor(group)
            resultSet = resultSet?.apply { retainAll(matches) } ?: matches
            if (resultSet.isEmpty()) break
        }
        // Only exclusions: start from every tagged post.
        val matched: LinkedHashSet<String> = resultSet ?: LinkedHashSet(allTagged)
        if (query.exclude.isNotEmpty() && matched.isNotEmpty()) matched.removeAll(postsFor(query.exclude))
        if (matched.isEmpty()) return emptyList()
        // Bug fix ("search shows the newest posts for a tag, then refreshes
        // to older ones and the newer ones disappear"): matches came back in
        // raw table order — oldest-tagged first — and were then cut to
        // [limit], so for any popular tag the newest posts were exactly the
        // ones dropped. Results now come newest-tagged first (the same order
        // the unfiltered browse view uses) BEFORE the limit is applied.
        val ordered = ArrayList<String>(minOf(limit, matched.size))
        db.rawQuery("SELECT post_uri FROM liked_media ORDER BY indexed_timestamp DESC", null).use { cursor ->
            while (ordered.size < limit && cursor.moveToNext()) {
                val uri = cursor.getString(0)
                if (uri in matched) ordered.add(uri)
            }
        }
        return ordered
    }

    /** Item 2: default "browse all" view for the Liked search tab, most
     *  recently-tagged post first — only posts that ended up with >=1 tag
     *  (an EXISTS check against media_tags, same filter taggedCount() uses). */
    fun allTaggedPostUris(limit: Int = 200): List<String> {
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT lm.post_uri FROM liked_media lm
            WHERE EXISTS (SELECT 1 FROM media_tags mt WHERE mt.post_uri = lm.post_uri)
            ORDER BY lm.indexed_timestamp DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor -> while (cursor.moveToNext()) results.add(cursor.getString(0)) }
        return results
    }

    /** Item 3: the complete tag list for one post (for the Tags mode on a
     *  post opened from the Liked tab), highest confidence first. */
    fun tagsForPost(postUri: String): List<String> {
        val results = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT tag_name FROM media_tags WHERE post_uri = ? ORDER BY confidence DESC",
            arrayOf(postUri)
        ).use { cursor -> while (cursor.moveToNext()) results.add(cursor.getString(0)) }
        return results
    }



    /** The Tags page's hand edits: add ([oldTag] null), remove ([newTag]
     *  null) or rename. Tags added by hand get full confidence; a renamed
     *  one keeps its own. The post is added (to the device's own dataset)
     *  if the tagger never saw it; its place in "newest first" is kept. */
    fun editTag(postUri: String, cid: String, mediaUrl: String, oldTag: String?, newTag: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A post first tagged by hand isn't marked AI-tagged, so the AI
            // still tags it later.
            db.execSQL(
                "INSERT OR IGNORE INTO liked_media (post_uri, cid, media_url, indexed_timestamp, dataset_id, ai_tagged) VALUES (?, ?, ?, ?, ?, 0)",
                arrayOf(postUri, cid, mediaUrl, System.currentTimeMillis(), LOCAL_DATASET_ID)
            )
            db.execSQL(
                "INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM post_sources WHERE post_uri = ?)",
                arrayOf(postUri, LOCAL_DATASET_ID, postUri)
            )
            when {
                oldTag != null && newTag == null -> {
                    db.execSQL("DELETE FROM media_tags WHERE post_uri = ? AND tag_name = ?", arrayOf(postUri, oldTag))
                    db.execSQL("DELETE FROM tag_sources WHERE post_uri = ? AND tag_name = ?", arrayOf(postUri, oldTag))
                }
                oldTag == null && newTag != null -> {
                    db.execSQL("DELETE FROM media_tags WHERE post_uri = ? AND tag_name = ?", arrayOf(postUri, newTag))
                    db.execSQL("INSERT INTO media_tags (post_uri, tag_name, confidence, manual) VALUES (?, ?, 1.0, 1)", arrayOf(postUri, newTag))
                }
                oldTag != null && newTag != null && oldTag != newTag -> {
                    // (Renaming onto a tag it already has merges the two.)
                    db.execSQL("DELETE FROM media_tags WHERE post_uri = ? AND tag_name = ?", arrayOf(postUri, newTag))
                    db.execSQL("UPDATE media_tags SET tag_name = ?, manual = 1 WHERE post_uri = ? AND tag_name = ?", arrayOf(newTag, postUri, oldTag))
                    db.execSQL("DELETE FROM tag_sources WHERE post_uri = ? AND tag_name = ?", arrayOf(postUri, oldTag))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every post currently in the database, across every dataset — the
     *  source data for an Export. */
    fun allPostsForExport(exclude: Set<String> = emptySet()): List<TagExportedPost> {
        if (exclude.isNotEmpty()) return postsForExportExcluding(exclude)
        val posts = LinkedHashMap<String, TagExportedPost>()
        readableDatabase.rawQuery("SELECT post_uri, cid, media_url FROM liked_media", null).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                posts[uri] = TagExportedPost(uri, cursor.getString(1), cursor.getString(2), emptyList())
            }
        }
        val tagsByPost = HashMap<String, MutableList<Pair<String, Float>>>()
        readableDatabase.rawQuery("SELECT post_uri, tag_name, confidence FROM media_tags ORDER BY confidence DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                tagsByPost.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1) to cursor.getFloat(2))
            }
        }
        return posts.values.map { it.copy(tags = tagsByPost[it.postUri] ?: emptyList()) }
    }

    /** [allPostsForExport] leaving out what only the datasets in [exclude]
     *  gave: their posts (unless another source or hand tags have them)
     *  and their tags (unless another source has them, or added by hand). */
    private fun postsForExportExcluding(exclude: Set<String>): List<TagExportedPost> {
        val marks = exclude.joinToString(",") { "?" }
        val args = exclude.toTypedArray()
        val posts = LinkedHashMap<String, TagExportedPost>()
        readableDatabase.rawQuery(
            "SELECT m.post_uri, m.cid, m.media_url FROM liked_media m WHERE " +
                "EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = m.post_uri AND p.dataset_id NOT IN ($marks)) OR " +
                "EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = m.post_uri AND t.manual = 1)",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                posts[uri] = TagExportedPost(uri, cursor.getString(1), cursor.getString(2), emptyList())
            }
        }
        val tagsByPost = HashMap<String, MutableList<Pair<String, Float>>>()
        readableDatabase.rawQuery(
            "SELECT t.post_uri, t.tag_name, t.confidence FROM media_tags t WHERE t.manual = 1 OR EXISTS " +
                "(SELECT 1 FROM tag_sources s WHERE s.post_uri = t.post_uri AND s.tag_name = t.tag_name AND s.dataset_id NOT IN ($marks)) " +
                "ORDER BY t.confidence DESC",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tagsByPost.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1) to cursor.getFloat(2))
            }
        }
        return posts.values.map { it.copy(tags = tagsByPost[it.postUri] ?: emptyList()) }
    }

    /** Imports a friend's exported dataset as its own separate, independently
     *  deletable entry. Everything lands in the same liked_media/media_tags
     *  tables (so search treats every dataset as one); a post the device
     *  already has (from its own tagging or another dataset) gets this
     *  dataset's tags added to it — each tag once, the higher score kept —
     *  and every post and tag remembers which datasets it came from
     *  (post_sources/tag_sources), so [deleteDataset] removes exactly what
     *  only this dataset brought. */
    /**
     * Adds [posts] as one imported dataset. Fast enough for the shared
     * dataset's hundreds of thousands of tags on a phone: every statement
     * is compiled once (not once per tag), and a post that wasn't here yet
     * has its tags written straight in (only one already here has them
     * merged, keeping the highest score). [onProgress] hears 0–1.
     */
    fun importDataset(name: String, posts: List<TagExportedPost>, onProgress: (Float) -> Unit = {}): TagDatasetInfo {
        val id = java.util.UUID.randomUUID().toString()
        val importedAt = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO datasets (id, name, imported_at) VALUES (?, ?, ?)",
                arrayOf(id, name, importedAt)
            )
            var count = 0
            val insertPost = db.compileStatement(
                "INSERT OR IGNORE INTO liked_media (post_uri, cid, media_url, indexed_timestamp, dataset_id) VALUES (?, ?, ?, ?, ?)"
            )
            val insertSource = db.compileStatement("INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) VALUES (?, ?)")
            val insertTag = db.compileStatement("INSERT INTO media_tags (post_uri, tag_name, confidence) VALUES (?, ?, ?)")
            val raiseTag = db.compileStatement("UPDATE media_tags SET confidence = MAX(confidence, ?) WHERE post_uri = ? AND tag_name = ? AND manual = 0")
            val addTagIfMissing = db.compileStatement(
                "INSERT INTO media_tags (post_uri, tag_name, confidence) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM media_tags WHERE post_uri = ? AND tag_name = ?)"
            )
            val insertTagSource = db.compileStatement("INSERT OR IGNORE INTO tag_sources (post_uri, tag_name, dataset_id) VALUES (?, ?, ?)")
            try {
                val total = posts.size.coerceAtLeast(1)
                posts.forEachIndexed { i, post ->
                    if (post.postUri.isBlank()) return@forEachIndexed
                    insertPost.clearBindings()
                    insertPost.bindString(1, post.postUri)
                    insertPost.bindString(2, post.cid)
                    insertPost.bindString(3, post.mediaUrl)
                    insertPost.bindLong(4, importedAt)
                    insertPost.bindString(5, id)
                    val isNew = insertPost.executeInsert() != -1L
                    insertSource.clearBindings()
                    insertSource.bindString(1, post.postUri); insertSource.bindString(2, id)
                    insertSource.executeInsert()
                    count++
                    // (One post's tags once each — the highest score.)
                    val tags = if (isNew) post.tags.groupBy { it.first }.map { (t, l) -> t to l.maxOf { it.second } } else post.tags
                    for ((tag, confidence) in tags) {
                        if (isNew) {
                            insertTag.clearBindings()
                            insertTag.bindString(1, post.postUri); insertTag.bindString(2, tag); insertTag.bindDouble(3, confidence.toDouble())
                            insertTag.executeInsert()
                        } else {
                            raiseTag.clearBindings()
                            raiseTag.bindDouble(1, confidence.toDouble()); raiseTag.bindString(2, post.postUri); raiseTag.bindString(3, tag)
                            raiseTag.executeUpdateDelete()
                            addTagIfMissing.clearBindings()
                            addTagIfMissing.bindString(1, post.postUri); addTagIfMissing.bindString(2, tag); addTagIfMissing.bindDouble(3, confidence.toDouble())
                            addTagIfMissing.bindString(4, post.postUri); addTagIfMissing.bindString(5, tag)
                            addTagIfMissing.executeInsert()
                        }
                        insertTagSource.clearBindings()
                        insertTagSource.bindString(1, post.postUri); insertTagSource.bindString(2, tag); insertTagSource.bindString(3, id)
                        insertTagSource.executeInsert()
                    }
                    if (i % 500 == 0) onProgress(i.toFloat() / total)
                }
            } finally {
                insertPost.close(); insertSource.close(); insertTag.close(); raiseTag.close(); addTagIfMissing.close(); insertTagSource.close()
            }
            db.setTransactionSuccessful()
            onProgress(1f)
            return TagDatasetInfo(id, name, importedAt, count)
        } finally {
            db.endTransaction()
        }
    }

    /** Settings → Data → Export: only the on-device (main/default) dataset. */
    fun localPostsForExport(): List<TagExportedPost> {
        val posts = LinkedHashMap<String, TagExportedPost>()
        readableDatabase.rawQuery(
            "SELECT m.post_uri, m.cid, m.media_url FROM liked_media m JOIN post_sources p ON p.post_uri = m.post_uri WHERE p.dataset_id = ?",
            arrayOf(LOCAL_DATASET_ID)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                posts[uri] = TagExportedPost(uri, cursor.getString(1), cursor.getString(2), emptyList())
            }
        }
        val tagsByPost = HashMap<String, MutableList<Pair<String, Float>>>()
        readableDatabase.rawQuery(
            // This device's own tags: its AI's, and the ones added by hand.
            "SELECT t.post_uri, t.tag_name, t.confidence FROM media_tags t WHERE t.manual = 1 OR EXISTS " +
                "(SELECT 1 FROM tag_sources s WHERE s.post_uri = t.post_uri AND s.tag_name = t.tag_name AND s.dataset_id = ?) " +
                "ORDER BY t.confidence DESC", arrayOf(LOCAL_DATASET_ID)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tagsByPost.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1) to cursor.getFloat(2))
            }
        }
        return posts.values.map { it.copy(tags = tagsByPost[it.postUri] ?: emptyList()) }
    }

    /** One imported dataset's posts with the tags it gave them. */
    fun datasetPostsForExport(id: String): List<TagExportedPost> {
        val posts = LinkedHashMap<String, TagExportedPost>()
        readableDatabase.rawQuery(
            "SELECT m.post_uri, m.cid, m.media_url FROM liked_media m JOIN post_sources p ON p.post_uri = m.post_uri WHERE p.dataset_id = ?",
            arrayOf(id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                posts[uri] = TagExportedPost(uri, cursor.getString(1), cursor.getString(2), emptyList())
            }
        }
        val tagsByPost = HashMap<String, MutableList<Pair<String, Float>>>()
        readableDatabase.rawQuery(
            "SELECT t.post_uri, t.tag_name, t.confidence FROM media_tags t JOIN tag_sources s ON s.post_uri = t.post_uri AND s.tag_name = t.tag_name " +
                "WHERE s.dataset_id = ? ORDER BY t.confidence DESC", arrayOf(id)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tagsByPost.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1) to cursor.getFloat(2))
            }
        }
        return posts.values.map { it.copy(tags = tagsByPost[it.postUri] ?: emptyList()) }
    }

    /** Settings → Data → Import: [posts] BECOME the on-device (main) dataset
     *  — the old local rows are replaced, not merged, and nothing is added
     *  as a separate imported dataset. Imported datasets are left alone. */
    fun replaceLocalDataset(posts: List<TagExportedPost>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // This device's own tags go (imported datasets' stay), then any
            // post nothing is left on.
            db.execSQL("DELETE FROM tag_sources WHERE dataset_id = ?", arrayOf(LOCAL_DATASET_ID))
            db.execSQL(
                "DELETE FROM media_tags WHERE post_uri IN (SELECT post_uri FROM post_sources WHERE dataset_id = ?) AND " +
                    "(manual = 1 OR NOT EXISTS (SELECT 1 FROM tag_sources s WHERE s.post_uri = media_tags.post_uri AND s.tag_name = media_tags.tag_name))",
                arrayOf(LOCAL_DATASET_ID)
            )
            db.execSQL("DELETE FROM post_sources WHERE dataset_id = ?", arrayOf(LOCAL_DATASET_ID))
            db.execSQL("DELETE FROM liked_media WHERE NOT EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = liked_media.post_uri)")
            for (post in posts) {
                if (post.postUri.isBlank()) continue
                db.execSQL(
                    "INSERT OR IGNORE INTO liked_media (post_uri, cid, media_url, indexed_timestamp, dataset_id) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(post.postUri, post.cid, post.mediaUrl, now, LOCAL_DATASET_ID)
                )
                db.execSQL("INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) VALUES (?, ?)", arrayOf(post.postUri, LOCAL_DATASET_ID))
                for ((tag, confidence) in post.tags) mergeTag(db, post.postUri, tag, confidence, LOCAL_DATASET_ID)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Settings' imported-datasets list. Only ever contains entries created
     *  by [importDataset] — the local on-device dataset isn't listed here
     *  (see the `datasets` table's own doc comment in [onCreate]). */
    fun listImportedDatasets(): List<TagDatasetInfo> {
        val results = mutableListOf<TagDatasetInfo>()
        readableDatabase.rawQuery(
            """
            SELECT d.id, d.name, d.imported_at,
                (SELECT COUNT(*) FROM post_sources ps WHERE ps.dataset_id = d.id) AS post_count
            FROM datasets d ORDER BY d.imported_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(TagDatasetInfo(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3)))
            }
        }
        return results
    }

    /** Removes exactly one imported dataset's posts/tags — everything else
     *  (the local dataset, and every other import) is untouched, which is
     *  the whole point of tracking dataset_id per row (per the request:
     *  "if someone imports one they don't like they can remove it without
     *  deleting the others"). */
    fun deleteDataset(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inSet = "SELECT post_uri FROM post_sources WHERE dataset_id = ?"
            // Its tags go unless another dataset (or this device) has them too.
            db.execSQL("DELETE FROM tag_sources WHERE dataset_id = ?", arrayOf(id))
            dropOrphanTags(db, inSet, arrayOf(id))
            // Its posts go unless something else still has them.
            db.execSQL(
                "DELETE FROM liked_media WHERE post_uri IN ($inSet) AND " +
                    "NOT EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = liked_media.post_uri AND p.dataset_id != ?) AND " +
                    "NOT EXISTS (SELECT 1 FROM media_tags t WHERE t.post_uri = liked_media.post_uri)",
                arrayOf(id, id)
            )
            // A post it brought that's still here (another dataset or hand
            // tags have it) moves to whichever source is left; one only hand
            // tags are left on still needs the AI.
            db.execSQL(
                "UPDATE liked_media SET ai_tagged = CASE WHEN EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = liked_media.post_uri AND p.dataset_id != ?) THEN ai_tagged ELSE 0 END, " +
                    "dataset_id = COALESCE((SELECT p.dataset_id FROM post_sources p WHERE p.post_uri = liked_media.post_uri AND p.dataset_id != ? LIMIT 1), ?) " +
                    "WHERE post_uri IN ($inSet) AND dataset_id = ?",
                arrayOf(id, id, LOCAL_DATASET_ID, id, id)
            )
            db.execSQL("DELETE FROM post_sources WHERE dataset_id = ?", arrayOf(id))
            // (A post kept only by its hand tags is this device's now.)
            db.execSQL(
                "INSERT OR IGNORE INTO post_sources (post_uri, dataset_id) SELECT post_uri, ? FROM liked_media " +
                    "WHERE NOT EXISTS (SELECT 1 FROM post_sources p WHERE p.post_uri = liked_media.post_uri)",
                arrayOf(LOCAL_DATASET_ID)
            )
            db.execSQL("DELETE FROM datasets WHERE id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Wipes the whole dataset — used if the user wants to re-tag from
     *  scratch (a fresh "Locally Tag All Liked Posts" run reuses existing
     *  rows instead by default via [isIndexed]). Now wired to Settings'
     *  "Delete Tagged Post Database" button (item 5) via
     *  [TaggingRepository.deleteDatabase]. Runs a VACUUM after the deletes —
     *  SQLite doesn't shrink the on-disk file just because its rows are
     *  gone, and the whole point of this button is to actually reclaim that
     *  space, not just make the tables logically empty while
     *  [datasetSizeBytes] keeps reporting the old size. */
    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM media_tags")
        db.execSQL("DELETE FROM liked_media")
        // Item 4 (Import/Export): "Delete Tagged Post Database" is the
        // nuclear "start over" option, so it clears every imported dataset's
        // listing too, not just the local tagged rows — otherwise Settings'
        // imported-datasets list would keep showing entries whose underlying
        // liked_media/media_tags rows had already been wiped.
        db.execSQL("DELETE FROM datasets")
        db.execSQL("DELETE FROM post_sources")
        db.execSQL("DELETE FROM tag_sources")
        db.execSQL("VACUUM")
    }

    companion object {
        private const val DB_NAME = "liked_media_tags.db"
        // Bumped 1 -> 2 for the fts5 removal (see class doc comment). Devices
        // that never got past v1's onCreate (it always threw before
        // completing, since CREATE VIRTUAL TABLE...fts5 failed) never
        // actually persisted a v1 database — SQLiteOpenHelper rolls back and
        // deletes on an onCreate failure — so onUpgrade only matters for the
        // hypothetical device that *did* have fts5 and created a v1 database
        // successfully before this fix.
        //
        // Bumped 2 -> 3 for the Import/Export feature's dataset_id column +
        // datasets table (see onUpgrade/onCreate).
        //
        // Bumped 3 -> 4: liked_media.ai_tagged + media_tags.manual (hand
        // tags no longer stop the AI tagging a post, and survive it).
        //
        // Bumped 4 -> 5: post_sources + tag_sources (one post's tags can come
        // from several datasets; deleting one removes only its own).
        private const val DB_VERSION = 6

        /** Sentinel dataset_id for every row created by the normal on-device
         *  tagging pipeline (as opposed to an imported dataset — see the
         *  `datasets` table's doc comment in [onCreate]). Never actually
         *  shown to the user; just how liked_media rows are told apart. */
        const val LOCAL_DATASET_ID = ""

        @Volatile private var instance: TagDatabase? = null
        fun get(context: Context): TagDatabase =
            instance ?: synchronized(this) {
                instance ?: TagDatabase(context).also { instance = it }
            }
    }
}
