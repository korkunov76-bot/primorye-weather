package com.primorye.weather

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object Net {
    val HOSTS = setOf("api.open-meteo.com", "marine-api.open-meteo.com", "api.met.no",
        "services.swpc.noaa.gov", "kp.gfz-potsdam.de")

    fun get(u: String): String {
        val url = URL(u)
        require(url.protocol == "https" && url.host in HOSTS) { "host not allowed" }
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 9000
        c.setRequestProperty("User-Agent",
            "PrimoryeWeather/1.0 github.com/korkunov76-bot/primorye-weather")
        try {
            if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

object WeatherAlarm {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val p = prefs(c)
        if (!p.getBoolean("enabled", true)) { am.cancel(pi); return }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Vladivostok"))
        cal.set(Calendar.HOUR_OF_DAY, p.getInt("h", 7))
        cal.set(Calendar.MINUTE, p.getInt("m", 30))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        val t = cal.timeInMillis
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            else
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        WeatherAlarm.schedule(c)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        WeatherAlarm.schedule(c) // сначала следующий будильник, чтобы цепочка не рвалась
        val pr = goAsync()
        val app = c.applicationContext
        Thread {
            try { notifyNow(app) } catch (e: Exception) { } finally { pr.finish() }
        }.start()
    }

    companion object {
        private const val LAT = 43.1155
        private const val LON = 131.8855
        private val MODELS = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless",
            "meteofrance_seamless", "cma_grapes_global", "gem_seamless")

        private fun wmo(x: Int): String = when {
            x == 0 -> "Ясно"; x == 1 -> "Малооблачно"; x == 2 -> "Переменная облачность"
            x == 3 -> "Пасмурно"; x == 45 || x == 48 -> "Туман"; x in 51..57 -> "Морось"
            x in 61..67 || x in 80..82 -> "Дождь"; x in 71..77 || x == 85 || x == 86 -> "Снег"
            x >= 95 -> "Гроза"; else -> ""
        }

        private fun sym(s: String): String = when {
            "thunder" in s -> "Гроза"; "snow" in s || "sleet" in s -> "Снег"
            "rain" in s || "shower" in s -> "Дождь"; "fog" in s -> "Туман"
            "partlycloudy" in s -> "Переменная облачность"; "fair" in s -> "Малооблачно"
            "clearsky" in s -> "Ясно"; "cloudy" in s -> "Пасмурно"; else -> ""
        }

        private fun trim(l: List<Double>): Double? {
            if (l.isEmpty()) return null
            var s = l.sorted()
            if (s.size >= 5) s = s.drop(1).dropLast(1)
            return s.average()
        }

        private fun fmt(tmin: Double, tmax: Double, w: Double, c: String): String =
            "Сегодня от ${Math.round(tmin)} до ${Math.round(tmax)}°, ветер до ${Math.round(w)} м/с. $c".trim()

        private fun omText(): String {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&hourly=temperature_2m,wind_speed_10m,weather_code&models=${MODELS.joinToString(",")}" +
                "&wind_speed_unit=ms&forecast_days=2&timezone=Asia%2FVladivostok"
            val j = JSONObject(Net.get(url)).getJSONObject("hourly")
            val h = Calendar.getInstance(TimeZone.getTimeZone("Asia/Vladivostok")).get(Calendar.HOUR_OF_DAY)
            val n = j.getJSONArray("time").length()
            fun at(v: String, i: Int): Double? = trim(MODELS.mapNotNull { m ->
                val a = j.optJSONArray(v + "_" + m)
                if (a != null && !a.isNull(i)) a.getDouble(i) else null
            })
            val idx = (h + 1) until minOf(n, h + 16)
            val temps = idx.mapNotNull { at("temperature_2m", it) }
            val winds = idx.mapNotNull { at("wind_speed_10m", it) }
            if (temps.isEmpty()) throw Exception("empty")
            val codes = idx.mapNotNull { i ->
                MODELS.mapNotNull { m ->
                    val a = j.optJSONArray("weather_code_$m")
                    if (a != null && !a.isNull(i)) a.getInt(i) else null
                }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            }
            val code = codes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            return fmt(temps.min(), temps.max(), winds.maxOrNull() ?: 0.0, if (code != null) wmo(code) else "")
        }

        private fun metText(): String {
            val ts = JSONObject(Net.get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$LAT&lon=$LON"))
                .getJSONObject("properties").getJSONArray("timeseries")
            val temps = ArrayList<Double>()
            val winds = ArrayList<Double>()
            for (i in 0 until minOf(15, ts.length())) {
                val d = ts.getJSONObject(i).getJSONObject("data").getJSONObject("instant").getJSONObject("details")
                temps.add(d.getDouble("air_temperature"))
                winds.add(d.getDouble("wind_speed"))
            }
            if (temps.isEmpty()) throw Exception("empty")
            val data = ts.getJSONObject(0).getJSONObject("data")
            val s = (data.optJSONObject("next_6_hours") ?: data.optJSONObject("next_1_hours"))
                ?.optJSONObject("summary")?.optString("symbol_code") ?: ""
            return fmt(temps.min(), temps.max(), winds.max(), sym(s))
        }

        private fun kpText(): String {
            val a: JSONArray = try {
                JSONArray(Net.get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"))
            } catch (e: Exception) {
                JSONArray(Net.get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"))
            }
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            val now = System.currentTimeMillis()
            val from = f.format(now - 3 * 3600_000L)
            val to = f.format(now + 24 * 3600_000L)
            val cur = f.format(now)
            var curK: Double? = null
            var maxK = 0.0
            for (i in 0 until a.length()) {
                val r = a.get(i)
                val t: String; val k: String
                if (r is JSONArray) { t = r.optString(0); k = r.optString(1) }
                else if (r is JSONObject) { t = r.optString("time_tag"); k = r.optString("kp") }
                else continue
                val kv = k.toDoubleOrNull() ?: continue
                val ts = t.replace('T', ' ')
                if (ts.isEmpty() || !ts[0].isDigit()) continue
                if (ts <= cur) curK = kv
                if (ts >= from && ts < to && kv > maxK) maxK = kv
            }
            if (curK == null) throw Exception("no kp")
            return "Магнитное поле: Kp ${curK}, максимум за сутки ${maxK}"
        }

        fun notifyNow(c: Context) {
            var om: String? = null
            var met: String? = null
            var kp: String? = null
            val ts = listOf(
                Thread { om = try { omText() } catch (e: Exception) { null } },
                Thread { met = try { metText() } catch (e: Exception) { null } },
                Thread { kp = try { kpText() } catch (e: Exception) { null } })
            ts.forEach { it.start() }
            val end = System.currentTimeMillis() + 9000
            ts.forEach { val left = end - System.currentTimeMillis(); if (left > 0) it.join(left) }
            val main = om ?: met?.let { "$it (MET Norway)" } ?: "Не удалось получить прогноз"
            val body = if (kp != null) main + "\n" + kp else main

            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "morning"
            val b: Notification.Builder
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(ch, "Утренний прогноз", NotificationManager.IMPORTANCE_DEFAULT))
                b = Notification.Builder(c, ch)
            } else {
                @Suppress("DEPRECATION")
                b = Notification.Builder(c)
            }
            val open = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.setSmallIcon(R.drawable.ic_launcher_new)
                .setContentTitle("Погода: Владивосток")
                .setContentText(main)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
            try { nm.notify(1, b.build()) } catch (e: Exception) { }
        }
    }
}
