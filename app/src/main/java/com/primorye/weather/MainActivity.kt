package com.primorye.weather

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        private const val REMOTE_URL = ""
        private const val LOCAL_URL = "file:///android_asset/forecast.html"
        private val ALLOWED_HOSTS = setOf("api.open-meteo.com", "marine-api.open-meteo.com")
    }

    private lateinit var web: WebView

    inner class Bridge {
        @JavascriptInterface
        fun notifEnabled(): Boolean {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            return nm.areNotificationsEnabled()
        }

        @JavascriptInterface
        fun exactAlarms(): Boolean {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        }

        @JavascriptInterface
        fun openNotifSettings() {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        @JavascriptInterface
        fun openAlarmSettings() {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }

        // Запрос погоды средствами Android (обходит ограничения WebView).
        // Разрешены только адреса Open-Meteo по https.
        @JavascriptInterface
        fun httpGet(id: Int, url: String) {
            Thread {
                val result: String = try {
                    val u = URL(url)
                    if (u.protocol != "https" || u.host !in ALLOWED_HOSTS) {
                        throw SecurityException("адрес не разрешён")
                    }
                    val c = u.openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 25000
                    c.setRequestProperty("User-Agent", "PrimoryeWeather/1.0")
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                    JSONObject().put("status", code).put("body", body).toString()
                } catch (e: Exception) {
                    JSONObject().put("status", 0).put("error", e.toString()).toString()
                }
                runOnUiThread {
                    web.evaluateJavascript("window.__nativeCb($id, ${JSONObject.quote(result)})", null)
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && REMOTE_URL.isNotEmpty()) view.loadUrl(LOCAL_URL)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    view?.evaluateJavascript(
                        "if(confirm('Для уведомлений ровно в 6:00/7:30 нужны точные будильники. Включить?')){AndroidBridge.openAlarmSettings()}",
                        null
                    )
                }
            }
        }
        web.loadUrl(if (REMOTE_URL.isNotEmpty()) REMOTE_URL else LOCAL_URL)
        setContentView(web)

        WeatherAlarm.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
