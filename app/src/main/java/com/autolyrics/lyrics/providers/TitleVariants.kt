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
    private val SEPARATOR = Regex("""\s*[-–—－|｜]\s*""")
    private val SPACES = Regex("""\s{2,}""")
    private val EDGE = Regex("""^[\s\-–—－|｜:：,，·•]+|[\s\-–—－|｜:：,，·•]+$""")

    fun of(track: TrackInfo): List<TrackInfo> {
        val cleaned = clean(track.title)
        val out = LinkedHashSet<TrackInfo>()
        val parts = split(cleaned)
        if (parts != null) {
            val (left, right) = parts
            out += track.copy(title = right, artist = left)   // "Artist - Title"
            out += track.copy(title = left, artist = right)   // "Title - Artist"
        } else if (cleaned.isNotEmpty()) {
            out += track.copy(title = cleaned)
        }
        out.remove(track)
        return out.toList()
    }

    fun clean(raw: String): String {
        var s = raw
        while (true) {
            val next = BRACKETED.replace(s, " ")
            if (next == s) break
            s = next
        }
        s = UNCLOSED.replace(s, " ")
        s = JUNK.replace(s, " ")
        s = EDGE.replace(SPACES.replace(s, " ").trim(), "")
        // Nothing left (the whole title was in brackets) → keep the original.
        return s.ifBlank { raw.trim() }
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
