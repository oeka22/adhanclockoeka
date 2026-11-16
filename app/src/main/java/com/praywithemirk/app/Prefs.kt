package com.praywithemirk.app

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Alle Einstellungen der App (SharedPreferences). */
class Prefs(context: Context) {
    private val appCtx: Context = context.applicationContext
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("adhanclock", Context.MODE_PRIVATE)

    val hasLocation: Boolean get() = sp.contains("lat") && sp.contains("lng")
    var lat: Double
        get() = sp.getString("lat", "52.52")!!.toDoubleOrNull() ?: 52.52
        set(v) = sp.edit().putString("lat", v.toString()).apply()
    var lng: Double
        get() = sp.getString("lng", "13.405")!!.toDoubleOrNull() ?: 13.405
        set(v) = sp.edit().putString("lng", v.toString()).apply()
    var place: String
        get() = sp.getString("place", "") ?: ""
        set(v) = sp.edit().putString("place", v).apply()

    /** true = Standort bei jedem Öffnen automatisch aktualisieren. */
    var autoLocation: Boolean
        get() = sp.getBoolean("auto_loc", true)
        set(v) = sp.edit().putBoolean("auto_loc", v).apply()

    var method: Method
        get() = Method.byId(sp.getString("method", Method.DIYANET.id))
        set(v) = sp.edit().putString("method", v.id).apply()
    var hanafiAsr: Boolean
        get() = sp.getBoolean("hanafi", false)
        set(v) = sp.edit().putBoolean("hanafi", v).apply()
    var highLat: HighLatRule
        get() = HighLatRule.byId(sp.getString("highlat", HighLatRule.ANGLE_BASED.id))
        set(v) = sp.edit().putString("highlat", v.id).apply()

    fun offset(i: Int): Int = sp.getInt("offset_$i", 0)
    fun setOffset(i: Int, v: Int) = sp.edit().putInt("offset_$i", v).apply()

    fun adhanEnabled(i: Int): Boolean = i != P.SUNRISE && sp.getBoolean("adhan_$i", true)
    fun setAdhanEnabled(i: Int, v: Boolean) = sp.edit().putBoolean("adhan_$i", v).apply()

    /** Standard-Gebetsruf für alle Gebete (content:// oder file://). */
    /** Neuinstallation: eingebauter Gebetsruf Hafiz Mustafa Özcan. Leerer Text = bewusst der Weckton des Geräts. */
    var adhanUri: String?
        get() = (if (sp.contains("adhan_uri")) sp.getString("adhan_uri", "") else "android.resource://${appCtx.packageName}/raw/adhan_mustafa_oezcan")?.ifEmpty { null }
        set(v) = sp.edit().putString("adhan_uri", v ?: "").apply()
    var adhanName: String?
        get() = (if (sp.contains("adhan_name")) sp.getString("adhan_name", "") else "♪ Hafiz Mustafa Özcan")?.ifEmpty { null }
        set(v) = sp.edit().putString("adhan_name", v ?: "").apply()

    /** Individueller Gebetsruf je Gebet (überschreibt den Standard). */
    fun prayerUri(i: Int): String? = sp.getString("adhan_uri_$i", null)
    fun prayerName(i: Int): String? = sp.getString("adhan_name_$i", null)
    fun setPrayerSound(i: Int, uri: String?, name: String?) =
        sp.edit().putString("adhan_uri_$i", uri).putString("adhan_name_$i", name).apply()

    /** Adhkar-Audio nach dem Gebetsruf: [k] = 0 Morgen (nach Fajr), 1 Abend (nach Maghrib). */
    fun adhkarUri(k: Int): String? = sp.getString("adhkar_uri_$k", null)
    fun adhkarName(k: Int): String? = sp.getString("adhkar_name_$k", null)
    fun setAdhkarSound(k: Int, uri: String?, name: String?) =
        sp.edit().putString("adhkar_uri_$k", uri).putString("adhkar_name_$k", name).apply()
    /**
     * Adhkar nach dem Gebetsruf (Fajr/Maghrib): 0 = aus, 1 = automatisch direkt im Anschluss,
     * 2 = Auswahl-Fenster (30 s) mit Start-Knopf – dann kein automatisches Abspielen.
     */
    var adhkarMode: Int
        get() = if (sp.contains("adhkar_mode")) sp.getInt("adhkar_mode", 0)
                else if (sp.contains("adhkar_audio_on") && sp.getBoolean("adhkar_audio_on", false)) 1 else 0
        set(v) = sp.edit().putInt("adhkar_mode", v.coerceIn(0, 2)).apply()
    val adhkarAudioOn: Boolean get() = adhkarMode == 1
    /** Adhkar auch abspielen, wenn der Gebetsruf vorzeitig gestoppt wird (sonst endet beides zusammen). */
    var adhkarAfterStop: Boolean
        get() = sp.getBoolean("adhkar_after_stop", true)
        set(v) = sp.edit().putBoolean("adhkar_after_stop", v).apply()
    /** Adhkar-Audio für ein Gebet (nur Fajr und Maghrib), sonst null. */
    fun adhkarFor(prayer: Int): String? = when (prayer) {
        P.FAJR -> adhkarUri(0)
        P.MAGHRIB -> adhkarUri(1)
        else -> null
    }

