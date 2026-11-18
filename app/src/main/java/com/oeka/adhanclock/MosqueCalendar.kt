package com.oeka.adhanclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.Locale
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Übernimmt den Jahreskalender einer Moschee von ihrer öffentlichen Mawaqit-Seite.
 * Die Seite enthält die Gebetszeiten als JSON („confData“ → „calendar“: 12 Monate × Tage × 6 Zeiten).
 * Der Kalender wird lokal gespeichert und alle 14 Tage still aktualisiert.
 */
object MosqueCalendar {

    class Preset(val name: String, val url: String, val note: T? = null)

    val presets = listOf(
        Preset("Haymana Moschee (Jenfeld)", "https://mawaqit.net/de/m/haymana-moschee-jenfeld-hamburg-22043-germany"),
        Preset("Assahaba Moschee", "https://mawaqit.net/de/assahaba-hamburg"),
        Preset("Al-Nour Moschee", "https://mawaqit.net/de/m/al-nour-hamburg"),
        Preset(
            "Merkez Camii (IGMG)", "https://mawaqit.net/de/m/haymana-moschee-jenfeld-hamburg-22043-germany",
            T(
                "Nicht auf Mawaqit. Der IGMG-Kalender für Hamburg stimmt mit dem der Haymana Moschee überein (geprüft: ±1 Minute), daher wird dieser verwendet.",
                "Mawaqit'te yok. IGMG'nin Hamburg takvimi Haymana Camii'ninkiyle aynı (kontrol edildi: ±1 dakika), bu yüzden o kullanılır.",
                "Not on Mawaqit. The IGMG calendar for Hamburg matches the Haymana mosque (checked: ±1 minute), so that one is used."
            )
        ),
    )

    class Cal(val name: String, val url: String, val fetchedAt: Long, private val months: JSONArray) {
        /** Die 6 Zeiten (Fajr, Shuruq, Dhuhr, Asr, Maghrib, Isha) als "HH:MM" oder null. */
        fun day(date: LocalDate): List<String>? = try {
            val m = months.getJSONObject(date.monthValue - 1)
            var key = date.dayOfMonth.toString()
            if (!m.has(key) && date.monthValue == 2 && date.dayOfMonth == 29) key = "28"
            val a = m.getJSONArray(key)
            if (a.length() < 6) null else (0 until 6).map { a.getString(it) }
        } catch (_: Exception) { null }
    }

