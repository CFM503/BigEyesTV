package com.bigeyes.tv.player.model

import java.io.Serializable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe Episode Queue managing playlist sequences, cursor position,
 * boundary detection, and dynamic stream URL updates.
 */
class EpisodeQueue(
    initialEpisodes: List<Episode> = emptyList(),
    initialIndex: Int = 0
) : Serializable {

    private val _episodes = CopyOnWriteArrayList<Episode>(initialEpisodes)

    @Volatile
    private var _currentIndex: Int = initialIndex.coerceIn(0, (initialEpisodes.size - 1).coerceAtLeast(0))

    val episodes: List<Episode>
        get() = _episodes.toList()

    val currentIndex: Int
        get() = _currentIndex

    val size: Int
        get() = _episodes.size

    val isEmpty: Boolean
        get() = _episodes.isEmpty()

    fun current(): Episode? {
        val index = _currentIndex
        return if (index in _episodes.indices) _episodes[index] else null
    }

    fun next(): Episode? {
        val nextIndex = _currentIndex + 1
        return if (nextIndex in _episodes.indices) {
            _currentIndex = nextIndex
            _episodes[nextIndex]
        } else {
            null
        }
    }

    fun previous(): Episode? {
        val prevIndex = _currentIndex - 1
        return if (prevIndex in _episodes.indices) {
            _currentIndex = prevIndex
            _episodes[prevIndex]
        } else {
            null
        }
    }

    fun peekNext(): Episode? {
        val nextIndex = _currentIndex + 1
        return if (nextIndex in _episodes.indices) _episodes[nextIndex] else null
    }

    fun peekPrevious(): Episode? {
        val prevIndex = _currentIndex - 1
        return if (prevIndex in _episodes.indices) _episodes[prevIndex] else null
    }

    fun hasNext(): Boolean {
        return _currentIndex + 1 < _episodes.size
    }

    fun hasPrevious(): Boolean {
        return _currentIndex > 0 && _episodes.isNotEmpty()
    }

    fun isFirst(): Boolean {
        return _currentIndex == 0
    }

    fun isLast(): Boolean {
        return _episodes.isEmpty() || _currentIndex >= _episodes.size - 1
    }

    fun setCurrentIndex(index: Int): Boolean {
        return if (index in _episodes.indices) {
            _currentIndex = index
            true
        } else {
            false
        }
    }

    fun getEpisode(index: Int): Episode? {
        return if (index in _episodes.indices) _episodes[index] else null
    }

    /**
     * Update episode stream URL dynamically.
     * Essential for handling expired temporary/ephemeral CDN tokens.
     */
    fun updateEpisodeUrl(index: Int, newUrl: String): Boolean {
        if (index in _episodes.indices) {
            val old = _episodes[index]
            _episodes[index] = old.copy(playUrl = newUrl)
            return true
        }
        return false
    }

    /**
     * Replace the current playlist and reset or set cursor.
     */
    fun setQueue(newEpisodes: List<Episode>, startIndex: Int = 0) {
        _episodes.clear()
        _episodes.addAll(newEpisodes)
        _currentIndex = startIndex.coerceIn(0, (newEpisodes.size - 1).coerceAtLeast(0))
    }

    fun clear() {
        _episodes.clear()
        _currentIndex = 0
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * Create a single-episode queue for backward compatibility with plain URL playback.
         * In single mode, hasNext() is guaranteed to be false.
         */
        fun single(episode: Episode): EpisodeQueue {
            return EpisodeQueue(listOf(episode), 0)
        }

        fun single(url: String, title: String? = null): EpisodeQueue {
            return EpisodeQueue(listOf(Episode.createSingle(url, title)), 0)
        }
    }
}
