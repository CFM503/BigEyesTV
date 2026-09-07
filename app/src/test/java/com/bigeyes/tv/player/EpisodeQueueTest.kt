package com.bigeyes.tv.player

import com.bigeyes.tv.player.model.Episode
import com.bigeyes.tv.player.model.EpisodeQueue
import org.junit.Assert.*
import org.junit.Test

class EpisodeQueueTest {

    @Test
    fun testSingleEpisodeQueue() {
        val queue = EpisodeQueue.single("http://example.com/single.m3u8", "单集电影")

        assertEquals(1, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals(0, queue.currentIndex)
        assertTrue(queue.isFirst())
        assertTrue(queue.isLast())
        assertFalse(queue.hasNext())
        assertFalse(queue.hasPrevious())

        val current = queue.current()
        assertNotNull(current)
        assertEquals("http://example.com/single.m3u8", current?.playUrl)
        assertEquals("单集电影", current?.seriesTitle)

        // Advancing in single mode should return null and maintain index
        assertNull(queue.next())
        assertEquals(0, queue.currentIndex)
        assertNull(queue.previous())
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun testThreeEpisodeQueueTraversal() {
        val episodes = (1..3).map { num ->
            Episode(
                seriesId = "series_100",
                seriesTitle = "仙剑奇侠传",
                seasonNumber = 1,
                episodeNumber = num,
                episodeTitle = "第 $num 集",
                episodeIndex = num - 1,
                playUrl = "http://example.com/ep$num.m3u8"
            )
        }
        val queue = EpisodeQueue(episodes, initialIndex = 0)

        assertEquals(3, queue.size)
        assertTrue(queue.isFirst())
        assertFalse(queue.isLast())
        assertTrue(queue.hasNext())
        assertFalse(queue.hasPrevious())

        // Step to Ep 2
        val ep2 = queue.next()
        assertNotNull(ep2)
        assertEquals(2, ep2?.episodeNumber)
        assertEquals(1, queue.currentIndex)
        assertFalse(queue.isFirst())
        assertFalse(queue.isLast())
        assertTrue(queue.hasNext())
        assertTrue(queue.hasPrevious())

        // Step to Ep 3 (last)
        val ep3 = queue.next()
        assertNotNull(ep3)
        assertEquals(3, ep3?.episodeNumber)
        assertEquals(2, queue.currentIndex)
        assertFalse(queue.isFirst())
        assertTrue(queue.isLast())
        assertFalse(queue.hasNext())
        assertTrue(queue.hasPrevious())

        // Advance beyond last returns null and stays on last
        assertNull(queue.next())
        assertEquals(2, queue.currentIndex)

        // Step back to Ep 2
        val backToEp2 = queue.previous()
        assertNotNull(backToEp2)
        assertEquals(2, backToEp2?.episodeNumber)
        assertEquals(1, queue.currentIndex)

        // Step back to Ep 1
        val backToEp1 = queue.previous()
        assertNotNull(backToEp1)
        assertEquals(1, backToEp1?.episodeNumber)
        assertEquals(0, queue.currentIndex)

        // Step before first returns null and stays on first
        assertNull(queue.previous())
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun testTenEpisodeQueueRandomAccessAndBoundary() {
        val episodes = (1..10).map { num ->
            Episode(
                seriesId = "series_ten",
                seriesTitle = "斗破苍穹",
                seasonNumber = 1,
                episodeNumber = num,
                episodeTitle = "第 $num 集",
                episodeIndex = num - 1,
                playUrl = "http://example.com/ep$num.m3u8"
            )
        }
        val queue = EpisodeQueue(episodes, initialIndex = 5)

        assertEquals(5, queue.currentIndex)
        assertEquals(6, queue.current()?.episodeNumber)

        assertTrue(queue.setCurrentIndex(9))
        assertEquals(9, queue.currentIndex)
        assertTrue(queue.isLast())
        assertFalse(queue.hasNext())

        // Out of bounds
        assertFalse(queue.setCurrentIndex(10))
        assertEquals(9, queue.currentIndex)
        assertFalse(queue.setCurrentIndex(-1))
        assertEquals(9, queue.currentIndex)
    }

    @Test
    fun testUpdateEpisodeUrlForExpiredTokens() {
        val ep = Episode(
            seriesId = "ephemeral_series",
            seriesTitle = "测试剧集",
            episodeNumber = 1,
            episodeIndex = 0,
            playUrl = "http://cdn.com/expired_token.m3u8"
        )
        val queue = EpisodeQueue(listOf(ep), 0)

        val freshUrl = "http://cdn.com/fresh_token_valid.m3u8"
        val updated = queue.updateEpisodeUrl(0, freshUrl)
        assertTrue(updated)
        assertEquals(freshUrl, queue.current()?.playUrl)
    }
}
