package com.yodesla.omniverse.core.data.impl

/**
 * Cross-source identity for movies/shows: "normalized title|year". Plex and IPTV rows with the
 * same key are the same title, and Movies/Shows show only one (Plex wins). Conservative on
 * purpose: no year (neither given nor in the name) → null → never merged.
 */
internal object MatchKey {
    // "EN - ", "EN: ", "|EN| ", "[EN] ", "4K-UK - " style provider prefixes.
    private val prefix = Regex("""^\s*(?:[|\[(]?[A-Z0-9]{2,5}(?:[-_ ][A-Z0-9]{2,5})?[|\])]?\s*[-:|]\s*|\|[A-Z0-9 ]{2,8}\|\s*|\[[A-Z0-9 ]{2,8}]\s*)""")
    private val yearInName = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val quality = Regex("""\b(4k|uhd|fhd|hd|sd|hdr|hevc|x265|x264|1080p|720p|2160p|multi|multisub|subbed|dubbed|vost(?:fr)?|remastered)\b""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    fun of(name: String, year: Int?): String? {
        var n = name.trim()
        n = prefix.replace(n, "")
        val nameYear = yearInName.find(n)?.groupValues?.get(1)?.toInt()
        n = yearInName.replace(n, " ")
        val y = year?.takeIf { it in 1900..2100 } ?: nameYear ?: return null
        n = n.lowercase()
        n = quality.replace(n, " ")
        n = n.replace("&", " and ")
        n = nonAlnum.replace(n, " ").trim()
        if (n.startsWith("the ")) n = n.removePrefix("the ")
        n = n.replace(" ", "")
        return if (n.isEmpty()) null else "$n|$y"
    }
}
