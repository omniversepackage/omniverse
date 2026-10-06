package com.yodesla.omniverse.core.data.categories

/**
 * Task 84h: the language/region a category NAME declares ("US| SPORTS", "AR - MBC", "EX-YU",
 * "ARABIC"). Only category names are ever classified, never channel names. Anything that cannot
 * be placed with confidence is [CategoryLanguage.UNKNOWN] and stays visible unless the profile
 * also turns off "Keep untagged categories".
 */
enum class CategoryLanguage(val label: String) {
    ENGLISH("English"),
    ARABIC("Arabic"),
    FRENCH("French"),
    GERMAN("German"),
    SPANISH("Spanish"),
    ITALIAN("Italian"),
    PORTUGUESE("Portuguese"),
    DUTCH("Dutch"),
    POLISH("Polish"),
    TURKISH("Turkish"),
    RUSSIAN("Russian"),
    GREEK("Greek"),
    ROMANIAN("Romanian"),
    ALBANIAN("Albanian"),
    HINDI("Hindi"),
    URDU("Urdu"),
    PERSIAN("Persian"),
    KURDISH("Kurdish"),
    NORDIC("Nordic"),
    AFRICAN("African"),
    EXYU("Ex-Yu"),
    UNKNOWN("Other"),
    ;

    companion object {
        /** Every group the picker can offer (UNKNOWN is handled by "Keep untagged"). */
        val selectable: List<CategoryLanguage> = entries.filter { it != UNKNOWN }
    }
}

/**
 * Classify one category name. Matching is whole-token only: "ARG" is Spanish, "AR" is Arabic,
 * "UKRAINE" is not English, "MINI" is not Hindi. Two-letter ISO codes count only at the start or
 * end of the name, or when a punctuation separator pins them ("News·US"), so ordinary English
 * words like "IN" or "NO" inside a title ("Movies IN 4K") do not tag the category.
 */
fun categoryLanguage(name: String): CategoryLanguage {
    val upper = name.uppercase()
    if (EXYU_MARKERS.any { upper.contains(it) }) return CategoryLanguage.EXYU
    val toks = tokenize(name)
    var found: CategoryLanguage? = null
    for ((i, t) in toks.withIndex()) {
        val byWord = WORDS[t.text]
        if (byWord != null) {
            if (found == null || found == byWord) found = byWord else return CategoryLanguage.UNKNOWN
        }
        if (t.text.length == 2) {
            val pinned = i == 0 || i == toks.size - 1 || t.punctAfter || (i > 0 && toks[i - 1].punctAfter)
            val byCode = if (pinned) CODES[t.text] else null
            if (byCode != null) {
                if (found == null || found == byCode) found = byCode else return CategoryLanguage.UNKNOWN
            }
        }
    }
    return found ?: CategoryLanguage.UNKNOWN
}

/**
 * Whether a category of [language] passes the profile's filter. An empty [allowed] set means the
 * filter is off ("All"). Untagged categories are governed only by [keepUntagged].
 */
fun categoryLanguageAllowed(
    language: CategoryLanguage,
    allowed: Set<CategoryLanguage>,
    keepUntagged: Boolean,
): Boolean = when {
    allowed.isEmpty() -> true
    language == CategoryLanguage.UNKNOWN -> keepUntagged
    else -> language in allowed
}

/** What the Live TV chip shows while the filter is on: "English only" + how many categories it hides. */
data class CategoryLanguageSummary(val label: String, val hiddenLive: Int)

fun categoryLanguageFilterLabel(allowed: Set<CategoryLanguage>): String {
    val names = CategoryLanguage.selectable.filter { it in allowed }.map { it.label }
    return if (names.size == 1) "${names.first()} only" else names.joinToString(" + ")
}

const val CATEGORY_LANG_FILTER_KEY = "category_lang_filter"
const val CATEGORY_LANG_KEEP_UNTAGGED_KEY = "category_lang_keep_untagged"
const val CATEGORY_LANG_FILTER_VOD_KEY = "category_lang_filter_vod"

