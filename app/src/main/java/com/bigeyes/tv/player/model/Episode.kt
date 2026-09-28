package com.bigeyes.tv.player.model

import org.json.JSONObject
import java.io.Serializable

/**
 * Unified Episode data model representing a playable unit within a series or standalone media.
 */
data class Episode(
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val episodeTitle: String = "",
    val episodeIndex: Int = 0,
    val playUrl: String,
    val thumbnail: String? = null,
    val duration: Long = 0L,
    /**
     * Optional anti-hotlink request headers (Referer / User-Agent / Cookie) forwarded by the
     * BigEyes phone app. Applied by the player engine for every HTTP request of this episode.
     */
    val headers: Map<String, String> = emptyMap()
) : Serializable {

    fun toDisplayTitle(): String {
        val epNumStr = if (episodeNumber > 0) "第${episodeNumber}集" else ""
        return when {
            seriesTitle.isNotBlank() && epNumStr.isNotBlank() && episodeTitle.isNotBlank() ->
                "$seriesTitle $epNumStr - $episodeTitle"
            seriesTitle.isNotBlank() && epNumStr.isNotBlank() ->
                "$seriesTitle $epNumStr"
            seriesTitle.isNotBlank() && episodeTitle.isNotBlank() ->
                "$seriesTitle - $episodeTitle"
            episodeTitle.isNotBlank() -> episodeTitle
            seriesTitle.isNotBlank() -> seriesTitle
            epNumStr.isNotBlank() -> epNumStr
            else -> "视频播放"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("seriesId", seriesId)
            put("seriesTitle", seriesTitle)
            put("seasonNumber", seasonNumber)
            put("episodeNumber", episodeNumber)
            put("episodeTitle", episodeTitle)
            put("episodeIndex", episodeIndex)
            put("playUrl", playUrl)
            if (thumbnail != null) put("thumbnail", thumbnail)
            put("duration", duration)
            if (headers.isNotEmpty()) {
                val headerObj = JSONObject()
                headers.forEach { (key, value) -> headerObj.put(key, value) }
                put("headers", headerObj)
            }
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun fromJson(json: JSONObject): Episode {
            return Episode(
                seriesId = json.optString("seriesId", "single_series"),
                seriesTitle = json.optString("seriesTitle", ""),
                seasonNumber = json.optInt("seasonNumber", 1),
                episodeNumber = json.optInt("episodeNumber", 1),
                episodeTitle = json.optString("episodeTitle", ""),
                episodeIndex = json.optInt("episodeIndex", 0),
                playUrl = json.optString("playUrl", ""),
                thumbnail = json.optString("thumbnail", "").takeIf { it.isNotBlank() },
                duration = json.optLong("duration", 0L),
                headers = parseHeaders(json.optJSONObject("headers"))
            )
        }

        private fun parseHeaders(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val headers = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                if (value.isNotBlank()) headers[key] = value
            }
            return headers
        }

        fun createSingle(url: String, title: String? = null, headers: Map<String, String> = emptyMap()): Episode {
            val display = if (!title.isNullOrBlank()) title else "投屏流媒体"
            val id = "stream_" + (url.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
            return Episode(
                seriesId = id,
                seriesTitle = display,
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = display,
                episodeIndex = 0,
                playUrl = url,
                thumbnail = null,
                duration = 0L,
                headers = headers
            )
        }
    }
}
