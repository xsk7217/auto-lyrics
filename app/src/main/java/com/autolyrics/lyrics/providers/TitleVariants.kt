package com.autolyrics.lyrics.providers

import com.autolyrics.model.TrackInfo

/**
 * Fallback guesses for uploaded videos whose title holds more than the song
 * name, e.g. "薛之謙-天外之物" or
 * "不遺憾 (《妳的婚禮》電影主題曲) - 李榮浩『如果來生還可能繼續…』".
 * Only used after the normal lookup found nothing; every guess still has to
 * pass TrackMatcher, so a wrong split simply finds no match.
 */
internal object TitleVariants {

    private val BRACKETED = Regex(
        """\([^()]*\)|（[^（）]*）|\[[^\[\]]*]|【[^【】]*】|「[^「」]*」|『[^『』]*』|《[^《》]*》|〈[^〈〉]*〉"""
    )
    /** An opening bracket that is never closed (the title was cut off). */
    private val UNCLOSED = Regex("""[(（\[【「『《〈][^)）\]】」』》〉]*$""")
    private val JUNK = Regex(
        """官方(?:完整)?(?:版)?\s*(?:MV|M/V|音樂錄影帶|音乐录影带)?|動態歌詞|动态歌词|歌詞版|歌词版|高音質|高音质|完整版|""" +
            """\bOfficial\s*(?:Music\s*)?(?:Video|Audio|MV)\b|\bLyrics?\s*Video\b|\bLyrics?\b|\bM/?V\b|\bHD\b|\b4K\b""",
        RegexOption.IGNORE_CASE
    )
    /** Brackets that usually hold the song name in video titles (not plain parentheses). */
    private val SONG_BRACKETS = Regex("""【([^【】]*)】|「([^「」]*)」|『([^『』]*)』|《([^《》]*)》""")
    private val LATIN_WORD = Regex("""[A-Za-z][A-Za-z0-9'’.&]*""")
    private const val MAX_VARIANTS = 4
    private val SEPARATOR = Regex("""\s*[-–—－|｜]\s*""")
    private val SPACES = Regex("""\s{2,}""")
    private val EDGE = Regex("""^[\s\-–—－|｜:：,，·•]+|[\s\-–—－|｜:：,，·•]+$""")

    fun of(track: TrackInfo): List<TrackInfo> {
        val cleaned = clean(track.title)
        val guesses = mutableListOf<Pair<String, String>>()   // (title, artist)
        val parts = split(cleaned)
        if (parts != null) {
            val (left, right) = parts
            guesses += right to left   // "Artist - Title"
            guesses += left to right   // "Title - Artist"
        } else {
            // "周杰倫 Jay Chou【告白氣球 Love Confession】Official MV": the song name is
            // in the brackets, the rest is the artist.
            val outside = strip(track.title).ifBlank { track.artist }
            for (m in SONG_BRACKETS.findAll(track.title)) {
                val raw = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                val inner = EDGE.replace(JUNK.replace(raw, " ").replace(SPACES, " ").trim(), "")
                if (inner.isNotEmpty()) guesses += inner to outside
            }
            if (cleaned.isNotEmpty()) guesses += cleaned to track.artist
        }

        val out = LinkedHashSet<TrackInfo>()
        for ((title, artist) in guesses) {
            out += track.copy(title = title, artist = artist)
            // "告白氣球 Love Confession" never matches "告白氣球"; also try Chinese only.
            out += track.copy(title = cjkOnly(title), artist = cjkOnly(artist))
        }
        out.remove(track)
        return out.take(MAX_VARIANTS)
    }

    /** [strip], or the original title when nothing is left (e.g. "《稻香》"). */
    fun clean(raw: String): String = strip(raw).ifBlank { raw.trim() }

    /** Removes bracketed parts, a cut-off bracket at the end and video junk words. */
    private fun strip(raw: String): String {
        var s = raw
        while (true) {
            val next = BRACKETED.replace(s, " ")
            if (next == s) break
            s = next
        }
        s = UNCLOSED.replace(s, " ")
        s = JUNK.replace(s, " ")
        return EDGE.replace(SPACES.replace(s, " ").trim(), "")
    }

    /** "周杰倫 Jay Chou" → "周杰倫"; text without both CJK and Latin is returned as is. */
    fun cjkOnly(s: String): String {
        if (!s.any { isCjk(it) } || !LATIN_WORD.containsMatchIn(s)) return s
        val reduced = EDGE.replace(SPACES.replace(LATIN_WORD.replace(s, " "), " ").trim(), "")
        return reduced.ifBlank { s }
    }

    /**
     * Splits at the first dash/bar that has spaces on both sides or touches a
     * CJK character, so "Anti-Hero" stays whole.
     */
    fun split(s: String): Pair<String, String>? {
        for (m in SEPARATOR.findAll(s)) {
            val spaced = m.value.first().isWhitespace() && m.value.last().isWhitespace()
            val before = s.getOrNull(m.range.first - 1)
            val after = s.getOrNull(m.range.last + 1)
            if (!spaced && !isCjk(before) && !isCjk(after)) continue
            val left = EDGE.replace(s.substring(0, m.range.first), "")
            val right = EDGE.replace(s.substring(m.range.last + 1), "")
            if (left.isNotEmpty() && right.isNotEmpty()) return left to right
        }
        return null
    }

    private fun isCjk(c: Char?): Boolean {
        if (c == null) return false
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }
}