/** Stored as "all" or "sel:ENGLISH,ARABIC". Anything unparseable reads back as "all" (filter off). */
fun parseCategoryLanguageFilter(value: String?): Set<CategoryLanguage> = when {
    value == null || !value.startsWith("sel:") -> emptySet()
    else -> value.removePrefix("sel:").split(',')
        .mapNotNull { v -> CategoryLanguage.entries.firstOrNull { it.name == v.trim() } }
        .filter { it != CategoryLanguage.UNKNOWN }
        .toSet()
}

fun encodeCategoryLanguageFilter(selected: Set<CategoryLanguage>): String =
    if (selected.isEmpty()) "all" else "sel:" + CategoryLanguage.selectable.filter { it in selected }.joinToString(",") { it.name }

private data class Tok(val text: String, val punctAfter: Boolean)

private val EXYU_MARKERS = listOf("EXYU", "EX-YU", "EX\u2013YU", "EX\u2014YU")

private const val PUNCT = "|:[]{}(),;!?&=+@#%$^*~<>»«/\\“”‘’\"'"

private fun tokenize(name: String): List<Tok> {
    val out = ArrayList<Tok>()
    val sb = StringBuilder()
    fun flush(punct: Boolean) {
        if (sb.isNotEmpty()) { out += Tok(sb.toString(), punct); sb.setLength(0) }
    }
    for (c in name) {
        val mapped = DIACRITICS[c]
        when {
            mapped != null -> sb.append(mapped)
            c.isWhitespace() -> flush(false)
            c == '-' || c == '\u2013' || c == '\u2014' || c in PUNCT -> flush(true)
            c.isLetterOrDigit() -> sb.append(c.uppercaseChar())
            else -> flush(true)
        }
    }
    flush(false)
    return out
}

private val DIACRITICS: Map<Char, String> = mapOf(
    '\u00C0' to "A", '\u00C1' to "A", '\u00C2' to "A", '\u00C3' to "A", '\u00C4' to "A", '\u00C5' to "A",
    '\u00C6' to "AE", '\u00C7' to "C", '\u00C8' to "E", '\u00C9' to "E", '\u00CA' to "E", '\u00CB' to "E",
    '\u00CC' to "I", '\u00CD' to "I", '\u00CE' to "I", '\u00CF' to "I", '\u00D0' to "D", '\u00D1' to "N",
    '\u00D2' to "O", '\u00D3' to "O", '\u00D4' to "O", '\u00D5' to "O", '\u00D6' to "O", '\u00D8' to "O",
    '\u00D9' to "U", '\u00DA' to "U", '\u00DB' to "U", '\u00DC' to "U", '\u00DD' to "Y", '\u00DE' to "TH",
    '\u00DF' to "SS", '\u00E0' to "A", '\u00E1' to "A", '\u00E2' to "A", '\u00E3' to "A", '\u00E4' to "A",
    '\u00E5' to "A", '\u00E6' to "AE", '\u00E7' to "C", '\u00E8' to "E", '\u00E9' to "E", '\u00EA' to "E",
    '\u00EB' to "E", '\u00EC' to "I", '\u00ED' to "I", '\u00EE' to "I", '\u00EF' to "I", '\u00F0' to "D",
    '\u00F1' to "N", '\u00F2' to "O", '\u00F3' to "O", '\u00F4' to "O", '\u00F5' to "O", '\u00F6' to "O",
    '\u00F8' to "O", '\u00F9' to "U", '\u00FA' to "U", '\u00FB' to "U", '\u00FC' to "U", '\u00FD' to "Y",
    '\u00FE' to "TH", '\u00FF' to "Y", '\u0130' to "I", '\u0131' to "I", '\u015E' to "S", '\u015F' to "S",
    '\u0160' to "S", '\u0161' to "S", '\u017D' to "Z", '\u017E' to "Z", '\u010C' to "C", '\u010D' to "C",
    '\u0106' to "C", '\u0107' to "C", '\u010A' to "C", '\u010B' to "C", '\u0118' to "E", '\u0119' to "E",
    '\u0141' to "L", '\u0142' to "L", '\u0143' to "N", '\u0144' to "N", '\u015A' to "S", '\u015B' to "S",
    '\u0179' to "Z", '\u017A' to "Z", '\u0110' to "D", '\u0111' to "D", '\u011E' to "G", '\u011F' to "G",
    '\u0139' to "L", '\u013A' to "L", '\u0158' to "R", '\u0159' to "R", '\u016A' to "U", '\u016B' to "U",
    '\u0218' to "S", '\u0219' to "S", '\u021A' to "T", '\u021B' to "T", '\u01FC' to "AE", '\u01FD' to "AE",
    '\u0152' to "OE", '\u0153' to "OE", '\u01C4' to "DZ", '\u01C6' to "DZ", '\u01C7' to "LJ", '\u01C9' to "LJ",
    '\u01CA' to "NJ", '\u01CC' to "NJ", '\u01F1' to "DZ", '\u01F3' to "DZ", '\u01F4' to "GJ", '\u01F5' to "GJ",
    '\u01C8' to "LJ", '\u01CB' to "NJ", '\u01C5' to "DZ", '\u01F2' to "DZ",
)

