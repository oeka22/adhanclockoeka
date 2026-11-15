package com.praywithemirk.app

/**
 * Vorgeschlagene Gebetsrufe von Wikimedia Commons (nur frei lizenzierte Medien).
 * Special:FilePath leitet auf die eigentliche Datei weiter; heruntergeladen wird erst auf Wunsch.
 */
object AdhanPresets {
    class Preset(val title: String, val file: String) {
        val url: String get() = "https://commons.wikimedia.org/wiki/Special:FilePath/" + file.replace(' ', '_')
        val page: String get() = "https://commons.wikimedia.org/wiki/File:" + file.replace(' ', '_')
    }

    /** In der App enthaltene Gebetsrufe (res/raw). Gespeichert wird der Name, damit die Auswahl Updates übersteht. */
    class Builtin(val title: String, val raw: String) {
        fun uri(ctx: android.content.Context) = "android.resource://${ctx.packageName}/raw/$raw"
    }

    val builtin = listOf(
        Builtin("Mishary Rashid Alafasy (1)", "adhan_alafasy_1"),
        Builtin("Mishary Rashid Alafasy (2)", "adhan_alafasy_2"),
        Builtin("Mishary Rashid Alafasy (3)", "adhan_alafasy_3"),
        Builtin("Hafiz Mustafa Özcan", "adhan_mustafa_oezcan"),
        Builtin("Ahmad al-Nafees", "adhan_ahmad_al_nafees"),
        Builtin("Mansour al-Zahrani", "adhan_mansour_al_zahrani"),
        Builtin("Karl Jenkins – Call to Prayers", "adhan_karl_jenkins"),
    )

    /** In der App enthaltene Adhkar: [0] = Morgen (nach Fajr), [1] = Abend (nach Maghrib). */
    val adhkarBuiltin = listOf(
        listOf(
            Builtin("Mishary Rashid Alafasy (1) · 2 Min.", "adhkar_morning_1_alafasy"),
            Builtin("Mishary Rashid Alafasy (2) · 21 Min.", "adhkar_morning_2_alafasy"),
            Builtin("Fares Abbad · 21 Min.", "adhkar_morning_3_fares_abbad"),
            Builtin("Muhammad Jibril · 22 Min.", "adhkar_morning_4_jibril"),
            Builtin("Morgen-Adhkar (5) · 4 Min.", "adhkar_morning_5"),
        ),
        listOf(
            Builtin("Nasser Al-Qatami · 6 Min.", "adhkar_evening_1_qatami"),
            Builtin("Mishary Rashid Alafasy (1) · 21 Min.", "adhkar_evening_2_alafasy"),
            Builtin("Fares Abbad · 20 Min.", "adhkar_evening_3_fares_abbad"),
            Builtin("Muhammad Jibril · 22 Min.", "adhkar_evening_4_jibril"),
            Builtin("Abend-Adhkar (5) · 4 Min.", "adhkar_evening_5"),
            Builtin("Mishary Rashid Alafasy (2) · 11 Min.", "adhkar_evening_6_alafasy"),
        ),
    )

    val list = listOf(
        Preset("Adhan – Prophetenmoschee, Medina", "33937_ejaz215_call-to-prayer-from-the-prophet-s-mo.ogg"),
        Preset("The Adhan – Aaqib Azeez", "The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3"),
        Preset("Beautiful Adhan", "Beautiful_adhan.ogg"),
        Preset("Azan", "Azan.ogg"),
        Preset("Adhan (Wikipedia)", "Adhan_wiki.oga"),
    )
}
