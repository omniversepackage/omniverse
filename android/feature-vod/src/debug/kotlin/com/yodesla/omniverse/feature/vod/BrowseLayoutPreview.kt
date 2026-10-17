package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.metadata.RtScores
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.flow.flowOf

/** Debug-only geometry fixture: the real brand renderers, populated with long text and scores. */
@Composable
fun BrowseLayoutPreview(brandName: String, series: Boolean, logo: Boolean) {
    val brand = CategoryBrand.entries.firstOrNull { it.name == brandName.uppercase() } ?: categoryBrand(brandName) ?: CategoryBrand.NETFLIX
    val kind = if(series) ContentKind.SERIES else ContentKind.VOD
    val rows = remember(brand,kind,logo) { (1..24).map { index ->
        PosterRow(ContentKey(SourceId("qa"),kind,RemoteId("$index")),
            "A Very Long Title About Heroes, Journeys and Impossible Adventures $index",null, categoryId=RemoteId("qa"),
            year=2026,rating=8.7f,genre="Action, Adventure, Drama, Fantasy, Animation, Horror, Science Fiction, Anime",
            backdropUrl="android.resource://android/drawable/ic_dialog_info",
            plot="A long synopsis tests complete readable lines without slicing words at the shelf boundary. The heroes travel across the world to discover the truth about their extraordinary adventure.",
            logoUrl=if(logo) "android.resource://android/drawable/ic_dialog_info" else null)
    } }
    val items=remember(rows){flowOf(PagingData.from(rows))}.collectAsLazyPagingItems()
    val focus=remember{FocusRequester()}
    BrandLayout(brand,items,if(series)"Shows" else "Movies",focus,{},scores={RtScores(83,96)})
    LaunchedEffect(items.itemCount){if(items.itemCount>0)focus.requestFocusWhenReady()}
}
