package dev.photopilot.scaffold.data.logic

enum class LibrarySort { CAPTURED_DESC, CAPTURED_ASC, IMPORTED_DESC, NAME_ASC }

data class LibraryCriteria(
    val search: String? = null,
    val sourceIds: Set<String> = emptySet(),
    val orientations: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val noDateOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.CAPTURED_DESC,
) {
    fun isFiltered(): Boolean =
        !search.isNullOrBlank() || sourceIds.isNotEmpty() || orientations.isNotEmpty() ||
            statuses.isNotEmpty() || noDateOnly
}

object LibraryQueryBuilder {

    fun build(criteria: LibraryCriteria, limit: Int, offset: Int): Pair<String, Array<Any>> {
        val args = mutableListOf<Any>()
        val where = StringBuilder(
            " WHERE a.readiness != 'deleted' AND a.deleted_at IS NULL AND a.privacy_excluded = 0",
        )
        val search = criteria.search?.takeIf { it.isNotBlank() }
        if (search != null) {
            where.append(
                " AND (a.id IN (SELECT ca.asset_id FROM collection_asset ca" +
                    " INNER JOIN collection co ON co.id = ca.collection_id" +
                    " WHERE co.name LIKE '%' || ? || '%')" +
                    " OR a.id IN (SELECT ao.asset_id FROM asset_origin ao" +
                    " INNER JOIN source_asset sa ON sa.id = ao.source_asset_id" +
                    " WHERE sa.remote_key LIKE '%' || ? || '%'))",
            )
            args += search
            args += search
        }
        if (criteria.sourceIds.isNotEmpty()) {
            where.append(
                " AND a.id IN (SELECT ao2.asset_id FROM asset_origin ao2" +
                    " INNER JOIN source_asset sa2 ON sa2.id = ao2.source_asset_id" +
                    " WHERE sa2.source_id IN (${placeholders(criteria.sourceIds.size)}))",
            )
            args.addAll(criteria.sourceIds.sorted())
        }
        if (criteria.orientations.isNotEmpty()) {
            where.append(" AND a.orientation_class IN (${placeholders(criteria.orientations.size)})")
            args.addAll(criteria.orientations.sorted())
        }
        if (criteria.statuses.isNotEmpty()) {
            where.append(" AND a.readiness IN (${placeholders(criteria.statuses.size)})")
            args.addAll(criteria.statuses.sorted())
        }
        if (criteria.noDateOnly) {
            where.append(" AND a.captured_at IS NULL")
        }
        val order = when (criteria.sort) {
            LibrarySort.CAPTURED_DESC ->
                " ORDER BY COALESCE(a.captured_at, a.imported_at) DESC, a.id"
            LibrarySort.CAPTURED_ASC ->
                " ORDER BY COALESCE(a.captured_at, a.imported_at) ASC, a.id"
            LibrarySort.IMPORTED_DESC ->
                " ORDER BY a.imported_at DESC, a.id"
            LibrarySort.NAME_ASC ->
                " ORDER BY (SELECT MIN(sa3.remote_key) FROM asset_origin ao3" +
                    " INNER JOIN source_asset sa3 ON sa3.id = ao3.source_asset_id" +
                    " WHERE ao3.asset_id = a.id) ASC, a.id"
        }
        val sql = "SELECT a.* FROM asset a$where$order LIMIT ? OFFSET ?"
        args += limit
        args += offset
        return sql to args.toTypedArray()
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
}
