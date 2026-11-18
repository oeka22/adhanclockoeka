package com.oeka.adhanclock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Einrichtungs-Assistent beim ersten Start (jederzeit überspringbar):
 * 1 Sprache · 2 Standort/Moschee · 3 Gebetsruf & Lautstärke · 4 Design.
 */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private var step = 0
    private var player: MediaPlayer? = null
    private var locInfo: TextView? = null
    private val reqLoc = 31
    private val LAST = 4
    // Moscheen in der Nähe (Mawaqit), Auswahl per Häkchen
    private var nearby: List<MosqueCalendar.Found>? = null
    private var nearbyFor = ""
    private val nearbyChecked = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        step = savedInstanceState?.getInt("step") ?: 0
        build()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("step", step)
    }

    override fun onResume() {
        super.onResume()
        build() // z. B. nach Rückkehr von Design-Seite oder Einstellungen
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    private fun build() {
        C.applyTo(this, prefs.theme)
        val root = FrameLayout(this)
        root.addView(PatternBackground(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; fitsSystemWindows = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        render()
    }

    private fun render() {
        val l = prefs.lang
        col.removeAllViews()
        col.addView(text(S.setupTitle[l], 28f, C.cream, Fonts.light))
        // Schrittanzeige
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        S.setupSteps.forEachIndexed { i, t ->
            dots.addView(text("${i + 1}  ${t[l]}", 13f, if (i == step) C.gold else C.muted, if (i == step) Fonts.bold else Fonts.regular).apply {
                setPadding(0, 0, dp(14), 0)
            })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dots) }, lp(top = dp(10)))

        val body = card(18)
        col.addView(body, lp(top = dp(16)))
        when (step) {
            0 -> stepLanguage(body)
            1 -> stepLocation(body)
            2 -> stepAdhan(body)
            3 -> stepPerms(body)
            else -> stepDesign(body)
        }

        // Navigation
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(text(S.skip[l], 15f, C.muted, Fonts.medium).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { done() }
        })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (step > 0) nav.addView(pill("‹  " + S.back[l]) { step--; stopPreview(); render() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        nav.addView(pill(if (step < LAST) S.next[l] + "  ›" else S.finish[l], filled = true) {
            stopPreview()
            if (step < LAST) { step++; render() } else done()
        })
        col.addView(nav, lp(top = dp(16)))
    }

    private fun stepLanguage(box: LinearLayout) {
        Lang.entries.forEach { lang ->
            val sel = lang == prefs.lang
            box.addView(text(lang.label, 18f, if (sel) C.onAccent else C.cream, Fonts.medium, Gravity.CENTER).apply {
                background = roundRect(if (sel) C.gold else C.card, dp(16).toFloat(), C.gold, dp(1))
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setOnClickListener { prefs.lang = lang; Notifications.ensureChannels(this@SetupActivity); render() }
            }, lp(top = dp(8)))
        }
    }

    private fun stepLocation(box: LinearLayout) {
        val l = prefs.lang
        box.addView(text(S.setupLocInfo[l], 15f, C.cream, Fonts.light))
        locInfo = text(if (prefs.hasLocation) "📍  " + prefs.place.ifBlank { "%.3f°, %.3f°".format(prefs.lat, prefs.lng) } else "", 16f, C.gold, Fonts.medium)
        box.addView(locInfo, lp(top = dp(12)))
        // automatisch über GPS/Netz
        box.addView(pill("📍  " + S.setupLocate[l], filled = true) { locate() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(12)))
        box.addView(switchLine(S.useGps[l], prefs.autoLocation) { on -> prefs.autoLocation = on; if (on) locate() }, lp(top = dp(8)))
        // oder Stadt suchen
        box.addView(text(S.searchCity[l], 13f, C.muted, Fonts.medium), lp(top = dp(12)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = android.widget.EditText(this).apply {
            hint = S.cityHint[l]; setTextColor(C.cream); setHintTextColor(C.faint); textSize = 15f; isSingleLine = true
            background = roundRect(alpha(C.cream, 18), dp(12).toFloat(), C.cardStroke, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(pill("🔍") {
            LocationHelper.search(this, input.text.toString()) { list ->
                results.removeAllViews()
                list.forEach { a ->
                    results.addView(text("📍  " + LocationHelper.label(a), 15f, C.cream, Fonts.regular).apply {
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        setOnClickListener {
                            prefs.autoLocation = false
                            prefs.lat = a.latitude; prefs.lng = a.longitude; prefs.place = LocationHelper.label(a)
                            AlarmScheduler.reschedule(this@SetupActivity)
                            nearby = null; render()
                        }
                    })
                }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        box.addView(row, lp(top = dp(4)))
        box.addView(results)

        // Moscheen in der Nähe: die nächsten 4 von Mawaqit vorschlagen, mit Mehrfachauswahl
        if (prefs.hasLocation) {
            box.addView(text("🕌  " + S.nearbyMosques[l], 15f, C.gold, Fonts.medium), lp(top = dp(16)))
            box.addView(text(S.mosqueInfo[l], 12f, C.muted, Fonts.light))
            val key = "%.3f,%.3f".format(prefs.lat, prefs.lng)
            if (nearbyFor != key) {
                nearbyFor = key; nearby = null
                MosqueCalendar.searchNearby(this, prefs.lat, prefs.lng, 4) { f ->
                    nearby = f ?: emptyList()
                    if (f == null) Toast.makeText(this, S.mosqueSearchFailed[l], Toast.LENGTH_LONG).show()
                    nearbyChecked.clear(); f?.forEach { nearbyChecked.add(it.url) }
                    if (step == 1) render()
                }
                box.addView(text("…", 15f, C.muted, Fonts.light), lp(top = dp(6)))
            } else {
                val list = nearby.orEmpty()
                if (list.isEmpty()) box.addView(text(S.mosqueNoneFound[l], 13f, C.muted, Fonts.light), lp(top = dp(6)))
                list.forEach { f ->
                    box.addView(android.widget.CheckBox(this).apply {
                        text = f.name + (if (f.km >= 0) "  ·  %.1f km".format(f.km) else "")
                        setTextColor(C.cream); textSize = 15f
                        buttonTintList = ColorStateList.valueOf(C.gold)
                        isChecked = f.url in nearbyChecked
                        setOnCheckedChangeListener { _, c -> if (c) nearbyChecked.add(f.url) else nearbyChecked.remove(f.url) }
                    }, lp(top = dp(4)))
                }
                if (list.isNotEmpty()) box.addView(pill("＋  " + S.mosqueAdd[l], filled = true) {
                    val chosen = list.filter { it.url in nearbyChecked }
                    chosen.forEach { MosqueCalendar.addSaved(this, it.name, it.url) }
                    if (chosen.isNotEmpty()) {
                        Toast.makeText(this, S.downloading[l], Toast.LENGTH_SHORT).show()
                        MosqueCalendar.fetch(this, chosen.first().url, chosen.first().name) { n ->
                            if (n != null) { prefs.useMosque = true; AlarmScheduler.reschedule(this); Toast.makeText(this, S.mosqueLoaded[l].format(n), Toast.LENGTH_SHORT).show() }
                            else Toast.makeText(this, S.mosqueFailed[l], Toast.LENGTH_LONG).show()
                            render()
                        }
                    }
                }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(8)))
                MosqueCalendar.load(this)?.let { cal ->
                    if (prefs.useMosque) box.addView(text("✓  " + S.mosqueCurrent[l].format(cal.name), 13f, C.teal, Fonts.regular), lp(top = dp(8)))
                }
            }
        }
        box.addView(pill("⚙  " + S.setupMosque[l]) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, 2))
        }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = dp(14)))
    }

    private fun switchLine(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 15f, C.cream, Fonts.light), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(android.widget.Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.gold, C.faint))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return row
    }

    private fun stepPerms(box: LinearLayout) {
        val l = prefs.lang
        box.addView(text(S.setupPermsInfo[l], 15f, C.cream, Fonts.light), lp(bottom = dp(12)))
        Perms.panel(this, box)
        box.addView(switchLine(S.clapSetting[l], prefs.clapToStop) { on ->
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                prefs.clapToStop = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), Perms.REQ)
            } else prefs.clapToStop = on
        }, lp(top = dp(16)))
        box.addView(text(S.clapInfo[l], 12f, C.muted, Fonts.light))
    }

    private fun locate() {
        if (!LocationHelper.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), reqLoc)
            return
        }
        locInfo?.text = "…"
        LocationHelper.locate(this) { loc ->
            if (loc == null) { locInfo?.text = "—"; return@locate }
            prefs.autoLocation = true
            prefs.lat = loc.latitude; prefs.lng = loc.longitude
            if (step == 1) render() // Moscheen in der Nähe suchen
            locInfo?.text = "📍  %.3f°, %.3f°".format(loc.latitude, loc.longitude)
            LocationHelper.placeName(this, loc.latitude, loc.longitude) { name ->
                if (name != null) { prefs.place = name; locInfo?.text = "📍  $name" }
            }
            AlarmScheduler.reschedule(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqLoc && LocationHelper.hasPermission(this)) locate()
        else if (requestCode == Perms.REQ) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) prefs.clapToStop = false
            if (step == 1 && LocationHelper.hasPermission(this)) locate() else render()
        }
    }

    private fun stepAdhan(box: LinearLayout) {
        val l = prefs.lang
        box.addView(text(S.setupAdhanInfo[l], 15f, C.cream, Fonts.light))
        val current = prefs.adhanUri
        fun option(title: String, uri: String?) {
            val sel = current == uri
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(6), dp(6))
                background = roundRect(if (sel) C.goldSoft else C.card, dp(14).toFloat(), if (sel) C.gold else C.cardStroke, dp(1))
                setOnClickListener {
                    prefs.adhanUri = uri; prefs.adhanName = if (uri == null) null else "♪ $title"
                    P.SALAH.forEach { prefs.setPrayerSound(it, null, null) } // alle Gebete nutzen den Standard
                    render()
                }
            }
            row.addView(text((if (sel) "● " else "○ ") + title, 15f, C.cream, Fonts.regular), LinearLayout.LayoutParams(0, -2, 1f))
            if (uri != null) row.addView(pill("▶") { preview(uri) })
            box.addView(row, lp(top = dp(6)))
        }
        AdhanPresets.builtin.forEach { option(it.title, it.uri(this)) }
        option(S.defaultSound[l], null)
        // eine Lautstärke für alle Gebete
        box.addView(text("🔊  " + S.volume[l], 15f, C.gold, Fonts.medium), lp(top = dp(14)))
        val valueView = text("${prefs.volume(P.FAJR)} %", 15f, C.gold, Fonts.medium, Gravity.END)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(android.widget.SeekBar(this).apply {
            max = 20; progress = prefs.volume(P.FAJR) / 5
            progressTintList = ColorStateList.valueOf(C.gold); thumbTintList = ColorStateList.valueOf(C.gold)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    valueView.text = "${p * 5} %"
                    if (fromUser) P.SALAH.forEach { prefs.setVolume(it, p * 5) }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(56), -2))
        box.addView(row)
    }

    private fun stepDesign(box: LinearLayout) {
        val l = prefs.lang
        box.addView(text(S.setupDesignInfo[l], 15f, C.cream, Fonts.light))
        box.addView(text(Skins.byId(prefs.skin)?.title?.get(l) ?: S.classicDesign[l], 18f, C.gold, Fonts.bold), lp(top = dp(12)))
        box.addView(pill(S.chooseDesign[l], filled = true) { startActivity(Intent(this, DesignActivity::class.java)) }, lp(top = dp(12)))
    }

    private fun preview(uri: String) {
        stopPreview()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(this@SetupActivity, Uri.parse(uri))
                prepare(); start()
            }
        } catch (_: Exception) {}
    }

    private fun stopPreview() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    private fun done() {
        stopPreview()
        prefs.setupDone = true
        AlarmScheduler.reschedule(this)
        finish() // Berechtigungen (Benachrichtigungen) fragt danach die Startseite

    }
}
