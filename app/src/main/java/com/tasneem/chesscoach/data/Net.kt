package com.tasneem.chesscoach.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object Net {
    private const val UA = "ChessCoach/1.0 (personal training app; github.com/Tasneem-Qureshi/chess-coach)"

    fun get(url: String, accept: String = "application/json"): String {
        val c = open(url, accept)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpException(code, url)
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun download(url: String, to: File, onProgress: (Float) -> Unit) {
        val c = open(url, "application/octet-stream")
        try {
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode, url)
            val total = c.contentLengthLong
            val tmp = File(to.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            tmp.renameTo(to)
        } finally { c.disconnect() }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", accept)
        }
}

class HttpException(val code: Int, url: String) : Exception("HTTP $code for $url")
