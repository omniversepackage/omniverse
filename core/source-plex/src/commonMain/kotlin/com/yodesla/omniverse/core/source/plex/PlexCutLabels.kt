package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.knownEditionLabel
import com.yodesla.omniverse.core.model.explicitEditionLabel

/** Prefer an explicit filename edition; Plex has no edition field for each Media inside one movie. */
internal fun plexCutLabel(title: String?, year: Int?, durationSec: Int?, file: String?): String? {
    val name = file.orEmpty().substringAfterLast('/').substringBeforeLast('.')
    val explicit = explicitEditionLabel(name)
    if (explicit != null) return explicit

    return knownEditionLabel(null, title, year, durationSec)
}