private val WORDS: Map<String, CategoryLanguage> = mapOf(
    "ENGLISH" to CategoryLanguage.ENGLISH, "ENGLAND" to CategoryLanguage.ENGLISH,
    "BRITAIN" to CategoryLanguage.ENGLISH, "BRITISH" to CategoryLanguage.ENGLISH,
    "AMERICA" to CategoryLanguage.ENGLISH, "AMERICAS" to CategoryLanguage.ENGLISH,
    "AMERICAN" to CategoryLanguage.ENGLISH, "USA" to CategoryLanguage.ENGLISH,
    "SCOTLAND" to CategoryLanguage.ENGLISH, "WALES" to CategoryLanguage.ENGLISH,
    "CANADA" to CategoryLanguage.ENGLISH, "AUSTRALIA" to CategoryLanguage.ENGLISH,
    "AUSTRALIAN" to CategoryLanguage.ENGLISH, "IRELAND" to CategoryLanguage.ENGLISH,
    "IRISH" to CategoryLanguage.ENGLISH, "ZEALAND" to CategoryLanguage.ENGLISH,

    "ARABIC" to CategoryLanguage.ARABIC, "ARAB" to CategoryLanguage.ARABIC,
    "ARABI" to CategoryLanguage.ARABIC, "ARABIA" to CategoryLanguage.ARABIC,
    "ARABIAN" to CategoryLanguage.ARABIC,

    "FRENCH" to CategoryLanguage.FRENCH, "FRANCE" to CategoryLanguage.FRENCH,
    "QUEBEC" to CategoryLanguage.FRENCH,

    "GERMAN" to CategoryLanguage.GERMAN, "GERMANY" to CategoryLanguage.GERMAN,
    "DEUTSCH" to CategoryLanguage.GERMAN, "DEUTSCHLAND" to CategoryLanguage.GERMAN,
    "OSTERREICH" to CategoryLanguage.GERMAN, "AUSTRIA" to CategoryLanguage.GERMAN,
    "SCHWEIZ" to CategoryLanguage.GERMAN,

    "SPANISH" to CategoryLanguage.SPANISH, "SPAIN" to CategoryLanguage.SPANISH,
    "ESPANOL" to CategoryLanguage.SPANISH, "ESPANA" to CategoryLanguage.SPANISH,
    "LATINO" to CategoryLanguage.SPANISH, "LATINA" to CategoryLanguage.SPANISH,
    "LATINOAMERICA" to CategoryLanguage.SPANISH, "LATAM" to CategoryLanguage.SPANISH,
    "HISPANO" to CategoryLanguage.SPANISH, "HISPANIC" to CategoryLanguage.SPANISH,
    "ARG" to CategoryLanguage.SPANISH, "ARGENTINA" to CategoryLanguage.SPANISH,
    "CHILE" to CategoryLanguage.SPANISH, "COLOMBIA" to CategoryLanguage.SPANISH,
    "MEXICO" to CategoryLanguage.SPANISH, "VENEZUELA" to CategoryLanguage.SPANISH,
    "URUGUAY" to CategoryLanguage.SPANISH, "PARAGUAY" to CategoryLanguage.SPANISH,

    "ITALIAN" to CategoryLanguage.ITALIAN, "ITALY" to CategoryLanguage.ITALIAN,
    "ITALIA" to CategoryLanguage.ITALIAN,

    "PORTUGUESE" to CategoryLanguage.PORTUGUESE, "PORTUGAL" to CategoryLanguage.PORTUGUESE,
    "BRASIL" to CategoryLanguage.PORTUGUESE, "BRAZIL" to CategoryLanguage.PORTUGUESE,

    "DUTCH" to CategoryLanguage.DUTCH, "NETHERLANDS" to CategoryLanguage.DUTCH,
    "HOLLAND" to CategoryLanguage.DUTCH, "VLAANDEREN" to CategoryLanguage.DUTCH,
    "VLAAMS" to CategoryLanguage.DUTCH,

    "POLISH" to CategoryLanguage.POLISH, "POLAND" to CategoryLanguage.POLISH,
    "POLSKA" to CategoryLanguage.POLISH, "POLSKIE" to CategoryLanguage.POLISH,

    "TURKISH" to CategoryLanguage.TURKISH, "TURKEY" to CategoryLanguage.TURKISH,
    "TURKIYE" to CategoryLanguage.TURKISH, "TURK" to CategoryLanguage.TURKISH,

    "RUSSIAN" to CategoryLanguage.RUSSIAN, "RUSSIA" to CategoryLanguage.RUSSIAN,

    "GREEK" to CategoryLanguage.GREEK, "GREECE" to CategoryLanguage.GREEK,

    "ROMANIAN" to CategoryLanguage.ROMANIAN, "ROMANIA" to CategoryLanguage.ROMANIAN,

    "ALBANIAN" to CategoryLanguage.ALBANIAN, "ALBANIA" to CategoryLanguage.ALBANIAN,
    "SHQIP" to CategoryLanguage.ALBANIAN,

    "HINDI" to CategoryLanguage.HINDI, "HINDUSTAN" to CategoryLanguage.HINDI,
    "INDIA" to CategoryLanguage.HINDI, "BHARAT" to CategoryLanguage.HINDI,

    "URDU" to CategoryLanguage.URDU, "PAKISTAN" to CategoryLanguage.URDU,

    "PERSIAN" to CategoryLanguage.PERSIAN, "IRAN" to CategoryLanguage.PERSIAN,
    "FARSI" to CategoryLanguage.PERSIAN,

    "KURDISH" to CategoryLanguage.KURDISH, "KURD" to CategoryLanguage.KURDISH,
    "KURDISTAN" to CategoryLanguage.KURDISH,

    "NORDIC" to CategoryLanguage.NORDIC, "NORDICS" to CategoryLanguage.NORDIC,
    "SCANDINAVIA" to CategoryLanguage.NORDIC, "SCANDINAVIAN" to CategoryLanguage.NORDIC,
    "SWEDEN" to CategoryLanguage.NORDIC, "SWEDISH" to CategoryLanguage.NORDIC,
    "NORWAY" to CategoryLanguage.NORDIC, "NORWEGIAN" to CategoryLanguage.NORDIC,
    "DENMARK" to CategoryLanguage.NORDIC, "DANISH" to CategoryLanguage.NORDIC,
    "FINLAND" to CategoryLanguage.NORDIC, "FINNISH" to CategoryLanguage.NORDIC,
    "ICELAND" to CategoryLanguage.NORDIC, "ICELANDIC" to CategoryLanguage.NORDIC,

    "AFRICA" to CategoryLanguage.AFRICAN, "AFRICAN" to CategoryLanguage.AFRICAN,
    "NIGERIA" to CategoryLanguage.AFRICAN, "NIGERIAN" to CategoryLanguage.AFRICAN,
    "GHANA" to CategoryLanguage.AFRICAN, "KENYA" to CategoryLanguage.AFRICAN,

    "YUGOSLAV" to CategoryLanguage.EXYU, "YUGOSLAVIA" to CategoryLanguage.EXYU,
    "SERBIAN" to CategoryLanguage.EXYU, "SERBIA" to CategoryLanguage.EXYU,
    "SRBIJA" to CategoryLanguage.EXYU, "CROATIAN" to CategoryLanguage.EXYU,
    "CROATIA" to CategoryLanguage.EXYU, "HRVATSKA" to CategoryLanguage.EXYU,
    "BOSNIAN" to CategoryLanguage.EXYU, "BOSNIA" to CategoryLanguage.EXYU,
    "SLOVENIAN" to CategoryLanguage.EXYU, "SLOVENIA" to CategoryLanguage.EXYU,
    "MACEDONIA" to CategoryLanguage.EXYU, "MONTENEGRO" to CategoryLanguage.EXYU,
)

