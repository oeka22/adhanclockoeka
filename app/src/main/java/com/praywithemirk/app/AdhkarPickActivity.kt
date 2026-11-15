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
    private var endAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val left = ((endAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdown.text = S.closesIn[Prefs(this@AdhkarPickActivity).lang].format(left)
            if (left <= 0) finish() else handler.postDelayed(this, 500)
        }
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
        val card = card(20).apply {
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
                setOnClickListener {
                    AdhanService.playAdhkar(this@AdhkarPickActivity, prayer, uri)
                    finish()
                }
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
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
