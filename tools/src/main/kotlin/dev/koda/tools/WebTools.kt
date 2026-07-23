package dev.koda.tools

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val UA = "Mozilla/5.0 (compatible; KodaAgent/1.0)"
private const val MAX_TEXT = 15000

private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private fun get(url: String): String {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", UA)
        .timeout(Duration.ofSeconds(20))
        .GET().build()
    return http.send(req, HttpResponse.BodyHandlers.ofString()).body()
}

/** Strip HTML to readable text — no dependency, just careful regex. */
internal fun htmlToText(html: String): String {
    var s = html
    s = s.replace(Regex("(?is)<script.*?</script>"), " ")
    s = s.replace(Regex("(?is)<style.*?</style>"), " ")
    s = s.replace(Regex("(?is)<!--.*?-->"), " ")
    s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?is)</(p|div|h[1-6]|li|tr|section|article)>"), "\n")
    s = s.replace(Regex("(?s)<[^>]+>"), " ")
    s = decodeEntities(s)
    return s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun decodeEntities(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
    .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")

/** Fetch a URL and return its readable text. Read-only; no approval needed. */
class WebFetchTool : KodaTool {
    override val name = "web_fetch"
    override val description =
        "Fetch a URL (http/https) and return its readable text content — docs, " +
        "articles, API references. Returns cleaned text, not raw HTML."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("url"),
        properties = mapOf("url" to ("string" to "The absolute http(s) URL to fetch")),
    )

    override fun summarize(args: JsonObject) = "web_fetch ${args.requiredString("url")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.requiredString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("url must be an absolute http(s) URL")
        }
        return runCatching {
            val body = get(url)
            val text = if (body.trimStart().startsWith("<")) htmlToText(body) else body
            val clipped = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "\n… (truncated)" else text
            ToolResult(clipped.ifBlank { "(empty response)" })
        }.getOrElse { ToolResult.error("fetch failed: ${it.message}") }
    }
}

/**
 * Search the web. Uses Tavily when TAVILY_API_KEY is set (reliable, structured);
 * otherwise falls back to a keyless DuckDuckGo HTML scrape (best-effort, can be
 * rate-limited). Read-only; no approval needed.
 */
class WebSearchTool : KodaTool {
    override val name = "web_search"
    override val description =
        "Search the web and return the top results (title, URL, snippet). Set " +
        "TAVILY_API_KEY for reliable results; otherwise a keyless fallback is used. " +
        "Use to find current information, then web_fetch a result for detail."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("query"),
        properties = mapOf("query" to ("string" to "The search query")),
    )

    override fun summarize(args: JsonObject) = "web_search ${args.requiredString("query")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.requiredString("query")
        val key = System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
        return runCatching {
            val results = if (key != null) tavily(query, key) else duckduckgo(query)
            if (results.isEmpty()) ToolResult("No results found for: $query")
            else ToolResult(results.take(8).joinToString("\n\n"))
        }.getOrElse { ToolResult.error("search failed: ${it.message}") }
    }

    private fun tavily(query: String, key: String): List<String> {
        val payload = buildJsonObject { put("api_key", key); put("query", query); put("max_results", 8) }
        val req = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
            .header("Content-Type", "application/json").header("User-Agent", UA)
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), payload)))
            .build()
        val body = http.send(req, HttpResponse.BodyHandlers.ofString()).body()
        val arr = (Json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = o["content"]?.jsonPrimitive?.contentOrNull?.take(300) ?: ""
            "${i + 1}. $title\n   $url" + if (content.isNotBlank()) "\n   $content" else ""
        }
    }

    private fun duckduckgo(query: String): List<String> {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        // Attribute-order-independent: find each result anchor, then pull href from its attrs.
        val anchorRe = Regex("(?is)<a\\b([^>]*result__a[^>]*)>(.*?)</a>")
        val snipRe = Regex("(?is)<a\\b[^>]*result__snippet[^>]*>(.*?)</a>")
        val hrefRe = Regex("href=\"([^\"]+)\"")
        val snippets = snipRe.findAll(html).map { htmlToText(it.groupValues[1]).replace("\n", " ").trim() }.toList()
        return anchorRe.findAll(html).toList().mapIndexed { i, m ->
            val href = hrefRe.find(m.groupValues[1])?.groupValues?.get(1)?.let(::resolveDdg) ?: ""
            val title = htmlToText(m.groupValues[2]).replace("\n", " ").trim()
            val snip = snippets.getOrNull(i).orEmpty()
            "${i + 1}. $title\n   $href" + if (snip.isNotEmpty()) "\n   $snip" else ""
        }
    }

    /** DuckDuckGo wraps result hrefs as /l/?uddg=<encoded real url>. */
    private fun resolveDdg(href: String): String {
        val m = Regex("uddg=([^&]+)").find(href) ?: return if (href.startsWith("//")) "https:$href" else href
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href)
    }
}
