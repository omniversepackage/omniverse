package com.yodesla.omniverse.core.data.impl

import com.yodesla.omniverse.core.data.ALL_CHANNELS
import androidx.paging.PagingSource
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.paging3.QueryPagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CatalogRepositoryImpl(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher,
    /** Task 84d: fills backdrop/logo/cert/runtime gaps from the local TMDB cache. Null = off. */
    private val tmdbArt: com.yodesla.omniverse.core.data.metadata.TmdbArt? = null,
    private val profileId: () -> String = { DEFAULT_PROFILE },
) : CatalogRepository {

    private fun art(row: PosterRow): PosterRow = tmdbArt?.fill(row) ?: row
    private fun art(rows: List<PosterRow>): List<PosterRow> = tmdbArt?.fillAll(rows) ?: rows

    /** Task 87b: cache-only re-fill for rows already on screen (browse pages). */
    override suspend fun refillArt(rows: List<PosterRow>): List<PosterRow> =
        if (tmdbArt == null) rows else withContext(io) { tmdbArt.fillAll(rows) }

    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> {
        val q = if (includeHidden) {
            db.readQueries.allCategories(sourceId.value, kind.name)
        } else {
            db.readQueries.visibleCategories(sourceId.value, kind.name, profileId(), "CATEGORY_${kind.name}")
        }
        return q.asFlow().mapToList(io).map { rows ->
            rows.map {
                Category(
                    sourceId = SourceId(it.source_id),
                    kind = kind,
                    remoteId = RemoteId(it.remote_id),
                    name = it.name,
                    parentId = it.parent_id?.let(::RemoteId),
                    sortIndex = it.sort_index.toInt(),
                )
            }
        }
    }

    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> {
        val profile = profileId()
        return QueryPagingSource(
            countQuery = db.readQueries.countChannelsInCategoryVisible(sourceId.value, categoryId.value, profile),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.channelsInCategoryVisible(sourceId.value, categoryId.value, profile, limit, offset) {
                        sid, rid, number, name, logo, epg, _, catchup, _, _, _, _ ->
                    // Row's category = the one being browsed (channels can be in several).
                    channelRow(sid, rid, number, name, logo, epg, categoryId.value, catchup)
                }
            },
        )
    }

    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> {
        val profile = profileId()
        return QueryPagingSource(
            countQuery = db.readQueries.countChannelsAllVisible(sourceId.value, profile, excludedCategories),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.channelsAllVisible(sourceId.value, profile, excludedCategories, limit, offset) {
                        sid, rid, number, name, logo, epg, _, catchup, _, _, _, _ ->
                    channelRow(sid, rid, number, name, logo, epg, ALL_CHANNELS, catchup)
                }
            },
        )
    }

    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = withContext(io) {
        db.readQueries.channelsAllVisible(sourceId.value, profileId(), excludedCategories, Long.MAX_VALUE, 0) {
                sid, rid, number, name, logo, epg, _, catchup, _, _, _, _ ->
            channelRow(sid, rid, number, name, logo, epg, ALL_CHANNELS, catchup)
        }.executeAsList()
    }

    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = withContext(io) {
        db.readQueries.channelIdsAllVisible(sourceId.value, profileId(), excludedCategories).executeAsList()
            .map { ContentKey(sourceId, ContentKind.LIVE, RemoteId(it)) }
    }

    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = withContext(io) {
        db.readQueries.channelsInCategoryVisible(sourceId.value, categoryId.value, profileId(), Long.MAX_VALUE, 0) {
                sid, rid, number, name, logo, epg, _, catchup, _, _, _, _ ->
            channelRow(sid, rid, number, name, logo, epg, categoryId.value, catchup)
        }.executeAsList()
    }

    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> =
        withContext(io) {
            val ids = db.readQueries.channelIdsInCategoryVisible(key.sourceId.value, categoryId.value, profileId()).executeAsList()
            val i = ids.indexOf(key.remoteId.value)
            if (i < 0) return@withContext null to null
            fun at(idx: Int) = db.readQueries.channelByKey(key.sourceId.value, ids[idx]).executeAsOneOrNull()
                ?.toRow()?.copy(categoryId = categoryId)
            val prev = at((i - 1 + ids.size) % ids.size)
            val next = at((i + 1) % ids.size)
            prev to next
        }

    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = withContext(io) {
        db.readQueries.channelIdsInCategoryVisible(sourceId.value, categoryId.value, profileId()).executeAsList()
            .map { ContentKey(sourceId, ContentKind.LIVE, RemoteId(it)) }
    }

    override suspend fun channel(key: ContentKey): ChannelRow? = withContext(io) {
        db.readQueries.channelByKey(key.sourceId.value, key.remoteId.value).executeAsOneOrNull()?.toRow()
    }

    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countVod(sourceId.value, categoryId?.value),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.vodPage(sourceId.value, categoryId?.value, limit, offset) {
                        sid, rid, name, poster, cat, rating, year, _, _, tmdb, _, _, _, _, genre, _ ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), genre = genre?.takeIf(String::isNotBlank)))
                }
            },
        )

    /** Task 115: provider order keeps the untouched vodPage; the other modes use the sorted query. */
    override fun vodSorted(sourceId: SourceId, categoryId: RemoteId?, sort: com.yodesla.omniverse.core.data.BrowseSort): PagingSource<Int, PosterRow> =
        if (sort == com.yodesla.omniverse.core.data.BrowseSort.PROVIDER) vod(sourceId, categoryId)
        else QueryPagingSource(
            countQuery = db.readQueries.countVod(sourceId.value, categoryId?.value),
            transacter = db.readQueries, context = io,
            queryProvider = { limit, offset ->
                db.readQueries.vodPageSorted(profileId(), sourceId.value, categoryId?.value, sort.ordinal.toLong(), limit, offset) {
                        sid, rid, name, poster, cat, rating, year, tmdb, genre ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), genre = genre?.takeIf(String::isNotBlank)))
                }
            },
        )

    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countVodAll(excludedCategoryKeys),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.vodPageAll(excludedCategoryKeys, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                }
            },
        )

    override fun vodAllAlphabetic(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countVodAll(excludedCategoryKeys), transacter = db.readQueries, context = io,
            queryProvider = { limit, offset -> db.readQueries.vodPageAllAlphabetic(profileId(), excludedCategoryKeys, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
            } },
        )

    /** Task 115: provider/A–Z keep their untouched queries; year/rating use the sorted one. */
    override fun vodAllSorted(excludedCategoryKeys: Collection<String>, sort: com.yodesla.omniverse.core.data.BrowseSort): PagingSource<Int, PosterRow> =
        when (sort) {
            com.yodesla.omniverse.core.data.BrowseSort.PROVIDER -> vodAll(excludedCategoryKeys)
            com.yodesla.omniverse.core.data.BrowseSort.ALPHABETICAL -> vodAllAlphabetic(excludedCategoryKeys)
            else -> QueryPagingSource(
                countQuery = db.readQueries.countVodAll(excludedCategoryKeys), transacter = db.readQueries, context = io,
                queryProvider = { limit, offset -> db.readQueries.vodPageAllSorted(profileId(), excludedCategoryKeys, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                } },
            )
        }

    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countSeriesAll(excludedCategoryKeys),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.seriesPageAll(excludedCategoryKeys, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                }
            },
        )

    override fun seriesAllAlphabetic(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countSeriesAll(excludedCategoryKeys), transacter = db.readQueries, context = io,
            queryProvider = { limit, offset -> db.readQueries.seriesPageAllAlphabetic(profileId(), excludedCategoryKeys, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
            } },
        )

    /** Task 115: provider/A–Z keep their untouched queries; year/rating use the sorted one. */
    override fun seriesAllSorted(excludedCategoryKeys: Collection<String>, sort: com.yodesla.omniverse.core.data.BrowseSort): PagingSource<Int, PosterRow> =
        when (sort) {
            com.yodesla.omniverse.core.data.BrowseSort.PROVIDER -> seriesAll(excludedCategoryKeys)
            com.yodesla.omniverse.core.data.BrowseSort.ALPHABETICAL -> seriesAllAlphabetic(excludedCategoryKeys)
            else -> QueryPagingSource(
                countQuery = db.readQueries.countSeriesAll(excludedCategoryKeys), transacter = db.readQueries, context = io,
                queryProvider = { limit, offset -> db.readQueries.seriesPageAllSorted(profileId(), excludedCategoryKeys, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                } },
            )
        }

    override fun animeLibrary(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String>,
        alphabetic: Boolean,
    ): PagingSource<Int, PosterRow> =
        animeLibrarySorted(kind, excludedCategoryKeys, if (alphabetic) com.yodesla.omniverse.core.data.BrowseSort.ALPHABETICAL else com.yodesla.omniverse.core.data.BrowseSort.PROVIDER)

    /** Task 115: the anime library honours the same four browse sorts (SQL-side, before paging). */
    override fun animeLibrarySorted(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String>,
        sort: com.yodesla.omniverse.core.data.BrowseSort,
    ): PagingSource<Int, PosterRow> =
        animeLibraryPaged(kind, excludedCategoryKeys, filterArgs(com.yodesla.omniverse.core.data.SmartCollectionFilter()), sort)

    /** Task 117: the anime library with the browse filter in SQL (task 117 library grid). */
    override fun animeLibraryFilteredSorted(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String>,
        filter: com.yodesla.omniverse.core.data.SmartCollectionFilter,
        sort: com.yodesla.omniverse.core.data.BrowseSort,
    ): PagingSource<Int, PosterRow> = animeLibraryPaged(kind, excludedCategoryKeys, filterArgs(filter), sort)

    /** Task 117: one paging path for both anime-library variants; NULL filter args = no filter. */
    private fun animeLibraryPaged(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String>,
        a: FilterArgs,
        sort: com.yodesla.omniverse.core.data.BrowseSort,
    ): PagingSource<Int, PosterRow> = if (kind == ContentKind.SERIES) {
        QueryPagingSource(
            countQuery = db.readQueries.countSeriesAnime(a.genresCsv, excludedCategoryKeys, a.yearFrom,
                a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                a.plexOnly, a.uhdOnly),
            transacter = db.readQueries, context = io,
            queryProvider = { limit, offset ->
                db.readQueries.seriesPageAnime(a.genresCsv, profileId(), excludedCategoryKeys, a.yearFrom,
                    a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                }
            },
        )
    } else {
        QueryPagingSource(
            countQuery = db.readQueries.countVodAnime(a.genresCsv, excludedCategoryKeys, a.yearFrom,
                a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                a.plexOnly, a.uhdOnly),
            transacter = db.readQueries, context = io,
            queryProvider = { limit, offset ->
                db.readQueries.vodPageAnime(a.genresCsv, profileId(), excludedCategoryKeys, a.yearFrom,
                    a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                }
            },
        )
    }

    override suspend fun categoryName(sourceId: SourceId, kind: ContentKind, categoryId: RemoteId): String? = withContext(io) {
        db.readQueries.categoryName(sourceId.value, kind.name, categoryId.value).executeAsOneOrNull()
    }

    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> =
        QueryPagingSource(
            countQuery = db.readQueries.countSeries(sourceId.value, categoryId?.value),
            transacter = db.readQueries,
            context = io,
            queryProvider = { limit, offset ->
                db.readQueries.seriesPage(sourceId.value, categoryId?.value, limit, offset) {
                        sid, rid, name, poster, backdrop, cat, plot, genre, rating, year, _, _, _, _, _, tmdb, _ ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank),
                        backdropUrl = backdrop?.takeIf(String::isNotBlank), plot = plot?.takeIf(String::isNotBlank), genre = genre?.takeIf(String::isNotBlank)))
                }
            },
        )

    /** Task 115: provider order keeps the untouched seriesPage; the other modes use the sorted query. */
    override fun seriesSorted(sourceId: SourceId, categoryId: RemoteId?, sort: com.yodesla.omniverse.core.data.BrowseSort): PagingSource<Int, PosterRow> =
        if (sort == com.yodesla.omniverse.core.data.BrowseSort.PROVIDER) series(sourceId, categoryId)
        else QueryPagingSource(
            countQuery = db.readQueries.countSeries(sourceId.value, categoryId?.value),
            transacter = db.readQueries, context = io,
            queryProvider = { limit, offset ->
                db.readQueries.seriesPageSorted(profileId(), sourceId.value, categoryId?.value, sort.ordinal.toLong(), limit, offset) {
                        sid, rid, name, poster, cat, rating, year, tmdb, genre, backdrop, plot ->
                    art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank),
                        backdropUrl = backdrop?.takeIf(String::isNotBlank), plot = plot?.takeIf(String::isNotBlank), genre = genre?.takeIf(String::isNotBlank)))
                }
            },
        )

    override fun vodFiltered(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: com.yodesla.omniverse.core.data.SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>,
        alphabetic: Boolean,
    ): PagingSource<Int, PosterRow> =
        vodFilteredSorted(sourceId, categoryId, filter, excludedCategoryKeys, if (alphabetic) com.yodesla.omniverse.core.data.BrowseSort.ALPHABETICAL else com.yodesla.omniverse.core.data.BrowseSort.PROVIDER)

    /** Task 115: filtered browse ordered by [sort] in SQL before paging; provider keeps the old queries. */
    override fun vodFilteredSorted(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: com.yodesla.omniverse.core.data.SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>,
        sort: com.yodesla.omniverse.core.data.BrowseSort,
    ): PagingSource<Int, PosterRow> {
        val a = filterArgs(filter)
        return if (sourceId == null) {
            QueryPagingSource(
                countQuery = db.readQueries.countVodAllFiltered(a.genresCsv, excludedCategoryKeys, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.vodPageAllFiltered(a.genresCsv, profileId(), excludedCategoryKeys, a.yearFrom,
                        a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                    }
                },
            )
        } else if (sort == com.yodesla.omniverse.core.data.BrowseSort.PROVIDER) {
            QueryPagingSource(
                countQuery = db.readQueries.countVodFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.vodPageFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                        a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                    }
                },
            )
        } else {
            QueryPagingSource(
                countQuery = db.readQueries.countVodFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.vodPageFilteredSorted(a.genresCsv, profileId(), sourceId.value, categoryId?.value, a.yearFrom,
                        a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
                    }
                },
            )
        }
    }

    override fun seriesFiltered(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: com.yodesla.omniverse.core.data.SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>,
        alphabetic: Boolean,
    ): PagingSource<Int, PosterRow> =
        seriesFilteredSorted(sourceId, categoryId, filter, excludedCategoryKeys, if (alphabetic) com.yodesla.omniverse.core.data.BrowseSort.ALPHABETICAL else com.yodesla.omniverse.core.data.BrowseSort.PROVIDER)

    /** Task 115: filtered browse ordered by [sort] in SQL before paging; provider keeps the old queries. */
    override fun seriesFilteredSorted(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: com.yodesla.omniverse.core.data.SmartCollectionFilter,
        excludedCategoryKeys: Collection<String>,
        sort: com.yodesla.omniverse.core.data.BrowseSort,
    ): PagingSource<Int, PosterRow> {
        val a = filterArgs(filter)
        return if (sourceId == null) {
            QueryPagingSource(
                countQuery = db.readQueries.countSeriesAllFiltered(a.genresCsv, excludedCategoryKeys, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.seriesPageAllFiltered(a.genresCsv, profileId(), excludedCategoryKeys, a.yearFrom,
                        a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                    }
                },
            )
        } else if (sort == com.yodesla.omniverse.core.data.BrowseSort.PROVIDER) {
            QueryPagingSource(
                countQuery = db.readQueries.countSeriesFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.seriesPageFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                        a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                    }
                },
            )
        } else {
            QueryPagingSource(
                countQuery = db.readQueries.countSeriesFiltered(a.genresCsv, sourceId.value, categoryId?.value, a.yearFrom,
                    a.yearTo, a.minRating, a.started, profileId(), a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                    a.plexOnly, a.uhdOnly),
                transacter = db.readQueries, context = io,
                queryProvider = { limit, offset ->
                    db.readQueries.seriesPageFilteredSorted(a.genresCsv, profileId(), sourceId.value, categoryId?.value, a.yearFrom,
                        a.yearTo, a.minRating, a.started, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                        a.plexOnly, a.uhdOnly, sort.ordinal.toLong(), limit, offset) { sid, rid, name, poster, cat, rating, year, tmdb, backdrop, plot ->
                        art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank), backdrop?.takeIf(String::isNotBlank), plot?.takeIf(String::isNotBlank)))
                    }
                },
            )
        }
    }

    override suspend fun genreOptions(
        kind: ContentKind,
        sourceId: SourceId?,
        categoryId: RemoteId?,
        excludedCategoryKeys: Collection<String>,
    ): List<String> = withContext(io) {
        if (kind != ContentKind.VOD && kind != ContentKind.SERIES) return@withContext emptyList()
        db.readQueries.browseGenreOptions(kind.name, sourceId?.value, categoryId?.value, excludedCategoryKeys)
            .executeAsList()
            .flatMap { it.split(',', '|') }
            .map { it.trim() }
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = recentlyAdded(kind, limit, emptyList())

    override fun recentlyAdded(kind: ContentKind, limit: Int, excludedKeys: Collection<String>): Flow<List<PosterRow>> = when (kind) {
        ContentKind.VOD -> db.readQueries.recentVod(excludedKeys, limit.toLong()).asFlow().mapToList(io).map { r -> art(r.map { it.toPoster() }) }
        ContentKind.SERIES -> db.readQueries.recentSeries(excludedKeys, limit.toLong()).asFlow().mapToList(io).map { r -> art(r.map { it.toPoster() }) }
        else -> kotlinx.coroutines.flow.flowOf(emptyList())
    }

    override suspend fun poster(key: ContentKey): PosterRow? = withContext(io) {
        when (key.kind) {
            ContentKind.VOD -> db.readQueries.vodByKey(key.sourceId.value, key.remoteId.value).executeAsOneOrNull()?.toPoster()?.let(::art)
            ContentKind.SERIES -> db.readQueries.seriesByKey(key.sourceId.value, key.remoteId.value).executeAsOneOrNull()?.toPoster()?.let(::art)
            else -> null
        }
    }

    override suspend fun exactMovieMatches(key: ContentKey): List<PosterRow> = withContext(io) {
        if (key.kind != ContentKind.VOD) return@withContext emptyList()
        db.readQueries.otherVodWithSameTmdb(key.sourceId.value, key.remoteId.value) { sid, rid, name, poster, cat, rating, year, tmdb ->
            art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
        }.executeAsList()
    }

    override suspend fun exactSeriesMatches(key: ContentKey): List<PosterRow> = withContext(io) {
        if (key.kind != ContentKind.SERIES) return@withContext emptyList()
        db.readQueries.otherSeriesWithSameTmdb(key.sourceId.value, key.remoteId.value) { sid, rid, name, poster, cat, rating, year, tmdb ->
            art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
        }.executeAsList()
    }

    override suspend fun titleCandidates(identity: TitleIdentity): List<PosterRow> = withContext(io) {
        if (identity.namespace != "tmdb" || identity.externalId.isBlank()) return@withContext emptyList()
        when (identity.kind) {
            ContentKind.VOD -> db.readQueries.vodByTmdb(identity.externalId) { sid, rid, name, poster, cat, rating, year, tmdb ->
                art(PosterRow(ContentKey(SourceId(sid), ContentKind.VOD, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
            }.executeAsList()
            ContentKind.SERIES -> db.readQueries.seriesByTmdb(identity.externalId) { sid, rid, name, poster, cat, rating, year, tmdb ->
                art(PosterRow(ContentKey(SourceId(sid), ContentKind.SERIES, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
            }.executeAsList()
            else -> emptyList()
        }
    }

    override suspend fun smartCollectionItems(kind: ContentKind, filter: com.yodesla.omniverse.core.data.SmartCollectionFilter, limit: Int): List<PosterRow> = withContext(io) {
        val source = filter.sourceId?.takeIf(String::isNotBlank)
        val title = filter.titleContains?.trim()?.takeIf(String::isNotBlank)
        val maxRows = limit.coerceIn(1, 200)
        val a = filterArgs(filter)
        when (kind) {
            ContentKind.VOD -> db.readQueries.smartVodCandidates(a.genresCsv, source, title, a.yearFrom,
                a.yearTo, a.minRating, filter.genreContains?.trim()?.takeIf(String::isNotBlank),
                a.started, profileId(), a.hasEditionLabel, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                a.plexOnly, a.uhdOnly, filter.categoryId?.takeIf(String::isNotBlank), maxRows.toLong()) { sid, rid, name, poster, cat, rating, year, tmdb ->
                art(PosterRow(ContentKey(SourceId(sid), kind, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
            }.executeAsList()
            ContentKind.SERIES -> db.readQueries.smartSeriesCandidates(a.genresCsv, source, title, a.yearFrom,
                a.yearTo, a.minRating, filter.genreContains?.trim()?.takeIf(String::isNotBlank),
                a.started, profileId(), a.hasEditionLabel, a.completed, a.runtimeAtLeast, a.runtimeAtMost,
                a.plexOnly, a.uhdOnly, filter.categoryId?.takeIf(String::isNotBlank), maxRows.toLong()) { sid, rid, name, poster, cat, rating, year, tmdb ->
                art(PosterRow(ContentKey(SourceId(sid), kind, RemoteId(rid)), name, poster, year?.toInt(), rating?.toFloat(), RemoteId(cat), tmdb?.takeIf(String::isNotBlank)))
            }.executeAsList()
            else -> emptyList()
        }
    }

    override suspend fun similarTo(seed: ContentKey, limit: Int): List<PosterRow> = withContext(io) {
        if (limit <= 0) return@withContext emptyList()
        val seedGenre = (when (seed.kind) {
            ContentKind.VOD -> db.readQueries.vodByKey(seed.sourceId.value, seed.remoteId.value).executeAsOneOrNull()?.genre
            ContentKind.SERIES -> db.readQueries.seriesByKey(seed.sourceId.value, seed.remoteId.value).executeAsOneOrNull()?.genre
            else -> null
        }).orEmpty()
        val seedTokens = seedGenre.split(',', '|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (seedTokens.isEmpty()) return@withContext emptyList()
        // +3 per shared genre token. The +2 cast / +2 director rules are vacuous today: the vod/series
        // schema carries no cast or director column, so those sides of the comparison are always empty.
        db.readQueries.similarCandidates(seedGenre, seed.kind.name, seed.sourceId.value, seed.remoteId.value, CANDIDATE_SCAN_LIMIT) {
                source_id, remote_id, name, poster_url, primary_category_id, rating, year, tmdb_id, genre ->
            SimilarCandidate(
                art(PosterRow(
                    key = ContentKey(SourceId(source_id), seed.kind, RemoteId(remote_id)),
                    name = name, posterUrl = poster_url, year = year?.toInt(), rating = rating?.toFloat(),
                    categoryId = RemoteId(primary_category_id), tmdbId = tmdb_id?.takeIf(String::isNotBlank),
                )),
                genre?.lowercase(),
            )
        }.executeAsList()
            .map { c -> c.copy(score = 3 * seedTokens.count { token -> c.genre?.contains(token) == true }) }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<SimilarCandidate> { it.score }
                    .thenByDescending { it.poster.rating ?: -1f }
                    .thenBy { it.poster.name.lowercase() }
                    .thenBy { it.poster.key.sourceId.value }
                    .thenBy { it.poster.key.remoteId.value },
            )
            .take(limit)
            .map { it.poster }
    }

    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> =
        db.readQueries.countByKind(sourceId.value).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { r -> r.kind?.let { k -> runCatching { ContentKind.valueOf(k) }.getOrNull()?.let { it to r.n } } }.toMap()
        }
}

private const val CANDIDATE_SCAN_LIMIT = 300L

private data class SimilarCandidate(val poster: PosterRow, val genre: String?, val score: Int = 0)

/** SQL-side browse filter arguments (task 84i): booleans become 1/0/NULL, genres become a CSV. */
private class FilterArgs(
    val genresCsv: String?,
    val yearFrom: Long?,
    val yearTo: Long?,
    val minRating: Double?,
    val started: Long?,
    val completed: Long?,
    val hasEditionLabel: Long?,
    val runtimeAtLeast: Long?,
    val runtimeAtMost: Long?,
    val plexOnly: Long?,
    val uhdOnly: Long?,
)

private fun filterArgs(f: com.yodesla.omniverse.core.data.SmartCollectionFilter): FilterArgs = FilterArgs(
    genresCsv = f.genres?.map(String::trim)?.filter(String::isNotEmpty)?.joinToString(",")?.takeIf(String::isNotEmpty),
    yearFrom = f.yearFrom?.toLong(),
    yearTo = f.yearTo?.toLong(),
    minRating = f.ratingAtLeast?.toDouble(),
    started = f.started?.let { if (it) 1L else 0L },
    completed = f.completed?.let { if (it) 1L else 0L },
    hasEditionLabel = f.hasEditionLabel?.let { if (it) 1L else 0L },
    runtimeAtLeast = f.runtimeAtLeastMin?.toLong(),
    runtimeAtMost = f.runtimeAtMostMin?.toLong(),
    plexOnly = f.plexOnly?.let { if (it) 1L else 0L },
    uhdOnly = f.uhdOnly?.let { if (it) 1L else 0L },
)
