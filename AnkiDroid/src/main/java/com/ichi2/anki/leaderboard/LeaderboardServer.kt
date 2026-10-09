// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import org.json.JSONArray
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** HTTP to the add-on's server. Blocking: call from Dispatchers.IO. */
internal object LeaderboardServer {
    private const val API = "https://shigeyuki.pythonanywhere.com/api/v2/"

    /** The add-on version this client behaves like. */
    const val ADDON_VERSION = "v5.0 (Fixed by Shige)"

    /** Posts a form; the reply's text, or an IOException with the server's message. */
    fun post(
        endpoint: String,
        form: Map<String, String>,
    ): String {
        val (code, text) = request(endpoint, form)
        if (code !in 200..299) throw IOException(text.take(300).ifBlank { "Leaderboard server error $code" })
        return text
    }

    /** The current league season; null if the server does not say. */
    fun fetchSeason(): Season? =
        try {
            val reply = JSONArray(request("season/", null).second)

            fun time(index: Int): LocalDateTime {
                val parts = reply.getJSONArray(index)
                return LocalDateTime.of(
                    parts.getInt(0),
                    parts.getInt(1),
                    parts.getInt(2),
                    parts.getInt(3),
                    parts.getInt(4),
                    parts.getInt(5),
                )
            }
            Season(time(0), time(1))
        } catch (e: Exception) {
            Timber.w(e, "leaderboard season unavailable")
            null
        }

    private fun request(
        endpoint: String,
        form: Map<String, String>?,
    ): Pair<Int, String> {
        val connection = URL(API + endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body =
                    form.entries.joinToString("&") { (key, value) ->
                        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
                    }
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to decode(stream?.use { it.readCapped() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The server compresses the board (seen: a zlib stream, which the desktop's `requests` unpacks
     * by itself and HttpURLConnection does not). Unpack by what the bytes are, not by the headers.
     */
    private fun decode(bytes: ByteArray): String {
        fun byte(index: Int) = bytes[index].toInt() and 0xff
        val unpacked =
            try {
                when {
                    bytes.size > 2 && byte(0) == 0x1f && byte(1) == 0x8b ->
                        GZIPInputStream(bytes.inputStream()).use { it.readCapped() }
                    // zlib header: deflate method, and the two bytes are a multiple of 31
                    bytes.size > 2 && (byte(0) and 0x0f) == 8 && (byte(0) * 256 + byte(1)) % 31 == 0 ->
                        InflaterInputStream(bytes.inputStream()).use { it.readCapped() }
                    else -> bytes
                }
            } catch (e: IOException) {
                bytes
            }
        return unpacked.toString(Charsets.UTF_8)
    }

    /** The whole board is ~4 MB; anything far bigger is refused instead of filling the memory. */
    private const val MAX_REPLY = 32 shl 20

    private fun InputStream.readCapped(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            // IllegalStateException, not IOException: decode must not take it for "not compressed"
            if (out.size() > MAX_REPLY) error("leaderboard reply over $MAX_REPLY bytes")
        }
    }
}

/** A number as the add-on sends it: one decimal, a dot. */
internal fun decimal(value: Double) = String.format(Locale.US, "%.1f", value)

/** Python's `str(datetime.now())`. */
internal fun timestamp(time: LocalDateTime) =
    time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + String.format(Locale.US, ".%06d", time.nano / 1000)

/** Reads the server's sync times, written as by [timestamp]. */
internal val TIMESTAMP_PARSER: DateTimeFormatter =
    DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .toFormatter()
