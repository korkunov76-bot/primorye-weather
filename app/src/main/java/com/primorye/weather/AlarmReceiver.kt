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
import org.json.JSONObject
import java.net.URL
import java.util.Calendar
import java.util.Collections

object WeatherAlarm {
    const val CH = "weather_morning"

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        set(am, ctx, 6, 0, 1001, weekend = false)
        set(am, ctx, 7, 30, 1002, weekend = true)
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
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
        } else {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) WeatherAlarm.schedule(ctx)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val weekend = intent.getBooleanExtra("weekend", false)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
        if (weekend != isWeekend) return

        val pending = goAsync()
        Thread {
            val text = try {
                fetchConsensus()
            } catch (e: Exception) {
                "Прогноз не обновился — откройте приложение"
            }
            show(ctx, text)
            WeatherAlarm.schedule(ctx)
            pending.finish()
        }.start()
    }

    private val MODELS = listOf(
        "ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless",
        "meteofrance_seamless", "cma_grapes_global", "gem_seamless"
    )

    private fun fetchConsensus(): String {
        val base = "https://api.open-meteo.com/v1/forecast?latitude=43.1155&longitude=131.8855" +
                "&timezone=Asia%2FVladivostok&wind_speed_unit=ms" +
                "&current=temperature_2m,weather_code,wind_speed_10m,wind_gusts_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&forecast_days=1&models="
        val ok = Collections.synchronizedList(mutableListOf<JSONObject>())
        val threads = MODELS.map { m ->
            Thread {
                try {
                    val j = JSONObject(URL(base + m).readText())
                    if (j.has("current")) ok.add(j)
                } catch (e: Exception) { }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(8000) }
        if (ok.isEmpty()) throw IllegalStateException("no models")

        fun nums(f: (JSONObject) -> Double?): List<Double> = ok.mapNotNull(f)
        fun cons(vals: List<Double>): Double? {
            val v = vals.sorted()
            if (v.isEmpty()) return null
            val t = if (v.size > 2) v.subList(1, v.size - 1) else v
            return t.average()
        }

        val t = cons(nums { it.getJSONObject("current").optDouble("temperature_2m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val wind = cons(nums { it.getJSONObject("current").optDouble("wind_speed_10m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val gust = cons(nums { it.getJSONObject("current").optDouble("wind_gusts_10m", Double.NaN).takeIf { x -> !x.isNaN() } })
        val code = wmode(ok.map { it.getJSONObject("current").optInt("weather_code", -1) }.filter { it >= 0 })
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
        return "Сейчас %s°, %s%s. Сегодня %s…%s°%s.".format(
            r(t), desc(code), windTxt, r(tmin), r(tmax), rainTxt
        )
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
