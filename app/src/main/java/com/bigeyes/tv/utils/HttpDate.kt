package com.bigeyes.tv.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RFC 7231 HTTP-date formatter used by SSDP `DATE:` and GENA subscription headers.
 * Thread safe: a fresh formatter is created for every call.
 */
object HttpDate {

    private const val PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz"

    fun now(date: Date = Date()): String {
        val format = SimpleDateFormat(PATTERN, Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        format.isLenient = false
        return format.format(date)
    }
}
