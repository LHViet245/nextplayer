package dev.anilbeesetti.nextplayer.core.common.subtitles

/**
 * A file beside the video that can be played as a subtitle track.
 *
 * [languageCode] is an ISO 639-2/T code, or null when the filename names no language. That form is
 * deliberate: it is what the preferred-subtitle-language setting stores, and Media3 scores a text
 * track whose language equals the preference exactly, whereas a two-letter code would only reach it
 * through a prefix coincidence that misses region forms and seven whole languages.
 */
data class SubtitleMatch(val languageCode: String?)

/**
 * Recognises subtitle files that sit beside a video and name the video they belong to.
 *
 * A sibling matches when the first tokens of its stem spell the video's own stem and the tokens
 * after that either stop there — `Movie.srt` — or name a language: `Movie.vi.srt`,
 * `Movie.vi.HDTV.srt`, `Movie.pt-BR.srt`. A trailing token that is no language leaves the file
 * unmatched, so `Movie.720p.srt` is not a subtitle.
 *
 * Everything here is pure: no Android type, no I/O, no clock.
 */
object SubtitleSiblings {

    private const val REGION_SEPARATOR = '-'

    private val SUPPORTED_EXTENSIONS = setOf("srt", "ssa", "ass", "vtt", "ttml")

    /** ISO 639-1, in full. Recognition is deliberately a fixed table, not the device's locales. */
    private val ISO_639_1 = setOf(
        "aa", "ab", "ae", "af", "ak", "am", "an", "ar", "as", "av", "ay", "az", "ba", "be", "bg",
        "bh", "bi", "bm", "bn", "bo", "br", "bs", "ca", "ce", "ch", "co", "cr", "cs", "cu", "cv",
        "cy", "da", "de", "dv", "dz", "ee", "el", "en", "eo", "es", "et", "eu", "fa", "ff", "fi",
        "fj", "fo", "fr", "fy", "ga", "gd", "gl", "gn", "gu", "gv", "ha", "he", "hi", "ho", "hr",
        "ht", "hu", "hy", "hz", "ia", "id", "ie", "ig", "ii", "ik", "io", "is", "it", "iu", "ja",
        "jv", "ka", "kg", "ki", "kj", "kk", "kl", "km", "kn", "ko", "kr", "ks", "ku", "kv", "kw",
        "ky", "la", "lb", "lg", "li", "ln", "lo", "lt", "lu", "lv", "mg", "mh", "mi", "mk", "ml",
        "mn", "mr", "ms", "mt", "my", "na", "nb", "nd", "ne", "ng", "nl", "nn", "no", "nr", "nv",
        "ny", "oc", "oj", "om", "or", "os", "pa", "pi", "pl", "ps", "pt", "qu", "rm", "rn", "ro",
        "ru", "rw", "sa", "sc", "sd", "se", "sg", "si", "sk", "sl", "sm", "sn", "so", "sq", "sr",
        "ss", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "ti", "tk", "tl", "tn", "to", "tr",
        "ts", "tt", "tw", "ty", "ug", "uk", "ur", "uz", "ve", "vi", "vo", "wa", "wo", "xh", "yi",
        "yo", "za", "zh", "zu",
    )