    private var cache: Cal? = null
    private var cacheStamp = -1L

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "mosque_calendar.json")

    fun load(ctx: Context): Cal? {
        val f = file(ctx)
        if (!f.exists()) return null
        if (cache != null && cacheStamp == f.lastModified()) return cache
        return try {
            val o = JSONObject(f.readText())
            Cal(o.getString("name"), o.getString("url"), o.optLong("fetchedAt"), o.getJSONArray("calendar")).also {
                cache = it; cacheStamp = f.lastModified()
            }
        } catch (_: Exception) { null }
    }

    /** Zeiten eines Tages aus dem Moschee-Kalender (oder null, wenn nicht vorhanden). */
    fun timesFor(ctx: Context, date: LocalDate, zone: ZoneId): List<ZonedDateTime>? {
        val cal = load(ctx) ?: return null
        val d = cal.day(date) ?: return null
        return try {
            d.map { s -> date.atTime(LocalTime.parse(s.trim().padStart(5, '0'))).atZone(zone) }
        } catch (_: Exception) { null }
    }

    /**
     * Lädt den Kalender von einer Mawaqit-Seite. Rückgabe im Haupt-Thread:
     * Name der Moschee oder null bei Fehler.
     */
    fun fetch(ctx: Context, url: String, displayName: String?, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val name = try {
                val html = download(url)
                val conf = extractConf(html)
                val cal = conf.getJSONArray("calendar")
                require(cal.length() == 12)
                val n = displayName ?: conf.optString("name").ifBlank { url }
                val out = JSONObject()
                    .put("name", n).put("url", url).put("fetchedAt", System.currentTimeMillis())
                    .put("calendar", cal)
                file(app).writeText(out.toString())
                n
            } catch (_: Exception) { null }
            main.post { cb(name) }
        }.start()
    }

    /** Alle 14 Tage still aktualisieren (Moscheen passen ihre Kalender gelegentlich an). */
    fun refreshIfStale(ctx: Context) {
        val cal = load(ctx) ?: return
        if (System.currentTimeMillis() - cal.fetchedAt > 14L * 24 * 3600 * 1000) {
            fetch(ctx, cal.url, cal.name) { if (it != null) AlarmScheduler.reschedule(ctx.applicationContext) }
        }
    }

    // ---------------------------------------------------------------- Eigene Kalender-Liste & Suche

    /** Vom Nutzer aufgenommene Moscheen (zusätzlich zu den Vorschlägen). */
    fun saved(ctx: Context): List<Preset> = try {
        val a = JSONArray(sp(ctx).getString("mosques_saved", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Preset(o.getString("name"), o.getString("url")) } }
    } catch (_: Exception) { emptyList() }

    fun addSaved(ctx: Context, name: String, url: String) {
        val list = saved(ctx).filter { it.url != url } + Preset(name, url)
        storeSaved(ctx, list)
    }

    fun removeSaved(ctx: Context, url: String) = storeSaved(ctx, saved(ctx).filter { it.url != url })

    private fun storeSaved(ctx: Context, list: List<Preset>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
        sp(ctx).edit().putString("mosques_saved", a.toString()).apply()
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adhanclock", Context.MODE_PRIVATE)

    /** Treffer der Mawaqit-Suche. [km] = Entfernung zum Standort (oder -1). */
    class Found(val name: String, val place: String, val url: String, val km: Double)

    /*
     * Mawaqit hat 2026 die Suche (api/2.0/mosque/search) privat gemacht – sie liefert ohne Konto-Token
     * nur noch 401. Öffentlich ist weiterhin die Länderliste api/2.0/mosque/map/{ISO2} (Name, Adresse,
     * Stadt, Koordinaten, Slug). Daraus berechnen wir selbst die nächsten Moscheen bzw. filtern nach Wörtern.
     */
    private val countryCache = HashMap<String, List<JSONObject>>()

    /** Nächste Moscheen auf Mawaqit zum Standort (öffentliche Länderliste, ohne Anmeldung). */
    fun searchNearby(ctx: Context, lat: Double, lng: Double, max: Int, cb: (List<Found>?) -> Unit) =
        search(ctx, null, lat, lng, max, cb)

    /** Mawaqit-Suche nach Name, Stadt oder Adresse (im Land des Standorts bzw. des Geräts). */
    fun searchWord(ctx: Context, word: String, lat: Double?, lng: Double?, max: Int, cb: (List<Found>?) -> Unit) =
        search(ctx, word, lat, lng, max, cb)

    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace("ß", "ss").replace("ı", "i")

    @Suppress("DEPRECATION")
    private fun countries(ctx: Context, lat: Double?, lng: Double?): List<String> {
        val out = LinkedHashSet<String>()
        if (lat != null && lng != null) try {
            android.location.Geocoder(ctx.applicationContext, Locale.ENGLISH).getFromLocation(lat, lng, 1)
                ?.firstOrNull()?.countryCode?.takeIf { it.length == 2 }?.let { out.add(it.uppercase(Locale.ROOT)) }
        } catch (_: Exception) {}
        listOf(Locale.getDefault().country, "DE").forEach { if (it.length == 2) out.add(it.uppercase(Locale.ROOT)) }
        return out.toList()
    }

    private fun countryList(cc: String): List<JSONObject> = synchronized(countryCache) { countryCache[cc] } ?: run {
        val txt = download("https://mawaqit.net/api/2.0/mosque/map/$cc").trim()
        val arr = if (txt.startsWith("[")) JSONArray(txt)
            else JSONObject(txt).let { o -> o.optJSONArray("data") ?: o.optJSONArray("mosques") ?: o.optJSONArray("results") ?: JSONArray() }
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        synchronized(countryCache) { countryCache[cc] = list }
        list
    }

    private fun search(ctx: Context, word: String?, lat: Double?, lng: Double?, max: Int, cb: (List<Found>?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val res: List<Found>? = try {
                val ccs = countries(ctx, lat, lng)
                // Ohne Wort: nur das Land des Standorts (erstes), sonst auch das Geräte-Land
                val use = if (word == null) ccs.take(1) else ccs
                var anyOk = false
                val rows = ArrayList<JSONObject>()
                for (cc in use) try { rows += countryList(cc); anyOk = true } catch (_: Exception) {}
                if (!anyOk) null else {
                    val tokens = word?.let { norm(it).split(Regex("\\s+")).filter { t -> t.isNotBlank() } }.orEmpty()
                    rows.asSequence().mapNotNull { o ->
                        val slug = o.optString("slug")
                        val link = when {
                            slug.isNotBlank() -> "https://mawaqit.net/de/$slug"
                            o.optString("url").startsWith("https://") -> o.optString("url")
                            else -> return@mapNotNull null
                        }
                        val name = o.optString("name").ifBlank { o.optString("label", slug) }
                        val city = o.optString("city")
                        val addr = o.optString("address").ifBlank { o.optString("localisation") }
                        if (tokens.isNotEmpty()) {
                            val hay = norm("$name $city $addr ${o.optString("zipcode")} $slug")
                            if (!tokens.all { hay.contains(it) }) return@mapNotNull null
                        }
                        val la = o.optDouble("lat", o.optDouble("latitude", Double.NaN))
                        val lo = o.optDouble("lng", o.optDouble("longitude", Double.NaN))
                        val km = if (lat != null && lng != null && !la.isNaN() && !lo.isNaN()) distKm(lat, lng, la, lo) else -1.0
                        val place = listOf(addr, (o.optString("zipcode") + " " + city).trim()).filter { it.isNotBlank() }.distinct().joinToString(", ")
                        Found(name, place, link, km)
                    }.distinctBy { it.url }.sortedBy { if (it.km < 0) Double.MAX_VALUE else it.km }.take(max).toList()
                }
            } catch (_: Exception) { null }
            main.post { cb(res) }
        }.start()
    }

    private fun distKm(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val r = 6371.0
        val dLa = Math.toRadians(a2 - a1); val dLo = Math.toRadians(o2 - o1)
        val h = Math.sin(dLa / 2).let { it * it } + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLo / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    fun clear(ctx: Context) {
        file(ctx).delete(); cache = null
    }

    fun isMawaqitUrl(url: String): Boolean = try {
        val h = URL(url).host.lowercase()
        URL(url).protocol == "https" && (h == "mawaqit.net" || h.endsWith(".mawaqit.net"))
    } catch (_: Exception) { false }

    private fun download(start: String): String {
        var url = URL(start)
        var redirects = 0
        while (true) {
            require(url.protocol == "https")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AdhanClockOeka/1.0")
            c.setRequestProperty("Accept", "application/json, text/html")
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, c.getHeaderField("Location")); c.disconnect(); redirects++; continue
            }
            require(code == 200)
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            return text
        }
    }

    /** Sucht „confData = { … }“ im Seitenquelltext und liest das JSON-Objekt (Klammern zählen). */
    private fun extractConf(html: String): JSONObject {
        val m = Regex("confData\\s*=\\s*\\{").find(html) ?: error("no confData")
        val start = m.range.last
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return JSONObject(html.substring(start, i + 1)) }
                }
            }
            i++
        }
        error("unterminated")
    }
}
