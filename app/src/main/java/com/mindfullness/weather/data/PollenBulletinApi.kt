package com.mindfullness.weather.data

import com.mindfullness.weather.domain.PollenBulletin
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Downloads a city pollen bulletin's page, read by [BulletinParser]. Blocking. */
open class PollenBulletinApi {
    open fun page(bulletin: PollenBulletin): String {
        val connection = URI(bulletin.url).toURL().openConnection() as HttpURLConnection
        try {
            // An extra like the model comparison: it must not hold up the forecast for long.
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "HavaUyari/1.0 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // A page this large is not the bulletin (the real ones are about 55 kB).
            if (text.length > MAX_CHARS) throw IOException("page too large")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_CHARS = 1_000_000
    }
}
