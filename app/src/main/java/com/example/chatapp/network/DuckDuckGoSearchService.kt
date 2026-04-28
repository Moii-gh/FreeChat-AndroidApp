package com.example.chatapp.network

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object DuckDuckGoSearchService {
    data class SearchResponse(
        val context: String,
        val sources: List<SearchSource>
    )

    data class SearchSource(
        val title: String,
        val url: String
    )

    private data class SearchResult(
        val title: String = "",
        val url: String = "",
        val snippet: String = ""
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun search(query: String, maxResults: Int = 5): String {
        return searchWithSources(query, maxResults).context
    }

    fun searchWithSources(query: String, maxResults: Int = 5): SearchResponse {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return SearchResponse(context = "Пустой запрос", sources = emptyList())
        }

        return try {
            val formBody = FormBody.Builder()
                .add("q", trimmedQuery)
                .build()
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                if (!response.isSuccessful || html.isBlank()) {
                    return SearchResponse(context = "Нет ответа от поисковика.", sources = emptyList())
                }

                val results = parseResults(html, maxResults)
                val sources = results
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
                    .map { result ->
                        SearchSource(
                            title = result.title.ifBlank { result.url },
                            url = result.url
                        )
                    }

                if (results.isEmpty()) {
                    SearchResponse(
                        context = "Нет результатов поиска для: $trimmedQuery",
                        sources = emptyList()
                    )
                } else {
                    SearchResponse(
                        context = "Результаты поиска DuckDuckGo для \"$trimmedQuery\":\n" +
                            results.joinToString("\n") { result ->
                                buildString {
                                    append("- ")
                                    if (result.title.isNotBlank()) append(result.title)
                                    if (result.url.isNotBlank()) append(" (").append(result.url).append(")")
                                    if (result.snippet.isNotBlank()) append(": ").append(result.snippet)
                                }
                            },
                        sources = sources
                    )
                }
            }
        } catch (e: Exception) {
            SearchResponse(context = "Ошибка при поиске: ${e.message}", sources = emptyList())
        }
    }

    fun trendingNews(maxResults: Int = 4): List<String> {
        val seeds = listOf("новости", "сегодня новости", "популярные новости")
        val suggestions = linkedSetOf<String>()

        for (seed in seeds) {
            if (suggestions.size >= maxResults) break
            runCatching {
                val encodedQuery = URLEncoder.encode(seed, "UTF-8")
                val request = Request.Builder()
                    .url("https://duckduckgo.com/ac/?q=$encodedQuery&kl=ru-ru")
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful || body.isBlank()) {
                        return@runCatching
                    }

                    val json = JSONArray(body)
                    for (index in 0 until json.length()) {
                        val phrase = json.optJSONObject(index)
                            ?.optString("phrase")
                            ?.trim()
                            .orEmpty()
                        if (phrase.isNotBlank()) {
                            suggestions.add(phrase)
                            if (suggestions.size >= maxResults) break
                        }
                    }
                }
            }
        }

        return suggestions.take(maxResults)
    }

    fun formatSources(sources: List<SearchSource>): String {
        if (sources.isEmpty()) {
            return ""
        }

        return buildString {
            append("Источники:\n")
            sources.forEachIndexed { index, source ->
                append(index + 1)
                append(". [")
                append(escapeMarkdownTitle(source.title))
                append("](")
                append(source.url)
                append(")")
                if (index < sources.size - 1) append("\n")
            }
        }
    }

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val resultRegex = Regex(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?<a[^>]+class=\"result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val structuredResults = resultRegex.findAll(html)
            .map { match ->
                SearchResult(
                    title = cleanHtml(match.groupValues[2]),
                    url = normalizeDuckDuckGoUrl(cleanHtml(match.groupValues[1])),
                    snippet = cleanHtml(match.groupValues[3])
                )
            }
            .filter { it.title.isNotBlank() || it.snippet.isNotBlank() }
            .take(maxResults)
            .toList()

        if (structuredResults.isNotEmpty()) {
            return structuredResults
        }

        val snippetRegex = Regex(
            "<a[^>]+class=\"result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return snippetRegex.findAll(html)
            .map { SearchResult(snippet = cleanHtml(it.groupValues[1])) }
            .filter { it.snippet.isNotBlank() }
            .take(maxResults)
            .toList()
    }

    private fun normalizeDuckDuckGoUrl(rawUrl: String): String {
        val url = rawUrl.removePrefix("//")
        val marker = "uddg="
        val markerIndex = url.indexOf(marker)
        if (markerIndex < 0) {
            return if (url.startsWith("http")) url else ""
        }

        val encodedUrl = url.substring(markerIndex + marker.length)
            .substringBefore("&")
        return runCatching {
            URLDecoder.decode(encodedUrl, "UTF-8")
        }.getOrDefault("")
    }

    private fun cleanHtml(value: String): String {
        return value
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun escapeMarkdownTitle(title: String): String {
        return title.replace("[", "\\[").replace("]", "\\]")
    }
}