    /**
     * ISO 639-1 to ISO 639-2/T, in full and fixed.
     *
     * Recognition must not depend on the device's locale data — `Movie.vi.srt` has to be a sibling
     * on every device — so this table is written out rather than asked of the platform at runtime.
     * It is the JDK 17 mapping of each ISO 639-1 code to its ISO 639-2/T code; all 184 codes
     * resolve there.
     */
    private val ISO_639_1_TO_639_2_T: Map<String, String> = mapOf(
        "aa" to "aar",
        "ab" to "abk",
        "ae" to "ave",
        "af" to "afr",
        "ak" to "aka",
        "am" to "amh",
        "an" to "arg",
        "ar" to "ara",
        "as" to "asm",
        "av" to "ava",
        "ay" to "aym",
        "az" to "aze",
        "ba" to "bak",
        "be" to "bel",
        "bg" to "bul",
        "bh" to "bih",
        "bi" to "bis",
        "bm" to "bam",
        "bn" to "ben",
        "bo" to "bod",
        "br" to "bre",
        "bs" to "bos",
        "ca" to "cat",
        "ce" to "che",
        "ch" to "cha",
        "co" to "cos",
        "cr" to "cre",
        "cs" to "ces",
        "cu" to "chu",
        "cv" to "chv",
        "cy" to "cym",
        "da" to "dan",
        "de" to "deu",
        "dv" to "div",
        "dz" to "dzo",
        "ee" to "ewe",
        "el" to "ell",
        "en" to "eng",
        "eo" to "epo",
        "es" to "spa",
        "et" to "est",
        "eu" to "eus",
        "fa" to "fas",
        "ff" to "ful",
        "fi" to "fin",
        "fj" to "fij",
        "fo" to "fao",
        "fr" to "fra",
        "fy" to "fry",
        "ga" to "gle",
        "gd" to "gla",
        "gl" to "glg",
        "gn" to "grn",
        "gu" to "guj",
        "gv" to "glv",
        "ha" to "hau",
        "he" to "heb",
        "hi" to "hin",
        "ho" to "hmo",
        "hr" to "hrv",
        "ht" to "hat",
        "hu" to "hun",
        "hy" to "hye",
        "hz" to "her",
        "ia" to "ina",
        "id" to "ind",
        "ie" to "ile",
        "ig" to "ibo",
        "ii" to "iii",
        "ik" to "ipk",
        "io" to "ido",
        "is" to "isl",
        "it" to "ita",
        "iu" to "iku",
        "ja" to "jpn",
        "jv" to "jav",
        "ka" to "kat",
        "kg" to "kon",
        "ki" to "kik",
        "kj" to "kua",
        "kk" to "kaz",
        "kl" to "kal",
        "km" to "khm",
        "kn" to "kan",
        "ko" to "kor",
        "kr" to "kau",
        "ks" to "kas",
        "ku" to "kur",
        "kv" to "kom",
        "kw" to "cor",
        "ky" to "kir",
        "la" to "lat",
        "lb" to "ltz",
        "lg" to "lug",
        "li" to "lim",
        "ln" to "lin",
        "lo" to "lao",
        "lt" to "lit",
        "lu" to "lub",
        "lv" to "lav",
        "mg" to "mlg",
        "mh" to "mah",
        "mi" to "mri",
        "mk" to "mkd",
        "ml" to "mal",
        "mn" to "mon",
        "mr" to "mar",
        "ms" to "msa",
        "mt" to "mlt",
        "my" to "mya",
        "na" to "nau",
        "nb" to "nob",
        "nd" to "nde",
        "ne" to "nep",
        "ng" to "ndo",
        "nl" to "nld",
        "nn" to "nno",
        "no" to "nor",
        "nr" to "nbl",
        "nv" to "nav",
        "ny" to "nya",
        "oc" to "oci",
        "oj" to "oji",
        "om" to "orm",
        "or" to "ori",
        "os" to "oss",
        "pa" to "pan",
        "pi" to "pli",
        "pl" to "pol",
        "ps" to "pus",
        "pt" to "por",
        "qu" to "que",
        "rm" to "roh",
        "rn" to "run",
        "ro" to "ron",
        "ru" to "rus",
        "rw" to "kin",
        "sa" to "san",
        "sc" to "srd",
        "sd" to "snd",
        "se" to "sme",
        "sg" to "sag",
        "si" to "sin",
        "sk" to "slk",
        "sl" to "slv",
        "sm" to "smo",
        "sn" to "sna",
        "so" to "som",
        "sq" to "sqi",
        "sr" to "srp",
        "ss" to "ssw",
        "st" to "sot",
        "su" to "sun",
        "sv" to "swe",
        "sw" to "swa",
        "ta" to "tam",
        "te" to "tel",
        "tg" to "tgk",
        "th" to "tha",
        "ti" to "tir",
        "tk" to "tuk",
        "tl" to "tgl",
        "tn" to "tsn",
        "to" to "ton",
        "tr" to "tur",
        "ts" to "tso",
        "tt" to "tat",
        "tw" to "twi",
        "ty" to "tah",
        "ug" to "uig",
        "uk" to "ukr",
        "ur" to "urd",
        "uz" to "uzb",
        "ve" to "ven",
        "vi" to "vie",
        "vo" to "vol",
        "wa" to "wln",
        "wo" to "wol",
        "xh" to "xho",
        "yi" to "yid",
        "yo" to "yor",
        "za" to "zha",
        "zh" to "zho",
        "zu" to "zul",
    )