    /** Welche Audiodatei für ein Gebet gespielt wird (null = Weckton des Geräts). */
    fun soundFor(i: Int): String? = prayerUri(i) ?: adhanUri

    /** Gebetsruf durch dreimaliges Klatschen stoppen (Mikrofon). */
    var clapToStop: Boolean
        get() = sp.getBoolean("clap_stop", false)
        set(v) = sp.edit().putBoolean("clap_stop", v).apply()

    /** Erklärung im Adhkar-Bildschirm aufgeklappt anzeigen. */
    var adhkarHelp: Boolean
        get() = sp.getBoolean("adhkar_help", true)
        set(v) = sp.edit().putBoolean("adhkar_help", v).apply()

    /** Lautstärke des Gebetsrufs je Gebet in Prozent (0–100) der maximalen Wecker-Lautstärke. */
    fun volume(i: Int): Int = sp.getInt("volume_$i", 80)
    fun setVolume(i: Int, v: Int) = sp.edit().putInt("volume_$i", v.coerceIn(0, 100)).apply()
    /** Lautstärke des Adhkar-Audios: [k] = 0 Morgen, 1 Abend (Prozent der Wecker-Lautstärke). */
    fun adhkarVolume(k: Int): Int = sp.getInt("adhkar_volume_$k", 80)
    fun setAdhkarVolume(k: Int, v: Int) = sp.edit().putInt("adhkar_volume_$k", v.coerceIn(0, 100)).apply()

    /** Schriftgröße der Startseite: 0 = normal, 1 = groß (Standard), 2 = sehr groß. */
    var mainScale: Int
        get() = sp.getInt("main_scale", 1)
        set(v) = sp.edit().putInt("main_scale", v).apply()
    val mainFactor: Float get() = floatArrayOf(1f, 1.3f, 1.6f)[mainScale.coerceIn(0, 2)]

    /** Neues Design (Skin-ID) oder "classic" für die klassischen Designs (Farbe + Stil). */
    /** Gewähltes Design. Neuinstallation: Mashrabiya · Schwarz. */
    var skin: String
        get() = sp.getString("skin", "mash_black") ?: "mash_black"
        set(v) = sp.edit().putString("skin", v).apply()

    /** Tag/Nacht-Wechsel: ab Fajr [daySkin], ab Maghrib [nightSkin]. */
    var dayNightOn: Boolean
        get() = sp.getBoolean("day_night", false)
        set(v) = sp.edit().putBoolean("day_night", v).apply()
    var daySkin: String
        get() = sp.getString("day_skin", "mash_white") ?: "mash_white"
        set(v) = sp.edit().putString("day_skin", v).apply()
    var nightSkin: String
        get() = sp.getString("night_skin", "mash_black") ?: "mash_black"
        set(v) = sp.edit().putString("night_skin", v).apply()

    /** Das jetzt gültige Design (berücksichtigt den Tag/Nacht-Wechsel). */
    fun activeSkin(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
        if (!dayNightOn) return skin
        return try {
            val t = timesFor(now.toLocalDate(), now.zone)
            if (!now.isBefore(t[P.FAJR]) && now.isBefore(t[P.MAGHRIB])) daySkin else nightSkin
        } catch (_: Exception) { skin }
    }

    /** Schriftgröße im Hadith-/Sure-Bereich: 0 klein, 1 normal, 2 groß, 3 sehr groß. */
    var hadithSize: Int
        get() = sp.getInt("hadith_size", 1)
        set(v) = sp.edit().putInt("hadith_size", v.coerceIn(0, 3)).apply()
    val hadithFactor: Float get() = floatArrayOf(0.85f, 1f, 1.2f, 1.4f)[hadithSize]

    /** Knöpfe auf der Startseite: 0 immer sichtbar, 1 bei Berührung (10 s). */
    var buttonsMode: Int
        get() = sp.getInt("buttons_mode", 0)
        set(v) = sp.edit().putInt("buttons_mode", v.coerceIn(0, 1)).apply()

    /** Einrichtungs-Assistent abgeschlossen oder übersprungen. */
    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    var style: AppStyle
        get() = AppStyle.byId(sp.getString("style", AppStyle.CLASSIC.id))
        set(v) = sp.edit().putString("style", v.id).apply()

