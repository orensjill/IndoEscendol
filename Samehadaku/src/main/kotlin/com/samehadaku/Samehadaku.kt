
package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Samehadaku : MainAPI() {

    override var mainUrl = "https://samehadaku.li"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) ||
                    t.contains("Special", true) ||
                    t.contains("ONA", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("ongoing", true) ||
                    t.contains("on going", true) -> ShowStatus.Ongoing
                t.contains("completed", true) ||
                    t.contains("complete", true) -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/" to "HomePage",
        "$mainUrl/daftar-anime-2/" to "Daftar Anime"
    )

    private fun extractResults(document: Document): List<AnimeSearchResponse> {
        val selectors = listOf(
            ".post-show ul li",
            ".post-show li",
            ".latest-release li",
            ".latestrelease li",
            ".lastest li",
            ".listupd .bs",
            ".listupd article",
            ".animepost",
            ".bs",
            "main article"
        )

        for (selector in selectors) {
            val results = document.select(selector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        // Fallback: baca tautan kartu langsung jika class situs berubah.
        return document.select("a[href]")
            .filter { anchor ->
                val href = anchor.absUrl("href")
                val text = buildString {
                    append(anchor.text())
                    append(" ")
                    append(anchor.attr("title"))
                    append(" ")
                    append(anchor.selectFirst("img")?.attr("alt").orEmpty())
                }.trim()

                val validHost = href.startsWith(mainUrl, true)
                val excluded = listOf(
                    "/genre/", "/tag/", "/category/",
                    "/wp-content/", "/author/", "/page/"
                ).any { href.contains(it, true) }

                validHost &&
                    !excluded &&
                    text.isNotBlank() &&
                    (text.contains("episode", true) ||
                     text.contains("subtitle indonesia", true) ||
                     anchor.selectFirst("img") != null)
            }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = when (request.name) {
            "Daftar Anime" ->
                if (page <= 1) "$mainUrl/daftar-anime-2/"
                else "$mainUrl/daftar-anime-2/page/$page/"

            else ->
                if (page <= 1) mainUrl
                else "$mainUrl/page/$page/"
        }

        val document = app.get(url).document
        val items = mutableListOf<HomePageList>()

        if (request.name == "HomePage" && page <= 1) {
            val blocks = document.select(
                ".widget_senction, .widget_section, " +
                    ".bixbox, .series, .listupd"
            )

            blocks.forEach { block ->
                val header = block.selectFirst(
                    ".widget-title h3, .releases h2, " +
                        ".bixbox h2, h2, h3"
                )?.text()?.trim() ?: "Anime"

                val results = block.select(
                    ".animepost, .bs, article, .uta, .utao"
                ).mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }

                if (results.isNotEmpty()) {
                    items.add(HomePageList(header, results))
                }
            }
        }

        if (items.isEmpty()) {
            val results = extractResults(document)

            if (results.isEmpty()) {
                throw ErrorLoadingException(
                    "Samehadaku: 0 anime ditemukan. " +
                        "Halaman: ${document.title()}. " +
                        "Periksa akses situs atau selector HTML."
                )
            }

            items.add(
                HomePageList(
                    request.name,
                    results,
                    true
                )
            )
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = if (tagName() == "a") {
            this
        } else {
            selectFirst(
                "h2 a, h3 a, .tt a, .title a, " +
                    ".tt h2 a, .tt h3 a, a[href]"
            )
        } ?: return null

        val href = anchor.absUrl("href").ifBlank {
            fixUrlNull(anchor.attr("href")).orEmpty()
        }

        if (href.isBlank() || !href.startsWith("http", true)) {
            return null
        }

        if (!href.contains("samehadaku", true)) return null

        val image = anchor.selectFirst("img")
            ?: selectFirst("img")

        val rawTitle = anchor.attr("title")
            .ifBlank { anchor.text() }
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank {
                selectFirst("h2, h3, .tt, .title")?.text().orEmpty()
            }
            .trim()

        if (rawTitle.isBlank()) return null

        val poster = image?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("data-original") }
                .ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let {
            fixUrlNull(it)
        }

        val episode = Regex(
            """(?i)(?:episode|ep\.?)\s*(\d+)"""
        ).find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val cleanTitle = rawTitle
            .replace(
                Regex("""(?i)\bepisode\s*\d+.*$"""),
                ""
            )
            .replace(
                Regex("""(?i)\bep\.?\s*\d+.*$"""),
                ""
            )
            .replace(
                Regex("""(?i)\b(subtitle indonesia|nonton anime)\b"""),
                ""
            )
            .trim()

        return newAnimeSearchResponse(
            cleanTitle.ifBlank { rawTitle },
            href,
            TvType.Anime
        ) {
            this.posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        return extractResults(document)
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.entry-title, h1"
        )?.text()?.removeBloat() ?: return null

        val poster = document.selectFirst(
            ".thumb img, .thumb > img, " +
                ".ime img, .entry-content img"
        )?.let { img ->
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let {
            fixUrlNull(it)
        }

        val tags = document.select(
            ".genre-info a, .genxed a, .genre a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }

        val infoText = document.select(
            ".spe, .info, .infox, .seriestu"
        ).text()

        val year = Regex("""\b(?:19|20)\d{2}\b""")
            .find(infoText)?.value?.toIntOrNull()

        val statusText = document.select(
            ".spe, .info, .infox, .seriestu"
        ).text()

        val status = getStatus(statusText)

        val typeText = document.select(
            ".spe, .info, .infox, .seriestu"
        ).text()

        val type = getType(typeText)

        val rating = document.selectFirst(
            ".ratingValue, .numscore, .rating"
        )?.text()?.trim()

        val description = document.select(
            ".desc p, .entry-content p, .synp p"
        ).text().trim()

        val trailer = document.selectFirst(
            ".trailer-anime iframe, " +
                ".trailer iframe, iframe[src*='youtube']"
        )?.attr("src")?.let {
            fixUrlNull(it)
        }

        val episodeElements = document.select(
            ".lstepsiode.listeps ul li, " +
                ".episodelist ul li, " +
                ".eplister ul li, " +
                ".listing a[href*='episode'], " +
                ".episodelist a[href], .eplister a[href]"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val anchor = if (element.tagName() == "a") {
                element
            } else {
                element.selectFirst("a[href]")
            } ?: return@mapNotNull null

            val episodeTitle = anchor.text().trim()

            val episodeNumber = Regex(
                """(?i)(?:episode|ep\.?)\s*(\d+)"""
            ).find(episodeTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val episodeUrl = anchor.absUrl("href").ifBlank {
                fixUrlNull(anchor.attr("href")).orEmpty()
            }

            if (episodeUrl.isBlank()) return@mapNotNull null

            newEpisode(episodeUrl) {
                this.name = episodeTitle.ifBlank {
                    "Episode ${episodeNumber ?: ""}".trim()
                }
                this.episode = episodeNumber
            }
        }.distinctBy { it.data }.reversed()

        val recommendations = document.select(
            "aside#sidebar li, .listupd .bs, .related .bs"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val tracker = APIHolder.getTracker(
            listOf(title),
            TrackerType.getTypes(type),
            year,
            true
        )

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            addScore(rating)
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val links = document.select(
            "#downloadb li a, #downloadb a, " +
                ".download a, .soraurlx a, " +
                ".player-embed iframe, .player-embed a, " +
                ".player iframe, .player a"
        )

        for (element in links) {
            val rawUrl = when (element.tagName()) {
                "iframe" -> element.attr("src")
                else -> element.attr("href")
                    .ifBlank { element.attr("src") }
            }

            val linkUrl = fixUrlNull(rawUrl)
                ?: continue

            if (!linkUrl.startsWith("http", true)) continue

            val quality = element.closest("li")
                ?.selectFirst("strong, b")
                ?.text()
                .orEmpty()
                .ifBlank { "Unknown" }

            loadFixedExtractor(
                linkUrl,
                quality,
                "$mainUrl/",
                subtitleCallback,
                callback
            )
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        loadExtractor(
            url,
            referer,
            subtitleCallback
        ) { link ->
            launch(Dispatchers.IO) {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int {
        val value = uppercase().replace(" ", "")

        return when {
            value.contains("4K") ||
                value.contains("2160") -> Qualities.P2160.value
            value.contains("FULLHD") ||
                value.contains("1080") -> Qualities.P1080.value
            value.contains("720") ||
                value == "HD" -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            else -> filter { it.isDigit() }
                .toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private fun String.removeBloat(): String {
        return replace(
            Regex("""(?i)(Nonton|Anime|Subtitle\s*Indonesia)"""),
            ""
        ).trim()
    }
}
