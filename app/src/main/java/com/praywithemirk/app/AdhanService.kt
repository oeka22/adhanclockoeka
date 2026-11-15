package com.praywithemirk.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Spielt den Gebetsruf als Vordergrund-Dienst ab (auch bei gesperrtem Bildschirm).
 * Ohne eigene Audiodatei wird der Standard-Weckton ca. 30 Sekunden lang wiederholt.
 * Nach Fajr und Maghrib kann direkt im Anschluss ein Adhkar-Audio (Morgen/Abend) folgen.
 * Das Stoppen per 3× Klatschen läuft hier im Dienst – so funktioniert es auch, nachdem
 * der Glocken-Bildschirm nach 10 Sekunden geschlossen wurde.
 */
class AdhanService : Service() {

    companion object {
        const val ACTION_PLAY = "com.praywithemirk.app.action.PLAY"
        const val ACTION_STOP = "com.praywithemirk.app.action.STOP"
        const val ACTION_CLAP_ON = "com.praywithemirk.app.action.CLAP_ON"
        /** Broadcast an die sichtbaren Seiten: Anzahl erkannter Klatscher (Extra „n“). */
        const val ACTION_CLAP_COUNT = "com.praywithemirk.app.action.CLAP_COUNT"
        const val EXTRA_REASON = "reason"

        /** true, solange ein Gebetsruf oder das anschließende Adhkar läuft. */
        @Volatile var playing = false
        /** true, solange das Adhkar-Audio (nach dem Gebetsruf) läuft. */
        @Volatile var adhkarPhase = false
        /** true, solange die Klatsch-Erkennung im Dienst aktiv ist. */
        @Volatile var clapActive = false
        /** Klatsch-Erkennung konnte für diesen Gebetsruf nicht gestartet werden (nicht ständig neu versuchen). */
        @Volatile var clapUnavailable = false

        private const val MAX_DURATION_MS = 7 * 60_000L
        private const val MAX_ADHKAR_MS = 60 * 60_000L
        private const val DEFAULT_TONE_MS = 30_000L

        // Gründe, warum ein Gebetsruf endet (für die Anzeige „Letzter Gebetsruf“ in den Einstellungen)
        const val R_END = "end"; const val R_BUTTON = "button"; const val R_CLAP = "clap"
        const val R_NOTIFICATION = "notification"; const val R_ERROR = "error"; const val R_MAX = "max"; const val R_TONE = "tone"

        fun start(ctx: Context, prayer: Int, fromAlarm: Boolean) {
            val i = Intent(ctx, AdhanService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(AlarmScheduler.EXTRA_PRAYER, prayer)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // z. B. ForegroundServiceStartNotAllowedException, wenn kein exakter Alarm erlaubt ist
                if (fromAlarm) Notifications.showPrayerWithSound(ctx, prayer)
            }
        }

        fun stop(ctx: Context, reason: String = R_BUTTON) {
            ctx.startService(Intent(ctx, AdhanService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, reason))
        }

        /**
         * Von einer sichtbaren Seite aufrufen: startet die Klatsch-Erkennung im Dienst.
         * Android erlaubt das Mikrofon nur, wenn die App dabei im Vordergrund ist; danach
         * darf der Dienst weiter zuhören, auch wenn die Glocke geschlossen ist.
         */
        fun enableClap(ctx: Context) {
            if (!playing || clapActive || clapUnavailable) return
            val p = Prefs(ctx)
            if (!p.clapToStop || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try { ctx.startService(Intent(ctx, AdhanService::class.java).setAction(ACTION_CLAP_ON)) } catch (_: Exception) {}
        }
    }

    private var player: MediaPlayer? = null
    private var prayer = P.DHUHR
    private var focusRequest: AudioFocusRequest? = null
    private var savedAlarmVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val maxRunnable = Runnable { onPhaseEnd(R_MAX) }
    private val toneRunnable = Runnable { onPhaseEnd(R_TONE) }
    private var startedAt = 0L
    private var clap: ClapDetector? = null
    private var micGranted = false
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                onPhaseEnd(intent.getStringExtra(EXTRA_REASON) ?: R_NOTIFICATION)
                return START_NOT_STICKY
            }
            ACTION_CLAP_ON -> {
                if (playing) startClap()
                return START_NOT_STICKY
            }
        }
        prayer = intent?.getIntExtra(AlarmScheduler.EXTRA_PRAYER, P.DHUHR) ?: P.DHUHR
        finished = false
        playing = true
        adhkarPhase = false
        clapUnavailable = false
        Notifications.ensureChannels(this)
        startFg()
        startedAt = SystemClock.elapsedRealtime()
        play()
        // Ist die App gerade sichtbar (z. B. als Wanduhr), den Adhan-Bildschirm direkt öffnen.
        if (App.visibleActivities > 0) {
            try { startActivity(adhanScreenIntent()) } catch (_: Exception) {}
        }
        return START_NOT_STICKY
    }

    /** Vordergrund-Dienst starten bzw. aktualisieren – mit Mikrofon, sobald die Klatsch-Erkennung erlaubt wurde. */
    private fun startFg(): Boolean {
        val n = buildNotification()
        return try {
            when {
                Build.VERSION.SDK_INT >= 30 && micGranted ->
                    startForeground(Notifications.ID_ADHAN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(Notifications.ID_ADHAN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else -> startForeground(Notifications.ID_ADHAN, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Klatsch-Erkennung im Dienst starten (nur auf Anforderung einer sichtbaren Seite). */
    private fun startClap() {
        if (clapActive || finished) return
        val prefs = Prefs(this)
        if (!prefs.clapToStop || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micGranted = true
        if (!startFg()) {
            // Mikrofon-Vordergrunddienst nicht erlaubt: wieder nur Wiedergabe
            micGranted = false
            clapUnavailable = true
            startFg()
            return
        }
        clapActive = true
        newDetector()
    }

    private fun newDetector() {
        clap?.stop()
        clap = ClapDetector(
            onClap = { n -> sendBroadcast(Intent(ACTION_CLAP_COUNT).setPackage(packageName).putExtra("n", n)) },
            onTriple = { onPhaseEnd(R_CLAP) }
        ).also { it.start() }
    }

    private fun adhanScreenIntent(): Intent =
        Intent(this, AdhanActivity::class.java)
            .putExtra(AdhanActivity.EXTRA_PRAYER, prayer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun buildNotification(): Notification {
        val l = Prefs(this).lang
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AdhanService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, R_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(null, S.stop[l], stopPi).build()
        val screenPi = PendingIntent.getActivity(
            this, 2, adhanScreenIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notifications.builder(this, Notifications.CH_ADHAN)
            .setContentTitle(S.timeFor[l].format(S.prayerNames[prayer][l]) + "  ·  " + S.prayerArabic[prayer])
            .setContentText(if (adhkarPhase) S.adhkarPlaying[l] else S.adhanPlaying[l])
            .setContentIntent(screenPi)
            .setDeleteIntent(stopPi)
            .addAction(stopAction)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (!adhkarPhase) b.setFullScreenIntent(screenPi, true)
        return b.build()
    }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun play() {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        val prefs = Prefs(this)
        val custom = prefs.soundFor(prayer)
        requestFocus(attrs)
        applyVolume(prefs.volume(prayer))

        val ok = custom != null && tryPlay(Uri.parse(custom), loop = false)
        if (!ok) {
            val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (def == null || !tryPlay(def, loop = true)) {
                onPhaseEnd(R_ERROR); return
            }
            handler.postDelayed(toneRunnable, DEFAULT_TONE_MS)
        }
        handler.postDelayed(maxRunnable, MAX_DURATION_MS)
    }

    private fun tryPlay(uri: Uri, loop: Boolean): Boolean = try {
        val mp = MediaPlayer()
        mp.setAudioAttributes(attrs)
        mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setDataSource(applicationContext, uri)
        mp.isLooping = loop
        mp.setOnCompletionListener { onPhaseEnd(R_END) }
        mp.setOnErrorListener { _, what, extra -> onPhaseEnd("$R_ERROR $what/$extra"); true }
        mp.prepare()
        mp.start()
        player = mp
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Ende eines Abschnitts (Gebetsruf oder Adhkar). Nach dem Gebetsruf folgt – falls eingestellt –
     * direkt das Adhkar; beim vorzeitigen Stopp nur, wenn „Adhkar auch nach Stopp“ an ist.
     */
    private fun onPhaseEnd(reason: String) {
        if (finished) return
        if (!adhkarPhase) {
            saveEnd(reason)
            val prefs = Prefs(this)
            val adhkar = prefs.adhkarFor(prayer)
            val stoppedByUser = reason == R_BUTTON || reason == R_CLAP || reason == R_NOTIFICATION
            val natural = reason == R_END || reason == R_TONE || reason == R_MAX
            if (adhkar != null && prefs.adhkarAudioOn && (natural || (stoppedByUser && prefs.adhkarAfterStop))) {
                startAdhkar(adhkar)
                return
            }
            finishAll()
        } else {
            finishAll()
        }
    }

    private fun startAdhkar(uri: String) {
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        adhkarPhase = true
        startFg() // Benachrichtigung: „Adhkar läuft“
        applyVolume(Prefs(this).adhkarVolume(if (prayer == P.FAJR) 0 else 1))
        if (!tryPlay(Uri.parse(uri), loop = false)) { finishAll(); return }
        handler.postDelayed(maxRunnable, MAX_ADHKAR_MS)
        // Klatschen stoppt auch das Adhkar
        if (clapActive) newDetector()
    }

    private fun saveEnd(reason: String) {
        val secs = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0
        Prefs(this).lastAdhanEnd = "$reason|$secs|${System.currentTimeMillis()}"
    }

    /** Setzt die Wecker-Lautstärke für die Dauer des Gebetsrufs auf den gewünschten Anteil. */
    private fun applyVolume(percent: Int) {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (savedAlarmVolume < 0) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = if (percent <= 0) 0 else maxOf(1, Math.round(max * percent / 100f))
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (_: Exception) {}
    }

    private fun restoreVolume() {
        if (savedAlarmVolume < 0) return
        try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) } catch (_: Exception) {}
        savedAlarmVolume = -1
    }

    private fun requestFocus(attrs: AudioAttributes) {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun stopPlayer() {
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    private fun finishAll() {
        if (finished) return
        finished = true
        playing = false
        adhkarPhase = false
        clap?.stop(); clap = null
        clapActive = false
        micGranted = false
        sendBroadcast(Intent(AdhanActivity.ACTION_FINISHED).setPackage(packageName))
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        restoreVolume()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Notifications.showAfterAdhan(this, prayer)
        stopSelf()
    }

    override fun onDestroy() {
        playing = false
        adhkarPhase = false
        clap?.stop(); clap = null
        clapActive = false
        restoreVolume()
        handler.removeCallbacksAndMessages(null)
        stopPlayer()
        super.onDestroy()
    }
}
