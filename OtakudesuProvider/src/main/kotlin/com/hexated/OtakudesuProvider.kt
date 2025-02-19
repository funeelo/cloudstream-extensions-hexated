package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.ArrayList

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.lol"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        //        private val interceptor = CloudflareKiller()
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/complete-anime/page/" to "Anime Completed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data + page
//            , interceptor = interceptor
        ).document
        val home = document.select("div.venz > ul > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.select("div.thumbz > img").attr("src").toString()
        val epNum = this.selectFirst("div.epz")?.ownText()?.replace(Regex("[^0-9]"), "")?.trim()
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
//            posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query&post_type=anime"
        val document = app.get(
            link
//            , interceptor = interceptor
        ).document

        return document.select("ul.chivsrc > li").map {
            val title = it.selectFirst("h2 > a")!!.ownText().trim()
            val href = it.selectFirst("h2 > a")!!.attr("href")
            val posterUrl = it.selectFirst("img")!!.attr("src").toString()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
//                posterHeaders = interceptor.getCookieHeaders(url).toMap()
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url
//            , interceptor = interceptor
        ).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()
            ?.replace(":", "")?.trim().toString()
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()
            ?.replace(":", "")?.trim() ?: "tv"

        val year = Regex("\\d, ([0-9]*)").find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst("div.infozingle > p:nth-child(6) > span")!!.ownText()
                .replace(":", "")
                .trim()
        )
        val description = document.select("div.sinopc > p").text()

        val (malId, anilistId, image, cover) = getTracker(title, type, year)

        val episodes = document.select("div.episodelist")[1].select("ul > li").mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode = Regex("Episode\\s?([0-9]+)").find(name)?.groupValues?.getOrNull(0)
                ?: it.selectFirst("a")?.text()
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            Episode(link, name, episode = episode?.toIntOrNull())
        }.reversed()

        val recommendations =
            document.select("div.isi-recommend-anime-series > div.isi-konten").map {
                val recName = it.selectFirst("span.judul-anime > a")!!.text()
                val recHref = it.selectFirst("a")!!.attr("href")
                val recPosterUrl = it.selectFirst("a > img")?.attr("src").toString()
                newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                    this.posterUrl = recPosterUrl
//                    posterHeaders = interceptor.getCookieHeaders(url).toMap()
                }
            }

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = image ?: poster
            backgroundPosterUrl = cover ?: image ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
            this.recommendations = recommendations
//            posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }
    }


    data class ResponseSources(
        @JsonProperty("id") val id: String,
        @JsonProperty("i") val i: String,
        @JsonProperty("q") val q: String,
    )

    data class ResponseData(
        @JsonProperty("data") val data: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data
//            , interceptor = interceptor
        ).document
        val scriptData = document.select("script:containsData(action:)").lastOrNull()?.data()
        val token = scriptData?.substringAfter("{action:\"")?.substringBefore("\"}").toString()

        val nonce = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to token))
            .parsed<ResponseData>().data
        val action = scriptData?.substringAfter(",action:\"")?.substringBefore("\"}").toString()

        val mirrorData = document.select("div.mirrorstream > ul > li").mapNotNull {
            base64Decode(it.select("a").attr("data-content"))
        }.toString()

        tryParseJson<List<ResponseSources>>(mirrorData)?.apmap { res ->
            val id = res.id
            val i = res.i
            val q = res.q

            var sources = Jsoup.parse(
                base64Decode(
                    app.post(
                        "${mainUrl}/wp-admin/admin-ajax.php", data = mapOf(
                            "id" to id,
                            "i" to i,
                            "q" to q,
                            "nonce" to nonce,
                            "action" to action
                        )
                    ).parsed<ResponseData>().data
                )
            ).select("iframe").attr("src")

            if (sources.startsWith("https://desustream.me")) {
                if (!sources.contains(Regex("/arcg/|/odchan/|/desudrive/|/moedesu/"))) {
                    sources = app.get(sources).document.select("iframe").attr("src")
                }
                if (sources.startsWith("https://yourupload.com")) {
                    sources = sources.replace("//", "//www.")
                }
            }

            loadExtractor(sources, data, subtitleCallback, callback)

        }

        return true
    }

    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://api.consumet.org/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                    title,
                    true
                )) || (media.type.equals(type, true) && media.releaseDate == year)
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class Results(
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class AniSearch(
        @JsonProperty("results") val results: ArrayList<Results>? = arrayListOf(),
    )

}

class Moedesu : JWPlayer() {
    override val name = "Moedesu"
    override val mainUrl = "https://desustream.me/moedesu/"
}