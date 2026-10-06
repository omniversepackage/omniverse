package com.yodesla.omniverse.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether content in a category may be shown right now: `(kind, sourceId, categoryId) -> Boolean`.
 * kind is "LIVE", "VOD" or "SERIES". Parental locks provide the real one (android:parental);
 * screens take a Flow of it so unlocking re-filters everything live.
 */
typealias Visibility = (kind: String, sourceId: String, categoryId: String) -> Boolean

/** Default: everything visible (tests, builds without parental controls). */
val ShowEverything: Flow<Visibility> = flowOf { _, _, _ -> true }
