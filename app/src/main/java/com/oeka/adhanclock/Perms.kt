package com.oeka.adhanclock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Alle Berechtigungen der App an einer Stelle – mit Status (✓ / •), Erklärung und Knopf.
 * Genutzt in Einstellungen › System und im Einrichtungs-Assistenten.
 */
object Perms {
    const val REQ = 77

    class Item(val title: T, val info: T, val ok: (Activity) -> Boolean, val fix: (Activity) -> Unit)

    private fun granted(a: Activity, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun items(): List<Item> {
        val list = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 33) list += Item(S.permNotif, S.permNotifInfo,
            { granted(it, Manifest.permission.POST_NOTIFICATIONS) },
            { it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ) })
        if (Build.VERSION.SDK_INT >= 31) list += Item(S.permExact, S.exactAlarmInfo,
            { AlarmScheduler.canScheduleExact(it) },
            { open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${it.packageName}"))) })
        if (Build.VERSION.SDK_INT >= 34) list += Item(S.permFull, S.fullScreenInfo,
            { it.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() },
            { open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${it.packageName}"))) })
        list += Item(S.permLocation, S.permLocationInfo,
            { LocationHelper.hasPermission(it) },
            { a -> askOrSettings(a, Manifest.permission.ACCESS_COARSE_LOCATION) })
        list += Item(S.permMic, S.permMicInfo,
            { granted(it, Manifest.permission.RECORD_AUDIO) },
            { a -> askOrSettings(a, Manifest.permission.RECORD_AUDIO) })
        list += Item(S.permBattery, S.batteryInfo,
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            { open(it, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        return list
    }

    /** Erst direkt fragen; wurde schon dauerhaft abgelehnt, die App-Einstellungen öffnen. */
    private fun askOrSettings(a: Activity, p: String) {
        val p2 = Prefs(a)
        if (p2.askedOnce(p) && !a.shouldShowRequestPermissionRationale(p)) {
            open(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        } else {
            p2.markAsked(p)
            a.requestPermissions(arrayOf(p), REQ)
        }
    }

    private fun open(a: Activity, i: Intent) {
        try { a.startActivity(i) } catch (_: Exception) {
            try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) } catch (_: Exception) {}
        }
    }

    /** Liste mit Status und Knopf je Berechtigung. */
    fun panel(a: Activity, box: LinearLayout) {
        val l = Prefs(a).lang
        items().forEachIndexed { i, it ->
            val ok = it.ok(a)
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(a.text((if (ok) "✓  " else "•  ") + it.title[l], 15f, if (ok) C.teal else C.cream, Fonts.medium))
            row.addView(a.text(it.info[l], 12f, C.muted, Fonts.light), lp(top = a.dp(2)))
            if (!ok) row.addView(a.pill(S.allow[l], filled = true) { it.fix(a) },
                lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = a.dp(6)))
            box.addView(row, lp(top = a.dp(if (i == 0) 0 else 14)))
        }
    }
}

/** Auswahl-Dialog für gefundene Mawaqit-Moscheen (Mehrfachauswahl → Kalender-Liste). */
object MosquePick {
    fun show(a: Activity, found: List<MosqueCalendar.Found>?, done: () -> Unit) {
        val l = Prefs(a).lang
        if (found == null) { Toast.makeText(a, S.mosqueSearchFailed[l], Toast.LENGTH_LONG).show(); return }
        if (found.isEmpty()) { Toast.makeText(a, S.mosqueNoneFound[l], Toast.LENGTH_LONG).show(); return }
        val labels = found.map { f ->
            f.name + (if (f.place.isNotBlank()) " – " + f.place else "") + (if (f.km >= 0) "  ·  %.1f km".format(f.km) else "")
        }.toTypedArray()
        val checked = BooleanArray(found.size) { true }
        AlertDialog.Builder(a, if (C.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(S.mosquePickTitle[l])
            .setMultiChoiceItems(labels, checked) { _, w, c -> checked[w] = c }
            .setNegativeButton(S.cancel[l], null)
            .setPositiveButton(S.mosqueAdd[l]) { _, _ ->
                val chosen = found.filterIndexed { i, _ -> checked[i] }
                chosen.forEach { MosqueCalendar.addSaved(a, it.name, it.url) }
                val prefs = Prefs(a)
                // noch kein Kalender aktiv: den nächstgelegenen gleich übernehmen
                if (chosen.isNotEmpty() && MosqueCalendar.load(a) == null) {
                    Toast.makeText(a, S.downloading[l], Toast.LENGTH_SHORT).show()
                    MosqueCalendar.fetch(a, chosen.first().url, chosen.first().name) { n ->
                        if (n != null) { prefs.useMosque = true; AlarmScheduler.reschedule(a); Toast.makeText(a, S.mosqueLoaded[l].format(n), Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(a, S.mosqueFailed[l], Toast.LENGTH_LONG).show()
                        done()
                    }
                } else done()
            }
            .show()
    }
}
