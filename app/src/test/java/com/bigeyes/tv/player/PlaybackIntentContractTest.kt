package com.bigeyes.tv.player

import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.model.Episode
import org.json.JSONArray
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
}
