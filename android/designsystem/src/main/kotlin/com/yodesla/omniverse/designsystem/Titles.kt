package com.yodesla.omniverse.designsystem

private val trailingYear = Regex("""\s*[(\[](\d{4})[)\]]\s*$""")

/** "Movie Title (2019)" + year 2019 → "Movie Title" (the year is shown separately). Keeps other years. */
fun displayTitle(name: String, year: Int?): String {
    val m = trailingYear.find(name) ?: return name
    if (year != null && m.groupValues[1].toInt() != year) return name
    return name.removeRange(m.range).ifBlank { name }
}
