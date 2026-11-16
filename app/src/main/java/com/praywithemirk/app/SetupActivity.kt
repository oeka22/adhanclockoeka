package com.praywithemirk.app

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
        nav.addView(pill(if (step < 3) S.next[l] + "  ›" else S.finish[l], filled = true) {
            stopPreview()
            if (step < 3) { step++; render() } else done()
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
        box.addView(pill("📍  " + S.setupLocate[l], filled = true) { locate() }, lp(top = dp(12)))
        box.addView(pill("🕌  " + S.setupMosque[l]) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, 2))
        }, lp(top = dp(8)))
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
