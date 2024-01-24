
package eu.kanade.tachiyomi.animeextension.es.hackstore

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hackstore : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hackstore"

    override val baseUrl = "https://hackstore.rs"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/page/$page/")

    override fun popularAnimeSelector(): String = "div.movie-thumbnail"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.movie-thumbnail > div.movie-back > a").attr("href"))
        anime.title = element.select("h3 > a.movie-title").attr("title")
        anime.thumbnail_url = element.select("div.movie-back > a > div.poster-pad > img.imghacks").attr("data-src")
        anime.description = ""
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi"

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/peliculas/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request { throw UnsupportedOperationException() }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.description = document.selectFirst("#main-content > div > div.content-area.twelve.columns > div.watch-content > center > div > p:nth-child(1)")!!.text().removeSurrounding("\"")
        document.select("#main-content > div > div.content-area.twelve.columns > div.watch-content > center > div > p").map {
            val textContent = it.text()
            val tempContent = textContent.lowercase()
            if (tempContent.contains("titulo latino")) anime.title = textContent.replace("Titulo Latino:", "").trim()
            if (tempContent.contains("genero")) anime.genre = textContent.replace("Genero:", "").trim()
            if (tempContent.contains("Director")) anime.author = textContent.replace("Director:", "").trim()
            textContent.replace("en 1 Link", "").trim()
        }
        return anime
    }

    private fun externalOrInternalImg(url: String): String {
        return if (url.contains("https")) url else "$baseUrl/$url"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val ep = SEpisode.create()
        ep.setUrlWithoutDomain(response.request.url.toString())
        ep.name = "PELÍCULA"
        ep.episode_number = 1f

        episodeList.add(ep)

        return episodeList
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element): SEpisode { throw UnsupportedOperationException() }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val table = document.select("table.episodes")
        val rows = table.select("tbody tr.tabletr")

        rows.forEach { row ->
            val serverElement = row.select("td:eq(0)")
            val linkElement = row.select("td:eq(1) a")

            val server = serverElement.text()
            val url = linkElement.attr("href")

            val isLatino = server.contains("latino")
            val isSub = server.contains("subtitulado")

            when {
                server.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(url, if (isLatino) "StreamTape Latino" else if (isSub) "StreamTape Subtitulado" else "StreamTape Castellano")
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                server.contains("voe") -> {
                    val video = VoeExtractor(client).videosFromUrl(url, if (isLatino) "VOE Latino" else if (isSub) "VOE Subtitulado" else "VOE Castellano")
                    videoList.addAll(video)
                }
                server.contains("filemoon") -> {
                    val video = FilemoonExtractor(client).videosFromUrl(url, if (isLatino) "Filemoon Latino" else if (isSub) "Filemoon Subtitulado" else "Filemoon Castellano")
                    videoList.addAll(video)
                }
                server.contains("streamwish") -> {
                    val video = StreamWishExtractor(client, headers).videosFromUrl(url, if (isLatino) "StreamWish Latino" else if (isSub) "StreamWish Subtitulado" else "StreamWish Castellano")
                    videoList.addAll(video)
                }
                server.contains("dood") -> {
                    val video = DoodExtractor(client).videosFromUrl(url, if (isLatino) "Dood Latino" else if (isSub) "Dood Subtitulado" else "Dood Castellano")
                    videoList.addAll(video)
                }
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = "#onpn > table > tbody > tr > td.link-td > a"

    override fun videoFromElement(element: Element): Video { throw UnsupportedOperationException() }

    override fun videoUrlParse(document: Document): String { throw UnsupportedOperationException() }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // =========================== Preferences =============================

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Latino"
        private val LANGUAGE_LIST = arrayOf("Latino", "Castellano", "Subtitulado")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "DoodStream"
        private val SERVER_LIST = arrayOf("DoodStream", "StreamTape", "VOE", "Filemoon", "StreamWish")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
