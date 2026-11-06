package eu.kanade.tachiyomi.extension.en.torboxdirect

import android.app.Application
import android.content.Context
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class TorBoxDirect : HttpSource(), ConfigurableSource {
    override val name = "TorBox Direct"
    override val lang = "en"
    override val id = 746850379145316052L
    override val baseUrl = "https://api.torbox.app"
    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }
    private val apiClient by lazy { network.client }
    private val pageCache = LinkedHashMap<String, List<ComicPage>>(2, 0.75f, true)

    override val client by lazy {
        apiClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val segments = request.url.pathSegments
            if (request.url.host != PAGE_HOST || segments.size != 3) {
                return@addInterceptor chain.proceed(request)
            }
            val key = "${segments[0]}/${segments[1]}"
            val page = synchronized(pageCache) {
                pageCache[key]?.getOrNull(segments[2].toIntOrNull() ?: -1)
            } ?: throw IOException("Reopen the chapter to reload its archive.")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", page.type)
                .header("Cache-Control", "no-store")
                .body(page.bytes.toResponseBody(page.type.toMediaType())).build()
        }.build()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "torbox_api_key"
            title = "TorBox API key"
            summary = "Set this from your TorBox account settings."
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        })
    }

    private fun key() = preferences.getString("torbox_api_key", null)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw IOException("Set your TorBox API key in the source settings.")

    private fun api(route: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "$baseUrl/v1/api/torrents/$route".toHttpUrl().newBuilder()
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val request = Request.Builder().url(url.build())
            .header("Authorization", "Bearer ${key()}")
            .cacheControl(CacheControl.FORCE_NETWORK).build()
        try {
            apiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("TorBox returned HTTP ${response.code}.")
                val payload = JSONObject(response.body!!.string())
                if (!payload.optBoolean("success")) throw IOException("TorBox rejected the request.")
                return payload
            }
        } catch (_: IOException) {
            // requestdl includes a credential in its URL. Do not surface network exception text.
            throw IOException("TorBox request failed. Check your API key and torrent status.")
        }
    }

    private fun torrents() = api("mylist").getJSONArray("data").objects()
    private fun torrent(id: String) = torrents().firstOrNull { it.optString("id") == id }
        ?: throw IOException("This torrent is no longer in your TorBox library.")

    private fun archives(torrent: JSONObject) = torrent.optJSONArray("files")?.objects().orEmpty()
        .filter { it.optString("name").endsWith(".cbz", true) || it.optString("name").endsWith(".zip", true) }

    private fun manga(torrent: JSONObject) = SManga.create().apply {
        title = torrent.getString("name")
        url = "/${torrent.getLong("id")}"
        initialized = true
    }

    private fun browse(page: Int, query: String) = Observable.fromCallable {
        if (page != 1) return@fromCallable MangasPage(emptyList(), false)
        MangasPage(torrents().filter {
            it.optBoolean("download_finished") && it.optString("name").contains(query, true) &&
                archives(it).isNotEmpty()
        }.map(::manga), false)
    }.subscribeOn(Schedulers.io())

    override fun fetchPopularManga(page: Int) = browse(page, "")
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = browse(page, query)
    override fun fetchMangaDetails(manga: SManga) = Observable.fromCallable {
        this.manga(torrent(manga.url.trim('/')))
    }.subscribeOn(Schedulers.io())

    override fun fetchChapterList(manga: SManga) = Observable.fromCallable {
        val torrent = torrent(manga.url.trim('/'))
        archives(torrent).sortedBy { it.optString("name") }.mapIndexed { index, file ->
            SChapter.create().apply {
                name = file.getString("name").substringAfterLast('/')
                url = "${manga.url}/${file.getLong("id")}"
                chapter_number = (index + 1).toFloat()
            }
        }.asReversed()
    }.subscribeOn(Schedulers.io())

    override fun fetchPageList(chapter: SChapter) = Observable.fromCallable {
        val parts = chapter.url.trim('/').split('/')
        if (parts.size != 2 || parts.any { it.toLongOrNull() == null }) {
            throw IOException("Invalid TorBox archive ID.")
        }
        val cacheKey = parts.joinToString("/")
        val comic = synchronized(pageCache) { pageCache[cacheKey] } ?: loadArchive(parts[0], parts[1]).also {
            synchronized(pageCache) {
                pageCache[cacheKey] = it
                if (pageCache.size > 1) pageCache.remove(pageCache.keys.first())
            }
        }
        comic.indices.map { index ->
            Page(index, imageUrl = "https://$PAGE_HOST/$cacheKey/$index")
        }
    }

    private fun loadArchive(torrentId: String, fileId: String): List<ComicPage> {
        val link = api("requestdl", mapOf(
            "token" to key(), "torrent_id" to torrentId, "file_id" to fileId,
        )).getString("data").toHttpUrl()
        if (!link.isHttps) throw IOException("TorBox did not return an HTTPS link.")
        val request = Request.Builder().url(link).cacheControl(CacheControl.FORCE_NETWORK).build()
        try {
            apiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Could not open the TorBox file.")
                val body = response.body ?: throw IOException("TorBox returned an empty file.")
                return ComicArchive.read(body.byteStream(), body.contentLength())
            }
        } catch (error: ComicArchive.InvalidArchive) {
            throw error
        } catch (_: IOException) {
            throw IOException("Could not read the archive from TorBox.")
        }
    }

    // The fetch overrides above handle every supported route.
    override fun popularMangaRequest(page: Int): Request = unused()
    override fun popularMangaParse(response: Response): MangasPage = unused()
    override fun latestUpdatesRequest(page: Int): Request = unused()
    override fun latestUpdatesParse(response: Response): MangasPage = unused()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
    override fun searchMangaParse(response: Response): MangasPage = unused()
    override fun mangaDetailsParse(response: Response): SManga = unused()
    override fun chapterListParse(response: Response): List<SChapter> = unused()
    override fun pageListParse(response: Response): List<Page> = unused()
    override fun imageUrlParse(response: Response): String = unused()

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun unused(): Nothing = throw UnsupportedOperationException()
    private companion object {
        const val PAGE_HOST = "torbox-pages.invalid"
    }
}
