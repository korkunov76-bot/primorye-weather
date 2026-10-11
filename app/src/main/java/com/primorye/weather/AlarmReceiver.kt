package com.primorye.weather

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

object WeatherAlarm {
    const val CH = "weather_morning"

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sp = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        if (sp.getBoolean("notify_on", true)) {
            val wd = sp.getInt("wd_min", 360)
            val we = sp.getInt("we_min", 450)
            set(am, ctx, wd / 60, wd % 60, 1001, weekend = false)
            set(am, ctx, we / 60, we % 60, 1002, weekend = true)
        } else {
            cancel(am, ctx, 1001)
            cancel(am, ctx, 1002)
        }
    }

    private fun cancel(am: AlarmManager, ctx: Context, req: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun set(am: AlarmManager, ctx: Context, h: Int, m: Int, req: Int, weekend: Boolean) {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("weekend", weekend)
        val pi = PendingIntent.getBroadcast(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // До Android 12 точные будильники разрешены без особого доступа;
        // на Android 12+ нужен доступ, иначе ставим неточный (но работающий) будильник.
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // после перезагрузки телефона и после обновления приложения будильники нужно поставить заново
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WeatherAlarm.schedule(ctx)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Сразу планируем следующий запуск: цепочка не прервётся, даже если сеть зависнет
        // или сегодня сработал будильник «не того» дня недели.
        WeatherAlarm.schedule(ctx)

        val weekend = intent.getBooleanExtra("weekend", false)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
        if (weekend != isWeekend) return

        val pending = goAsync()
        Thread {
            try {
                val text = try {
                    fetchConsensus()
                } catch (e: Exception) {
                    "Прогноз не обновился — откройте приложение"
                }
                show(ctx, text)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private val MODELS = listOf(
        "ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless",
        "meteofrance_seamless", "cma_grapes_global", "gem_seamless"
    )

    // Запрос с таймаутами: раньше без них запрос мог зависнуть надолго.
    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "PrimoryeWeather/1.0 github.com/korkunov76-bot/primorye-weather")
            val code = c.responseCode
            if (code < 200 || code > 299) throw IllegalStateException("HTTP " + code)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun fetchConsensus(): String {
        val base = "https://api.open-meteo.com/v1/forecast?latitude=43.1155&longitude=131.8855" +
                "&timezone=Asia%2FVladivostok&wind_speed_unit=ms" +
                "&current=temperature_2m,weather_code,wind_speed_10m,wind_gusts_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&forecast_days=1&models="
        val ok = Collections.synchronizedList(mutableListOf<JSONObject>())
        val metRef = AtomicReference<String?>(null)
        val kpRef = AtomicReference<String?>(null)

        val threads = MODELS.map { m ->
            Thread {
                try {
                    val j = JSONObject(httpGet(base + m))
                    if (j.has("current")) ok.add(j)
                } catch (e: Exception) { }
            }
        }.toMutableList()
        // Параллельно запрашиваем MET Norway: он открывается и без VPN.
        threads.add(Thread {
            try {
                metRef.set(fetchMet())
            } catch (e: Exception) { }
        })
        threads.add(Thread {
            try {
                kpRef.set(fetchKp())
            } catch (e: Exception) { }
        })
        threads.forEach { it.start() }
        // Общий срок ожидания 9 секунд, чтобы не упереться в лимит времени получателя.
        val deadline = System.currentTimeMillis() + 9000
        threads.forEach {
            val left = deadline - System.currentTimeMillis()
            if (left > 0) it.join(left)
        }

        val results = synchronized(ok) { ok.toList() }
        val kpLine = kpRef.get()
        val withKp = { s: String -> if (kpLine == null) s else s + "\n" + kpLine }
        if (results.isEmpty()) {
            val met = metRef.get()
            if (met != null) return withKp(met)
            throw IllegalStateException("no models")
        }

        fun nums(f: (JSONObject) -> Double?): List<Double> = results.mapNotNull(f)
        fun cons(vals: List<Double>): Double? {
            val v = vals.sorted()
            if (v.isEmpty()) return null
            val t = if (v.size > 2) v.subList(1, v.size - 1) else v
            return t.average()
        }

        val t = cons(nums { it.getJSONObject("current").optDouble("temperature_2m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val wind = cons(nums { it.getJSONObject("current").optDouble("wind_speed_10m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val gust = cons(nums { it.getJSONObject("current").optDouble("wind_gusts_10m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val code = wmode(results.map { it.getJSONObject("current").optInt("weather_code", -1) }.filter { it >= 0 })
        val tmin = cons(nums { it.getJSONObject("daily").getJSONArray("temperature_2m_min").optDouble(0, Double.NaN).takeIf { x -> !x.isNaN() } })
        val tmax = cons(nums { it.getJSONObject("daily").getJSONArray("temperature_2m_max").optDouble(0, Double.NaN).takeIf { x -> !x.isNaN() } })
        val rain = cons(nums { it.getJSONObject("daily").getJSONArray("precipitation_sum").optDouble(0, Double.NaN).takeIf { x -> !x.isNaN() } })

        val r = { v: Double? -> if (v == null) "—" else "%+d".format(Math.round(v).toInt()) }
        val windTxt = if (wind == null) "" else ", ветер %d м/с".format(Math.round(wind).toInt()) +
                (if (gust != null) ", пор. %d".format(Math.round(gust).toInt()) else "")
        val rainTxt = when {
            rain == null -> ""
            rain >= 0.1 -> ", осадки %.1f мм".format(rain)
            else -> ", без осадков"
        }
        return withKp(
            "Сейчас %s°, %s%s. Сегодня %s…%s°%s.".format(
                r(t), desc(code), windTxt, r(tmin), r(tmax), rainTxt
            )
        )
    }

    // Индекс Kp (NOAA SWPC): текущее значение и максимум на ближайшие сутки.
    private fun fetchKp(): String? {
        val arr = JSONArray(httpGet("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"))
        if (arr.length() == 0) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        var tIdx = 0
        var kIdx = 1
        var start = 0
        val first = arr.get(0)
        if (first is JSONArray) {
            for (c in 0 until first.length()) {
                val name = first.optString(c).lowercase(Locale.ROOT)
                if (name == "time_tag") tIdx = c
                if (name == "kp") kIdx = c
            }
            start = 1
        }
        val now = System.currentTimeMillis()
        val slot = 3 * 3600 * 1000L
        var cur = Double.NaN
        var mx = Double.NaN
        for (i in start until arr.length()) {
            val row = arr.get(i)
            var ts = ""
            var kv = Double.NaN
            if (row is JSONArray) {
                ts = row.optString(tIdx)
                kv = row.optString(kIdx).toDoubleOrNull() ?: Double.NaN
            } else if (row is JSONObject) {
                ts = row.optString("time_tag")
                kv = row.optString("kp").toDoubleOrNull() ?: Double.NaN
            }
            if (ts.length < 19 || kv.isNaN()) continue
            val d = fmt.parse(ts.substring(0, 19).replace(' ', 'T')) ?: continue
            val t = d.time
            if (t <= now && now < t + slot) cur = kv
            if (t + slot > now && t < now + 24 * 3600 * 1000L) {
                if (mx.isNaN() || kv > mx) mx = kv
            }
        }
        val curL = if (cur.isNaN()) 0 else kpLevel(cur)
        val maxL = if (mx.isNaN()) 0 else kpLevel(mx)
        val curS = String.format(Locale.US, "%.1f", cur)
        val maxS = String.format(Locale.US, "%.1f", mx)
        return when {
            curL >= 1 -> "🧲 Магнитная буря G" + curL + " (" + kpWord(curL) + "), Kp " + curS +
                    (if (maxL > curL) ", ожидается до G" + maxL else "")
            maxL >= 1 -> "🧲 Ожидается магнитная буря G" + maxL + " (Kp до " + maxS + ")"
            !cur.isNaN() -> "Магнитное поле спокойно (Kp " + curS + ")"
            else -> null
        }
    }

    private fun kpLevel(k: Double): Int = when {
        k >= 8.99 -> 5
        k >= 7.99 -> 4
        k >= 6.99 -> 3
        k >= 5.99 -> 2
        k >= 4.99 -> 1
        else -> 0
    }

    private fun kpWord(l: Int): String = when (l) {
        5 -> "экстремальная"
        4 -> "жёсткая"
        3 -> "сильная"
        2 -> "умеренная"
        else -> "малая"
    }

    // Запасной источник: MET Norway (без выбора моделей, одна модель).
    private fun fetchMet(): String {
        val j = JSONObject(httpGet("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=43.1155&lon=131.8855"))
        val ts = j.getJSONObject("properties").getJSONArray("timeseries")
        if (ts.length() == 0) throw IllegalStateException("no data")

        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance()
        val todayKey = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)

        var tmin = Double.NaN
        var tmax = Double.NaN
        var rain = 0.0
        for (i in 0 until ts.length()) {
            val e = ts.getJSONObject(i)
            val date = fmt.parse(e.getString("time")) ?: continue
            val cal = Calendar.getInstance()
            cal.time = date
            val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (key != todayKey) continue
            val data = e.getJSONObject("data")
            val tv = data.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", Double.NaN)
            if (!tv.isNaN()) {
                if (tmin.isNaN() || tv < tmin) tmin = tv
                if (tmax.isNaN() || tv > tmax) tmax = tv
            }
            val n1 = data.optJSONObject("next_1_hours")
            if (n1 != null) {
                val det = n1.optJSONObject("details")
                if (det != null) rain += det.optDouble("precipitation_amount", 0.0)
            }
        }

        val first = ts.getJSONObject(0).getJSONObject("data")
        val details = first.getJSONObject("instant").getJSONObject("details")
        val cur = details.optDouble("air_temperature", Double.NaN)
        val windMs = details.optDouble("wind_speed", Double.NaN)
        var sym = ""
        val n1 = first.optJSONObject("next_1_hours")
        if (n1 != null) {
            val sm = n1.optJSONObject("summary")
            if (sm != null) sym = sm.optString("symbol_code", "")
        }

        val fmtT = { v: Double -> if (v.isNaN()) "—" else "%+d".format(Math.round(v).toInt()) }
        val windTxt = if (windMs.isNaN()) "" else ", ветер %d м/с".format(Math.round(windMs).toInt())
        val rainTxt = if (rain >= 0.1) ", осадки %.1f мм".format(rain) else ", без осадков"
        return "Сейчас %s°, %s%s. Сегодня %s…%s°%s. (MET Norway)".format(
            fmtT(cur), descMet(sym), windTxt, fmtT(tmin), fmtT(tmax), rainTxt
        )
    }

    private fun descMet(s: String): String = when {
        s.contains("thunder") -> "гроза"
        s.contains("sleet") -> "дождь со снегом"
        s.contains("snow") -> "снег"
        s.contains("showers") -> "ливень"
        s.contains("rain") -> "дождь"
        s.contains("fog") -> "туман"
        s.startsWith("clearsky") -> "ясно"
        s.startsWith("fair") -> "малооблачно"
        s.startsWith("partlycloudy") -> "переменная облачность"
        else -> "пасмурно"
    }

    private fun wmode(codes: List<Int>): Int {
        if (codes.isEmpty()) return -1
        val f = codes.groupingBy { it }.eachCount()
        val best = f.maxWith(compareBy({ it.value }, { sev(it.key) }))
        return best.key
    }

    private fun sev(c: Int) = when {
        c <= 3 -> 0
        c == 45 || c == 48 -> 1
        c >= 95 -> 3
        else -> 2
    }

    private fun desc(c: Int): String = when {
        c == 0 -> "ясно"
        c in 1..2 -> "переменная облачность"
        c == 3 -> "пасмурно"
        c == 45 || c == 48 -> "туман"
        c in 51..57 -> "морось"
        c in 61..67 -> "дождь"
        c in 71..77 -> "снег"
        c in 80..82 -> "ливень"
        c >= 95 -> "гроза"
        else -> "облачно"
    }

    private fun show(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(WeatherAlarm.CH, "Утренняя погода", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, WeatherAlarm.CH)
                 else Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Погода Владивостока")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        nm.notify(1, b.build())
    }
}
