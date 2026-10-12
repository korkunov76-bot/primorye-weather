package com.primorye.weather

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#0C1A24")
        val frame = FrameLayout(this)
        frame.setBackgroundColor(bg)
        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        WeatherAlarm.schedule(this)
        web.loadUrl("file:///android_asset/forecast.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface
        fun notifEnabled(): Boolean =
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

        @JavascriptInterface
        fun exactAlarms(): Boolean =
            if (Build.VERSION.SDK_INT >= 31)
                getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true

        @JavascriptInterface
        fun openNotifSettings() {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }

        @JavascriptInterface
        fun openAlarmSettings() {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
        }

        @JavascriptInterface
        fun openAppSettings() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }

        @JavascriptInterface
        fun appVersion(): String =
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""

        @JavascriptInterface
        fun testNotification() {
            val ctx = applicationContext
            Thread { try { AlarmReceiver.notifyNow(ctx) } catch (e: Exception) { } }.start()
        }

        @JavascriptInterface
        fun getNotifyConfig(): String {
            val p = WeatherAlarm.prefs(applicationContext)
            return JSONObject().put("enabled", p.getBoolean("enabled", true))
                .put("h", p.getInt("h", 7)).put("m", p.getInt("m", 30)).toString()
        }

        @JavascriptInterface
        fun setNotifyConfig(enabled: Boolean, h: Int, m: Int) {
            WeatherAlarm.prefs(applicationContext).edit()
                .putBoolean("enabled", enabled).putInt("h", h).putInt("m", m).apply()
            WeatherAlarm.schedule(applicationContext)
        }

        @JavascriptInterface
        fun httpGet(url: String, id: Int) {
            Thread {
                var ok = false
                var out: String
                try { out = Net.get(url); ok = true } catch (e: Exception) { out = e.toString() }
                val js = "window.__cb($id,$ok,${JSONObject.quote(out)})"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }.start()
        }
    }
}
