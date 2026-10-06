package com.yodesla.omniverse.player

import androidx.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureBoxTest {
    private val fourThree = 4f / 3f

    @Test fun fitPillarboxesFourThreeOnWideScreen() = assertEquals(1440f to 1080f, pictureBox(1920f, 1080f, fourThree, ContentScale.Fit))
    @Test fun stretchFillsTheScreen() = assertEquals(1920f to 1080f, pictureBox(1920f, 1080f, fourThree, ContentScale.FillBounds))
    @Test fun zoomCoversTheScreen() = assertEquals(1920f to 1440f, pictureBox(1920f, 1080f, fourThree, ContentScale.Crop))
    @Test fun unknownAspectFills() = assertEquals(1920f to 1080f, pictureBox(1920f, 1080f, 0f, ContentScale.Fit))
}