    /** ISO 639-2/T, derived from [ISO_639_1_TO_639_2_T] so the two tables can never disagree. */
    private val ISO_639_2_T: Set<String> = ISO_639_1_TO_639_2_T.values.toSet()

    /** The 639-2/B forms that still appear in filenames, mapped to their 639-2/T equivalents. */
    private val ISO_639_2_B_TO_T = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
        "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
        "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
        "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )

    /**
     * Two-letter codes that are also ordinary English words.
     *
     * These name a language only when they are the whole of the trailing text: `Movie.no.srt` is
     * Norwegian, while `Movie.no.HDTV.srt` is a file whose name we cannot read a language from.
     */
    private val AMBIGUOUS_TWO_LETTER = setOf(
        "am", "an", "as", "be", "he", "hi", "id", "is", "it", "la",
        "mi", "my", "ne", "no", "or", "pi", "so", "ti", "to",
    )

    /**
     * Returns the language of [siblingFileName] when it is a subtitle beside [videoFileName], or
     * null when it is not a sibling at all. A sibling that names no language matches with a null
     * [SubtitleMatch.languageCode].
     */
    fun match(videoFileName: String, siblingFileName: String): SubtitleMatch? {
        val extension = siblingFileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.lowercase() !in SUPPORTED_EXTENSIONS) return null

        val videoStem = videoFileName.substringBeforeLast('.', missingDelimiterValue = videoFileName)
        val siblingStem = siblingFileName.substringBeforeLast('.', missingDelimiterValue = "")
        if (siblingStem.isEmpty()) return null

        val videoTokens = videoStem.split('.')
        val siblingTokens = siblingStem.split('.')
        if (siblingTokens.size < videoTokens.size) return null
        val prefix = siblingTokens.take(videoTokens.size).joinToString(".")
        if (!prefix.equals(videoStem, ignoreCase = true)) return null

        val trailing = siblingTokens.drop(videoTokens.size)
        if (trailing.isEmpty()) return SubtitleMatch(null)

        return SubtitleMatch(mostSpecificLanguageTag(trailing) ?: return null)
    }

    /**
     * The most specific language tag among [tokens], as an ISO 639-2/T code, or null when none of
     * them names a language. A region or script form beats a three-letter code, which beats a
     * two-letter one; ties keep token order.
     */
    private fun mostSpecificLanguageTag(tokens: List<String>): String? {
        var best: Pair<Int, String>? = null
        tokens.forEach { token ->
            val candidate = languageTagOf(token, isSoleTrailingToken = tokens.size == 1)
            if (candidate != null && (best == null || candidate.first > best.first)) best = candidate
        }
        return best?.second
    }

    private fun languageTagOf(token: String, isSoleTrailingToken: Boolean): Pair<Int, String>? {
        val lowercased = token.lowercase()
        val base = lowercased.substringBefore(REGION_SEPARATOR)

        return when {
            REGION_SEPARATOR in lowercased && base.isKnownCode() -> toIso6392T(base)?.let { 3 to it }

            lowercased.length == 3 -> toIso6392T(lowercased)?.let { 2 to it }

            lowercased.length == 2 && lowercased in ISO_639_1 -> {
                if (lowercased in AMBIGUOUS_TWO_LETTER && !isSoleTrailingToken) {
                    null
                } else {
                    toIso6392T(lowercased)?.let { 1 to it }
                }
            }

            else -> null
        }
    }

    private fun String.isKnownCode(): Boolean = toIso6392T(this) != null

    /** The ISO 639-2/T code for a recognised code or bare language subtag. */
    private fun toIso6392T(code: String): String? {
        ISO_639_2_B_TO_T[code]?.let { return it }
        if (code.length == 3) return code.takeIf { it in ISO_639_2_T }
        if (code.length != 2 || code !in ISO_639_1) return null
        return ISO_639_1_TO_639_2_T[code]
    }
}
