package com.goldlive.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * مصادر البيانات:
 *  - السعر الفوري للذهب والفضة: gold-api.com (مجاني، تحديث لحظي)  ← احتياطي: xaus.com ← احتياطي: COMEX عبر Yahoo Finance
 *  - المخطط البياني: عقود الذهب الآجلة COMEX (GC=F) عبر Yahoo Finance، مع معايرتها على السعر الفوري
 *  - أسعار الصرف: open.er-api.com (ExchangeRate-API) — الريال مربوط رسمياً عند 3.75
 */
object Repo {

    data class Spot(val price: Double, val source: String)
    data class Series(
        val times: List<Long>,
        val values: List<Double>,
        val prevClose: Double?,
        val last: Double?
    )
    data class Fx(val sar: Double, val sypNew: Double?, val source: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GoldLive/1.0")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.firstDouble(vararg keys: String): Double? {
        for (k in keys) {
            if (has(k) && !isNull(k)) {
                val v = optDouble(k, Double.NaN)
                if (!v.isNaN()) return v
            }
        }
        return null
    }

    private fun valid(v: Double?, min: Double): Double? = v?.takeIf { it > min && it < 1_000_000 }

    suspend fun goldSpot(): Spot = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://api.gold-api.com/price/XAU"))
            valid(j.firstDouble("price", "spot", "value"), 100.0)?.let { Spot(it, "gold-api.com") }
        }.getOrNull()
            ?: runCatching {
                val j = JSONObject(get("https://xaus.com/api/v1/spot"))
                valid(j.firstDouble("spot_usd_oz"), 100.0)?.let { Spot(it, "xaus.com") }
            }.getOrNull()
            ?: runCatching {
                val s = yahoo("GC%3DF", "1d", "5m")
                valid(s.last, 100.0)?.let { Spot(it, "COMEX / Yahoo") }
            }.getOrNull()
            ?: throw IOException("تعذر الوصول لمصادر الأسعار")
    }

    suspend fun silverSpot(): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://api.gold-api.com/price/XAG"))
            valid(j.firstDouble("price", "spot", "value"), 1.0)
        }.getOrNull()
            ?: runCatching { valid(yahoo("SI%3DF", "1d", "5m").last, 1.0) }.getOrNull()
    }

    suspend fun fx(): Fx = withContext(Dispatchers.IO) {
        val j = JSONObject(get("https://open.er-api.com/v6/latest/USD"))
        val rates = j.getJSONObject("rates")
        val sar = rates.optDouble("SAR", 3.75).takeIf { !it.isNaN() && it > 3 && it < 4.5 } ?: 3.75
        var syp = rates.optDouble("SYP", Double.NaN).takeIf { !it.isNaN() && it > 0 }
        // الليرة الجديدة (2026) = القديمة ÷ 100 ؛ إن أعاد المصدر الفئة القديمة نحولها
        if (syp != null && syp > 1000) syp /= 100.0
        Fx(sar, syp, "ExchangeRate-API")
    }

    /** range: 1d / 5d / 1mo / 3mo */
    suspend fun goldSeries(range: String): Series = withContext(Dispatchers.IO) {
        val interval = when (range) {
            "1d" -> "5m"
            "5d" -> "30m"
            "1mo" -> "1d"
            else -> "1d"
        }
        yahoo("GC%3DF", range, interval)
    }

    private fun yahoo(symbol: String, range: String, interval: String): Series {
        val txt = get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=$range&interval=$interval")
        val res = JSONObject(txt).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = res.getJSONObject("meta")
        val ts = res.optJSONArray("timestamp")
        val closes = res.optJSONObject("indicators")
            ?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val t = ArrayList<Long>()
        val v = ArrayList<Double>()
        if (ts != null && closes != null) {
            val n = minOf(ts.length(), closes.length())
            for (i in 0 until n) {
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i, Double.NaN)
                if (c.isNaN() || c <= 0) continue
                t.add(ts.getLong(i) * 1000L)
                v.add(c)
            }
        }
        val prev = meta.firstDouble("chartPreviousClose", "previousClose")
        val last = meta.firstDouble("regularMarketPrice") ?: v.lastOrNull()
        return Series(t, v, prev, last)
    }
}
