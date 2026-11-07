package com.anthonyla.paperize.core.util

import com.anthonyla.paperize.domain.source.SourceFolder
import com.anthonyla.paperize.domain.source.SourceImage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GooglePhotosAlbumScraper {

    private val ALBUM_URL_REGEX = Regex(
        """^https?://(photos\.app\.goo\.gl/[\w-]+|photos\.google\.com/share/[\w-]+.*)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches direct Google Photos media base URLs embedded in the AF_initDataCallback payload
    private val PHOTO_BASE_URL_REGEX = Regex(
        """https://lh3\.googleusercontent\.com/(?:pw/)?[A-Za-z0-9_-]{20,}"""
    )

    private val TITLE_META_REGEX = Regex(
        """<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    fun isGooglePhotosUrl(uri: String): Boolean =
        ALBUM_URL_REGEX.matches(uri.trim())

    fun isRemoteUrl(uri: String): Boolean =
        uri.startsWith("https://", ignoreCase = true) || uri.startsWith("http://", ignoreCase = true)

    suspend fun scrapeAlbum(
        albumUrl: String,
        onProgress: (Int) -> Unit = {}
    ): SourceFolder = withContext(Dispatchers.IO) {
        val html = fetchHtmlFollowingRedirects(albumUrl.trim())
        val rawTitle = TITLE_META_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.replace("&#39;", "'")
            ?.replace("&quot;", "\"")
            ?.substringBefore(" – Google Photos")
            ?.substringBefore(" - Google Photos")
            ?.trim()
            .takeUnless { it.isNullOrBlank() } ?: "Google Photos Album"

        // All photos in the shared album appear inside AF_initDataCallback blocks.
        // The first occurrences of lh3.googleusercontent.com in the raw HTML can include the
        // album owner's profile avatar (usually short path without /pw/), so prefer /pw/ links
        // when present, or filter out tiny avatar tokens.
        val allMatches = PHOTO_BASE_URL_REGEX.findAll(html).map { it.value }.distinct().toList()
        val photoBaseUrls = allMatches.filter { it.contains("/pw/") }.ifEmpty { allMatches }

        val now = System.currentTimeMillis()
        val images = photoBaseUrls.mapIndexed { index, baseUrl ->
            val highResUrl = "$baseUrl=w2400-h2400"
            val shortId = baseUrl.takeLast(12)
            val image = SourceImage(
                uri = highResUrl,
                name = "gphoto_$shortId.jpg",
                modifiedAt = now
            )
            onProgress(index + 1)
            image
        }

        if (images.isEmpty()) {
            throw IOException("No images found in Google Photos album link")
        }

        SourceFolder(name = rawTitle, images = images)
    }

    private fun fetchHtmlFollowingRedirects(urlString: String): String {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 6) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (status !in 200..299) {
                    throw IOException("HTTP $status fetching Google Photos album")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects for $urlString")
    }
}