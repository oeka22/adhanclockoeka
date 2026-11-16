package com.praywithemirk.app

import android.app.Activity
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Auswahl-Fenster nach dem Gebetsruf (Einstellung Adhkar = „Auswahl“):
 * zeigt 30 Sekunden lang alle passenden Adhkar (Morgen nach Fajr, Abend nach Maghrib);
 * Antippen startet die Wiedergabe. Ohne Auswahl schließt es sich von selbst.
 */
class AdhkarPickActivity : Activity() {

    companion object {
        const val SHOW_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var card: LinearLayout
    private var endAt = 0L
    // Wiedergabe-Ansicht nach dem Antippen
    private var playing = false
    private var playStart = 0L
    private var playDuration = 0L
    private var remainingView: TextView? = null

    private val finishedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) { if (!isFinishing) finish() }
    }

    private val tick = object : Runnable {
        override fun run() {
            val l = Prefs(this@AdhkarPickActivity).lang
            if (playing) {
                // Restzeit der laufenden Adhkar-Aufnahme; Klatsch-Erkennung im Dienst einschalten
                AdhanService.enableClap(this@AdhkarPickActivity)
                if (playDuration > 0) {
                    val left = ((playDuration - (System.currentTimeMillis() - playStart)) / 1000).coerceAtLeast(0)
                    remainingView?.text = S.remainingFmt[l].format("%d:%02d".format(left / 60, left % 60))
                }
                handler.postDelayed(this, 1000)
                return
            }
            val left = ((endAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdown.text = S.closesIn[l].format(left)
            if (left <= 0) finish() else handler.postDelayed(this, 500)
        }
    }

    /** Nach dem Antippen: Fenster wird zur Wiedergabe-Ansicht mit großem Stopp-Knopf und Restzeit. */
    private fun startPlaying(prayer: Int, title: String, uri: String) {
        val prefs = Prefs(this)
        val l = prefs.lang
        AdhanService.playAdhkar(this, prayer, uri)
        playing = true
        playStart = System.currentTimeMillis()
        playDuration = try {
            val mr = android.media.MediaMetadataRetriever()
            mr.setDataSource(this, android.net.Uri.parse(uri))
            val d = mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mr.release(); d
        } catch (_: Exception) { 0L }
        card.removeAllViews()
        card.addView(text("▶  " + S.nowPlaying[l], 15f, C.gold, Fonts.medium))
        card.addView(text(title, 22f, C.cream, Fonts.medium), lp(top = dp(6)))
        remainingView = text("", 16f, C.muted, Fonts.light)
        card.addView(remainingView, lp(top = dp(6)))
        card.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(pill("■  " + S.stopAdhkar[l], filled = true) {
            AdhanService.stop(this, AdhanService.R_BUTTON); finish()
        }.apply { textSize = 20f; setPadding(dp(24), dp(18), dp(24), dp(18)) }, lp(top = dp(16)))
        if (prefs.clapToStop) card.addView(text(S.clapHint[l], 13f, C.muted, Fonts.light, Gravity.CENTER), lp(top = dp(8)))
        card.addView(pill(S.minimize[l]) { finish() }, lp(top = dp(10)))
        handler.removeCallbacksAndMessages(null)
        handler.post(tick)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        C.applyTo(this, prefs.theme)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(NotificationManager::class.java)?.cancel(41)

        val l = prefs.lang
        val prayer = intent.getIntExtra(AlarmScheduler.EXTRA_PRAYER, P.FAJR)
        val k = if (prayer == P.MAGHRIB) 1 else 0

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setOnClickListener { finish() }
        }
        card = card(20).apply {
            isClickable = true // Klicks auf die Karte schließen nicht
            background = roundRect(C.bgTop, dp(22).toFloat(), C.gold, dp(1))
        }
        card.addView(text(S.adhkarPickTitle[l], 22f, C.cream, Fonts.medium))
        card.addView(text(if (k == 0) S.adhkarPickMorning[l] else S.adhkarPickEvening[l], 15f, C.gold, Fonts.medium), lp(top = dp(2)))

        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun item(title: String, uri: String) {
            listCol.addView(text("▶   $title", 17f, C.cream, Fonts.regular).apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = roundRect(C.card, dp(14).toFloat(), C.cardStroke, dp(1))
                clickableRipple(dp(14).toFloat())
                setOnClickListener { startPlaying(prayer, title, uri) }
            }, lp(top = dp(8)))
        }
        // eigene Auswahl (falls gesetzt und nicht ohnehin eingebaut), dann alle eingebauten
        val builtins = AdhanPresets.adhkarBuiltin[k]
        val own = prefs.adhkarUri(k)
        if (own != null && builtins.none { it.uri(this) == own }) item(S.adhkarCustom[l] + ": " + (prefs.adhkarName(k) ?: ""), own)
        builtins.forEach { b -> item(b.title, b.uri(this)) }
        card.addView(ScrollView(this).apply { addView(listCol) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        countdown = text("", 13f, C.muted, Fonts.light)
        foot.addView(countdown, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(pill(S.close[l]) { finish() })
        card.addView(foot, lp(top = dp(12)))

        val sw = resources.configuration.screenWidthDp
        val sh = resources.configuration.screenHeightDp
        root.addView(card, FrameLayout.LayoutParams(dp(minOf(sw - 32, 560)), dp((sh * 0.8f).toInt().coerceAtMost(640)), Gravity.CENTER))
        setContentView(root)

        endAt = System.currentTimeMillis() + SHOW_MS
        handler.post(tick)
        val f = android.content.IntentFilter(AdhanActivity.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finishedReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(finishedReceiver, f)
    }

    override fun onDestroy() {
        try { unregisterReceiver(finishedReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