private val CODES: Map<String, CategoryLanguage> = mapOf(
    "UK" to CategoryLanguage.ENGLISH, "GB" to CategoryLanguage.ENGLISH,
    "US" to CategoryLanguage.ENGLISH, "CA" to CategoryLanguage.ENGLISH,
    "AU" to CategoryLanguage.ENGLISH, "IE" to CategoryLanguage.ENGLISH,
    "NZ" to CategoryLanguage.ENGLISH, "EN" to CategoryLanguage.ENGLISH,

    "AR" to CategoryLanguage.ARABIC, "AE" to CategoryLanguage.ARABIC,
    "SA" to CategoryLanguage.ARABIC, "EG" to CategoryLanguage.ARABIC,
    "MA" to CategoryLanguage.ARABIC, "DZ" to CategoryLanguage.ARABIC,
    "TN" to CategoryLanguage.ARABIC, "IQ" to CategoryLanguage.ARABIC,
    "JO" to CategoryLanguage.ARABIC, "LB" to CategoryLanguage.ARABIC,
    "SY" to CategoryLanguage.ARABIC, "YE" to CategoryLanguage.ARABIC,
    "LY" to CategoryLanguage.ARABIC, "BH" to CategoryLanguage.ARABIC,
    "QA" to CategoryLanguage.ARABIC, "KW" to CategoryLanguage.ARABIC,
    "OM" to CategoryLanguage.ARABIC,

    "FR" to CategoryLanguage.FRENCH,
    "DE" to CategoryLanguage.GERMAN, "AT" to CategoryLanguage.GERMAN,
    "CH" to CategoryLanguage.GERMAN,

    "ES" to CategoryLanguage.SPANISH, "MX" to CategoryLanguage.SPANISH,
    "CL" to CategoryLanguage.SPANISH, "CO" to CategoryLanguage.SPANISH,
    "PE" to CategoryLanguage.SPANISH,

    "IT" to CategoryLanguage.ITALIAN,
    "PT" to CategoryLanguage.PORTUGUESE, "BR" to CategoryLanguage.PORTUGUESE,
    "NL" to CategoryLanguage.DUTCH,
    "PL" to CategoryLanguage.POLISH,
    "TR" to CategoryLanguage.TURKISH,
    "RU" to CategoryLanguage.RUSSIAN,
    "GR" to CategoryLanguage.GREEK,
    "RO" to CategoryLanguage.ROMANIAN,
    "AL" to CategoryLanguage.ALBANIAN,
    "IN" to CategoryLanguage.HINDI,
    "PK" to CategoryLanguage.URDU,
    "IR" to CategoryLanguage.PERSIAN,
    "KU" to CategoryLanguage.KURDISH,

    "SE" to CategoryLanguage.NORDIC, "NO" to CategoryLanguage.NORDIC,
    "DK" to CategoryLanguage.NORDIC, "FI" to CategoryLanguage.NORDIC,
    "IS" to CategoryLanguage.NORDIC,

    "ZA" to CategoryLanguage.AFRICAN, "NG" to CategoryLanguage.AFRICAN,
    "GH" to CategoryLanguage.AFRICAN, "KE" to CategoryLanguage.AFRICAN,

    "RS" to CategoryLanguage.EXYU, "HR" to CategoryLanguage.EXYU,
    "BA" to CategoryLanguage.EXYU, "SI" to CategoryLanguage.EXYU,
    "MK" to CategoryLanguage.EXYU,
)