    var theme: AppTheme
        get() = AppTheme.byId(sp.getString("theme", AppTheme.NIGHT.id))
        set(v) = sp.edit().putString("theme", v.id).apply()

    fun sourceEnabled(s: Src): Boolean = !sourceDeleted(s) && sp.getBoolean("src_${s.id}", true)
    fun sourceDeleted(s: Src): Boolean = sp.getBoolean("src_deleted_${s.id}", false)
    fun setSourceDeleted(s: Src, v: Boolean) = sp.edit().putBoolean("src_deleted_${s.id}", v).apply()
    fun setSourceEnabled(s: Src, v: Boolean) = sp.edit().putBoolean("src_${s.id}", v).apply()

    /** Ein Inhalt wird verwendet, wenn mindestens eine seiner Quellen aktiv ist. */
    fun allowed(source: String): Boolean {
        val list = Src.parse(source)
        return list.isEmpty() || list.any { sourceEnabled(it) }
    }

    var reminderMinutes: Int
        get() = sp.getInt("reminder_min", 0)
        set(v) = sp.edit().putInt("reminder_min", v).apply()

    var lang: Lang
        get() = Lang.byId(sp.getString("lang", Lang.DE.id))
        set(v) = sp.edit().putString("lang", v.id).apply()
    /** „Grund|Sekunden|Zeitpunkt“ des zuletzt beendeten Gebetsrufs (zur Fehlersuche). */
    var lastAdhanEnd: String
        get() = sp.getString("last_adhan_end", "") ?: ""
        set(v) = sp.edit().putString("last_adhan_end", v).apply()
    /** Lichtsensor: unter diesem Wert (Lux) wird gedimmt. */
    var nightLux: Int
        get() = sp.getInt("night_lux", 5)
        set(v) = sp.edit().putInt("night_lux", v).apply()

    /** Nachtmodus: 0 = aus, 1 = nach Uhrzeit, 2 = Lichtsensor. */
    var nightMode: Int
        get() = sp.getInt("night_mode", 0)
        set(v) = sp.edit().putInt("night_mode", v).apply()
    var nightFrom: Int
        get() = sp.getInt("night_from", 22)
        set(v) = sp.edit().putInt("night_from", v).apply()
    var nightTo: Int
        get() = sp.getInt("night_to", 6)
        set(v) = sp.edit().putInt("night_to", v).apply()
    /** 0 = sehr dunkel, 1 = dunkel, 2 = gedämpft */
    var nightLevel: Int
        get() = sp.getInt("night_level", 1)
        set(v) = sp.edit().putInt("night_level", v).apply()

    /** Arabische Schrift anzeigen (Gebetsnamen, Hadith/Sure, Adhkar, Anleitung). */
    var showArabic: Boolean
        get() = sp.getBoolean("arabic", true)
        set(v) = sp.edit().putBoolean("arabic", v).apply()

    var showTranslit: Boolean
        get() = sp.getBoolean("translit", true)
        set(v) = sp.edit().putBoolean("translit", v).apply()
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_on", false)
        set(v) = sp.edit().putBoolean("keep_on", v).apply()
    /** 0 = Countdown groß, 1 = Gebetszeit groß, 2 = aktuelle Uhrzeit groß. */
    var ringBig: Int
        get() = sp.getInt("ring_big", 0)
        set(v) = sp.edit().putInt("ring_big", v).apply()
    var headerClock: Boolean
        get() = sp.getBoolean("header_clock", true)
        set(v) = sp.edit().putBoolean("header_clock", v).apply()
    var showSeconds: Boolean
        get() = sp.getBoolean("seconds", true)
        set(v) = sp.edit().putBoolean("seconds", v).apply()
    /** 0 = klein, 1 = mittel, 2 = groß */
    var clockSize: Int
        get() = sp.getInt("clock_size", 1)
        set(v) = sp.edit().putInt("clock_size", v).apply()

    var hijriOffset: Int
        get() = sp.getInt("hijri_offset", 0)
        set(v) = sp.edit().putInt("hijri_offset", v).apply()

    fun calcParams() = CalcParams(method, hanafiAsr, highLat, IntArray(6) { offset(it) })

    /** Gebetszeiten für ein Datum in der Gerätezeitzone. */
    /** true = Zeiten aus dem Kalender einer Moschee (Mawaqit) statt Berechnung. */
    var useMosque: Boolean
        get() = sp.getBoolean("use_mosque", false)
        set(v) = sp.edit().putBoolean("use_mosque", v).apply()

    /** Gebetszeiten für ein Datum: Moschee-Kalender (falls gewählt und vorhanden), sonst Berechnung. */
    fun timesFor(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<ZonedDateTime> {
        if (useMosque) MosqueCalendar.timesFor(appCtx, date, zone)?.let { return it }
        return PrayTimes.compute(date, lat, lng, zone, calcParams())
    }
}
