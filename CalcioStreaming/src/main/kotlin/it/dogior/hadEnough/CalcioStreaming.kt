package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.io.encoding.Base64

class CalcioStreaming : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://corner.direttecommunity.online"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "CalcioStreaming"

        /** The site serves its whole schedule from this single endpoint. */
        private const val EVENTS_PATH = "/api/events.php"
        private const val CACHE_DURATION_MS = 60_000L

        private var cachedEvents: List<CalcioEvent> = emptyList()
        private var cachedAt = 0L

        private val ZICO_SOURCES_REGEX = Regex("""ZT_SOURCES\s*=\s*(\[[\s\S]*?])\s*;""")
    }

    /** A directly playable link plus the referer its CDN expects. */
    data class Link(
        val name: String,
        val url: String,
        val ref: String
    )

    /* ─── Catalogue ─────────────────────────────────────────────────────────── */

    private suspend fun getEvents(forceRefresh: Boolean = false): List<CalcioEvent> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedEvents.isNotEmpty() && now - cachedAt < CACHE_DURATION_MS) {
            return cachedEvents
        }

        val body = app.get("$mainUrl$EVENTS_PATH", referer = "$mainUrl/").text
        val events = parseJson<EventsResponse>(body).events
        if (events.isNotEmpty()) {
            cachedEvents = events
            cachedAt = now
        }
        return events
    }

    private fun eventUrl(id: String) = "$mainUrl/?event=$id"

    private fun eventIdFromUrl(url: String) = url.substringAfter("?event=", "").substringBefore("&")

    private suspend fun findEvent(url: String): CalcioEvent? {
        val id = eventIdFromUrl(url)
        if (id.isBlank()) return null
        // The schedule rotates, so a stale cache miss is worth one refetch.
        return getEvents().firstOrNull { it.id == id }
            ?: getEvents(forceRefresh = true).firstOrNull { it.id == id }
    }

    private fun isToday(ts: Long?): Boolean {
        if (ts == null) return false
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }

    /** "2026-08-23T20:30:00+02:00" -> "20:30" */
    private fun CalcioEvent.clockTime() =
        startTime?.substringAfter("T", "")?.take(5)?.takeIf { it.length == 5 }

    private fun CalcioEvent.displayTitle() =
        title?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(homeTeam, awayTeam).joinToString(" x ").ifBlank { id }

    private fun CalcioEvent.badge() =
        homeTeamBadge?.takeIf { it.isNotBlank() } ?: awayTeamBadge?.takeIf { it.isNotBlank() }

    private suspend fun CalcioEvent.toSearchResponse(): SearchResponse {
        val prefix = when {
            status == "live" -> "🔴 "
            clockTime() != null -> "${clockTime()} "
            else -> ""
        }
        return newLiveSearchResponse(prefix + displayTitle(), eventUrl(id), TvType.Live) {
            this.posterUrl = badge()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = getEvents()
        if (events.isEmpty()) throw ErrorLoadingException("Nessun evento disponibile")

        val sections = listOf(
            "🔴 In Diretta" to events.filter { it.status == "live" },
            "Oggi" to events.filter { it.status == "scheduled" && isToday(it.startTs) },
            "Prossimi Eventi" to events.filter { it.status == "scheduled" && !isToday(it.startTs) }
        ).mapNotNull { (sectionName, sectionEvents) ->
            if (sectionEvents.isEmpty()) return@mapNotNull null
            HomePageList(
                sectionName,
                sectionEvents.sortedBy { it.startTs ?: 0L }.map { it.toSearchResponse() },
                isHorizontalImages = false
            )
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()

        return getEvents().filter { event ->
            listOfNotNull(event.title, event.league, event.homeTeam, event.awayTeam, event.sport)
                .any { it.lowercase(Locale.ROOT).contains(q) }
        }.sortedBy { it.startTs ?: 0L }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val event = findEvent(url) ?: throw ErrorLoadingException("Evento non disponibile")

        val description = listOfNotNull(
            event.league?.takeIf { it.isNotBlank() },
            event.sport?.takeIf { it.isNotBlank() },
            event.clockTime()?.let { "Inizio $it" },
            when (event.status) {
                "live" -> "In diretta"
                "finished" -> "Terminato"
                else -> null
            },
            "${event.streams.size} stream"
        ).joinToString(" - ")

        return newLiveStreamLoadResponse(
            name = event.displayTitle(),
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = event.badge()
            this.plot = description
        }
    }

    /* ─── Stream resolution ─────────────────────────────────────────────────── */

    /**
     * Decodes the `window._econfig` blob the sportsonline embed pages carry: base64
     * wrapping four shuffled, individually base64'd chunks, each missing its 4th
     * character.
     */
    fun getStreamUrl(html: String): String? {
        val configMatch = Regex("""window\._econfig\s*=\s*['"]([^'"]+)['"]""").find(html)
            ?: return null

        return try {
            val encodedConfig = configMatch.groupValues[1]
            val decodedConfig =
                Base64.decode(encodedConfig + "=".repeat((-encodedConfig.length % 4 + 4) % 4))
                    .toString(Charsets.ISO_8859_1)

            val partOrder = listOf(2, 0, 3, 1)
            val partLength = (decodedConfig.length + 3) / 4
            val encodedParts = mutableListOf<String>()
            var offset = 0

            repeat(4) {
                val part = decodedConfig.substring(
                    offset,
                    minOf(offset + partLength, decodedConfig.length)
                )
                offset += partLength
                encodedParts.add(part.take(3) + part.drop(4))
            }

            val decodedParts = Array(4) { "" }
            encodedParts.forEachIndexed { index, part ->
                val padded = part + "=".repeat((-part.length % 4 + 4) % 4)
                decodedParts[partOrder[index]] = Base64.decode(padded)
                    .toString(Charsets.ISO_8859_1)
            }

            val joinedConfig = decodedParts.joinToString("")
            val configJson = Base64
                .decode(joinedConfig + "=".repeat((-joinedConfig.length % 4 + 4) % 4))
                .toString(Charsets.UTF_8)

            val config = JSONObject(configJson)
            config.optString("stream_url_nop2p").ifEmpty { null }
                ?: config.optString("stream_url").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Resolves a relative iframe src against the page that contains it. */
    private fun absolutize(base: String, link: String): String = when {
        link.startsWith("http", ignoreCase = true) -> link
        link.startsWith("//") -> "https:$link"
        else -> base.toHttpUrlOrNull()?.resolve(link)?.toString() ?: link
    }

    /**
     * Walks the iframe chain of a sportsonline channel page until one exposes
     * `_econfig`. Returns the m3u8 and the embed page to use as referer.
     */
    private suspend fun extractVideoStream(
        url: String,
        ref: String,
        n: Int
    ): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        // These embed hosts sit behind Cloudflare and hand out 403s without it.
        val doc = app.get(url, referer = ref, interceptor = cfKiller).document
        val iframe = doc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: return null
        val next = absolutize(url, iframe)

        val newPage = app.get(
            next, referer = url, headers = mapOf(
                "Sec-Fetch-Dest" to "iframe"
            ), interceptor = cfKiller
        ).document

        val streamUrl = getStreamUrl(newPage.toString())
        return if (!streamUrl.isNullOrEmpty()) {
            streamUrl to next
        } else {
            extractVideoStream(url = next, ref = url, n = n + 1)
        }
    }

    /** zicotv player pages inline every CDN in a `ZT_SOURCES` JSON array. */
    private suspend fun extractZicoTv(stream: CalcioStream, name: String): List<Link> {
        val html = app.get(stream.url, referer = "$mainUrl/").text
        val raw = ZICO_SOURCES_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val origin = stream.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }
            ?: stream.url

        return parseJson<List<ZicoSource>>(raw)
            .filter { it.url.contains(".m3u8") }
            .mapIndexed { index, source ->
                val label = source.label?.takeIf { it.isNotBlank() } ?: "CDN ${index + 1}"
                Link("$name - $label", source.url, origin)
            }
    }

    private fun CalcioStream.displayName(): String {
        val base = label?.takeIf { it.isNotBlank() }
            ?: source?.takeIf { it.isNotBlank() }
            ?: "Stream"
        val language = lang?.takeIf { it.isNotBlank() && !it.equals("multi", true) }
        return if (language == null) base else "$base [${language.uppercase(Locale.ROOT)}]"
    }

    private suspend fun resolveStream(stream: CalcioStream): List<Link> {
        val name = stream.displayName()
        return try {
            if (stream.source.equals("zicotv", true) || stream.url.contains("zicotv", true)) {
                extractZicoTv(stream, name)
            } else {
                val resolved =
                    extractVideoStream(stream.url, stream.url.substringBefore("channels"), 1)
                if (resolved == null) emptyList()
                else listOf(Link(name, resolved.first, resolved.second))
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to resolve ${stream.url}: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val event = findEvent(data) ?: return false

        val links = event.streams.amap { resolveStream(it) }.flatten()
        links.forEach { link ->
            Log.d(TAG, link.toString())
            callback(
                newExtractorLink(
                    source = this.name,
                    name = link.name,
                    url = link.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = link.ref
                }
            )
        }
        return links.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }
}
