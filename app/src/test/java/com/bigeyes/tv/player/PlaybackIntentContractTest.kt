package com.bigeyes.tv.player

import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.model.Episode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackIntentContractTest {

    @Test
    fun testParseEpisodesJsonWithObjectArray() {
        val ep1 = Episode(
            seriesId = "s1",
            seriesTitle = "白夜追凶",
            episodeNumber = 1,
            episodeIndex = 0,
            playUrl = "http://test.com/s1e1.m3u8"
        )
        val ep2 = Episode(
            seriesId = "s1",
            seriesTitle = "白夜追凶",
            episodeNumber = 2,
            episodeIndex = 1,
            playUrl = "http://test.com/s1e2.m3u8"
        )

        val array = JSONArray().apply {
            put(ep1.toJson())
            put(ep2.toJson())
        }

        val parsed = PlaybackIntentContract.parseEpisodesJson(array.toString())

        assertEquals(2, parsed.size)
        assertEquals("s1", parsed[0].seriesId)
        assertEquals("白夜追凶", parsed[0].seriesTitle)
        assertEquals(1, parsed[0].episodeNumber)
        assertEquals("http://test.com/s1e1.m3u8", parsed[0].playUrl)

        assertEquals("s1", parsed[1].seriesId)
        assertEquals(2, parsed[1].episodeNumber)
        assertEquals("http://test.com/s1e2.m3u8", parsed[1].playUrl)
    }

    @Test
    fun testParseEpisodesJsonWithStringArray() {
        val urls = JSONArray().apply {
            put("http://cdn.example.com/live1.m3u8")
            put("http://cdn.example.com/live2.m3u8")
            put("http://cdn.example.com/live3.m3u8")
        }

        val parsed = PlaybackIntentContract.parseEpisodesJson(urls.toString())

        assertEquals(3, parsed.size)
        assertEquals("http://cdn.example.com/live1.m3u8", parsed[0].playUrl)
        assertEquals(1, parsed[0].episodeNumber)
        assertEquals(0, parsed[0].episodeIndex)

        assertEquals("http://cdn.example.com/live2.m3u8", parsed[1].playUrl)
        assertEquals(2, parsed[1].episodeNumber)
        assertEquals(1, parsed[1].episodeIndex)

        assertEquals("http://cdn.example.com/live3.m3u8", parsed[2].playUrl)
        assertEquals(3, parsed[2].episodeNumber)
        assertEquals(2, parsed[2].episodeIndex)
    }

    @Test
    fun testParseEpisodesJsonWithInvalidJsonReturnsEmptyListSafely() {
        val emptyList = PlaybackIntentContract.parseEpisodesJson("not a valid json {{{{")
        assertNotNull(emptyList)
        assertTrue(emptyList.isEmpty())
    }

    @Test
    fun testResolvePositionPrefersCanonicalKey() {
        assertEquals(65_000L, PlaybackIntentContract.resolvePositionMs(65_000L, 123L))
    }

    @Test
    fun testResolvePositionFallsBackToAliasKey() {
        // Older phone builds only send extra_position_ms for ACTION_PLAY / ACTION_SEEK
        assertEquals(
            45_000L,
            PlaybackIntentContract.resolvePositionMs(Long.MIN_VALUE, 45_000L)
        )
    }

    @Test
    fun testResolvePositionNeverReturnsNegative() {
        assertEquals(0L, PlaybackIntentContract.resolvePositionMs(-500L, 0L))
        assertEquals(0L, PlaybackIntentContract.resolvePositionMs(Long.MIN_VALUE, -1L))
    }

    @Test
    fun testBuildHeadersDropsBlankValues() {
        val headers = PlaybackIntentContract.buildHeaders(
            referer = "https://example.com/watch",
            userAgent = "",
            cookie = null
        )

        assertEquals(1, headers.size)
        assertEquals("https://example.com/watch", headers["Referer"])
        assertNull(headers["User-Agent"])
        assertNull(headers["Cookie"])
    }

    @Test
    fun testEpisodeHeadersSurviveJsonRoundTrip() {
        val episode = Episode(
            seriesId = "s1",
            seriesTitle = "白夜追凶",
            episodeNumber = 3,
            episodeIndex = 2,
            playUrl = "http://cdn.example.com/s1e3.m3u8",
            headers = mapOf(
                "Referer" to "https://example.com/watch",
                "User-Agent" to "BigEyes/1.0",
                "Cookie" to "uid=42"
            )
        )

        val parsed = Episode.fromJson(JSONObject(episode.toJson().toString()))

        assertEquals(3, parsed.headers.size)
        assertEquals("https://example.com/watch", parsed.headers["Referer"])
        assertEquals("BigEyes/1.0", parsed.headers["User-Agent"])
        assertEquals("uid=42", parsed.headers["Cookie"])
        assertEquals("http://cdn.example.com/s1e3.m3u8", parsed.playUrl)
    }

    @Test
    fun testEpisodeWithoutHeadersKeepsEmptyHeaderMap() {
        val parsed = Episode.fromJson(JSONObject(Episode.createSingle("http://a.com/v.m3u8").toJson().toString()))
        assertTrue(parsed.headers.isEmpty())
    }

    @Test
    fun testQueueJsonWithHeadersIsParsedWithHeaders() {
        val episode = Episode(
            seriesId = "s1",
            seriesTitle = "剧集",
            episodeNumber = 1,
            episodeIndex = 0,
            playUrl = "http://cdn.example.com/e1.m3u8",
            headers = mapOf("Referer" to "https://ref.example.com/")
        )
        val array = JSONArray().put(episode.toJson())

        val parsed = PlaybackIntentContract.parseEpisodesJson(array.toString())

        assertEquals(1, parsed.size)
        assertEquals("https://ref.example.com/", parsed[0].headers["Referer"])
    }
}
