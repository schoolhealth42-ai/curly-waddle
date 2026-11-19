package com.goldlive.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

const val OZ_GRAMS = 31.1034768

enum class Cur(val code: String, val label: String, val symbol: String) {
    USD("USD", "دولار", "$"),
    SAR("SAR", "ريال", "ر.س"),
    SYP("SYP", "ليرة", "ل.س");

    companion object {
        fun of(code: String?): Cur = values().firstOrNull { it.code == code } ?: USD
    }
}

/** مسكوكات ووحدات ذهبية شائعة في السعودية وسوريا */
data class Coin(val name: String, val grams: Double, val karat: Int)

val COINS = listOf(
    Coin("الليرة الذهبية السورية (8 غ - عيار 21)", 8.0, 21),
    Coin("الليرة الرشادية (7.2 غ - عيار 22)", 7.216, 22),
    Coin("الليرة الإنكليزية (7.98 غ - عيار 22)", 7.988, 22),
    Coin("سبيكة 10 غرام (عيار 24)", 10.0, 24),
    Coin("الكيلو (عيار 24)", 1000.0, 24)
)

/** إعدادات المستخدم */
class AppPrefs(ctx: Context) {
    val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun num(key: String, def: Double): Double =
        p.getString(key, null)?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: def

    private fun numOrNull(key: String): Double? =
        p.getString(key, null)?.replace(',', '.')?.trim()?.toDoubleOrNull()

    val refreshSeconds: Long get() = p.getString("refresh_interval", "10")?.toLongOrNull() ?: 10L
    val theme: String get() = p.getString("theme", "dark") ?: "dark"
    val sypSource: String get() = p.getString("syp_source", "api") ?: "api"
    val sypManual: Double? get() = numOrNull("syp_manual_rate")
    val sypOld: Boolean get() = p.getString("syp_denomination", "new") == "old"
    val karats: List<Int>
        get() = (p.getStringSet("karats", setOf("24", "22", "21", "18")) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.sortedDescending()
    val spread: Double get() = num("spread_percent", 0.0)
    val vat: Double get() = num("vat_percent", 15.0)
    val decimals: Int get() = p.getString("decimals", "2")?.toIntOrNull() ?: 2
    val arabicDigits: Boolean get() = p.getString("digits", "latin") == "arabic"
    val keepScreenOn: Boolean get() = p.getBoolean("keep_screen_on", false)
    val showSilver: Boolean get() = p.getBoolean("show_silver", true)
    val showCoins: Boolean get() = p.getBoolean("show_coins", true)
    val flashPrices: Boolean get() = p.getBoolean("flash_prices", true)
    val alertsEnabled: Boolean get() = p.getBoolean("alerts_enabled", false)
    val alertAbove: Double? get() = numOrNull("alert_above")
    val alertBelow: Double? get() = numOrNull("alert_below")

    var chartRange: String
        get() = p.getString("chart_range", "1d") ?: "1d"
        set(v) = p.edit().putString("chart_range", v).apply()
    var chartCurrency: Cur
        get() = Cur.of(p.getString("chart_currency", "USD"))
        set(v) = p.edit().putString("chart_currency", v.code).apply()
    var chartUnitGram: Boolean
        get() = p.getBoolean("chart_unit_gram", false)
        set(v) = p.edit().putBoolean("chart_unit_gram", v).apply()

    fun clear(key: String) = p.edit().remove(key).apply()

    /** سعر الدولار بالليرة حسب المصدر المختار والفئة (جديدة/قديمة) */
    fun sypRate(apiNew: Double?): Double? {
        val base = if (sypSource == "manual") (sypManual ?: apiNew) else apiNew
        return base?.let { if (sypOld) it * 100.0 else it }
    }

    fun rate(cur: Cur, sar: Double, sypApiNew: Double?): Double? = when (cur) {
        Cur.USD -> 1.0
        Cur.SAR -> sar
        Cur.SYP -> sypRate(sypApiNew)
    }

    fun format(v: Double, cur: Cur): String {
        val dec = if (cur == Cur.SYP && v >= 1000) 0 else decimals
        return Fmt.num(v, dec, arabicDigits)
    }
}

/** آخر أسعار محفوظة للعمل دون اتصال */
class Cache(ctx: Context) {
    private val p = ctx.getSharedPreferences("price_cache", Context.MODE_PRIVATE)

    private fun getD(k: String): Double? = p.getString(k, null)?.toDoubleOrNull()
    private fun putD(k: String, v: Double?) = p.edit().putString(k, v?.toString()).apply()

    var gold: Double? get() = getD("gold"); set(v) = putD("gold", v)
    var silver: Double? get() = getD("silver"); set(v) = putD("silver", v)
    var changePct: Double? get() = getD("change"); set(v) = putD("change", v)
    var sar: Double get() = getD("sar") ?: 3.75; set(v) = putD("sar", v)
    var sypNew: Double? get() = getD("syp"); set(v) = putD("syp", v)
    var time: Long get() = p.getLong("time", 0L); set(v) = p.edit().putLong("time", v).apply()
    var source: String get() = p.getString("source", "") ?: ""; set(v) = p.edit().putString("source", v).apply()
    var fxSource: String get() = p.getString("fx_source", "") ?: ""; set(v) = p.edit().putString("fx_source", v).apply()
    var fxTime: Long get() = p.getLong("fx_time", 0L); set(v) = p.edit().putLong("fx_time", v).apply()
}

object Fmt {
    fun num(v: Double, dec: Int, arabic: Boolean): String {
        val nf = NumberFormat.getNumberInstance(if (arabic) Locale("ar", "EG") else Locale.US)
        nf.minimumFractionDigits = dec
        nf.maximumFractionDigits = dec
        return nf.format(v)
    }

    fun pct(v: Double, arabic: Boolean): String {
        val s = num(kotlin.math.abs(v), 2, arabic)
        return (if (v >= 0) "▲ " else "▼ ") + s + "%"
    }
}

object Market {
    /** سوق الذهب العالمي مغلق من مساء الجمعة 21:00 حتى مساء الأحد 22:00 بتوقيت غرينتش تقريباً */
    fun isClosed(now: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = now
        val d = c.get(Calendar.DAY_OF_WEEK)
        val h = c.get(Calendar.HOUR_OF_DAY)
        return when (d) {
            Calendar.SATURDAY -> true
            Calendar.FRIDAY -> h >= 21
            Calendar.SUNDAY -> h < 22
            else -> false
        }
    }
}
