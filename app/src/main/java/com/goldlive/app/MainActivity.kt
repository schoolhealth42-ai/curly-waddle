package com.goldlive.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var cache: Cache

    // الحالة الحالية
    private var gold: Double? = null
    private var silver: Double? = null
    private var changePct: Double? = null
    private var sar: Double = 3.75
    private var sypNew: Double? = null
    private var source: String = ""
    private var updatedAt: Long = 0L
    private var online = false
    private var series: Repo.Series? = null
    private var seriesRange: String = ""

    // العناصر
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var liveDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvOzUsd: TextView
    private lateinit var tvOzSar: TextView
    private lateinit var tvOzSyp: TextView
    private lateinit var tvSypLabel: TextView
    private lateinit var tvChange: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvSpreadNote: TextView
    private lateinit var gramTable: LinearLayout
    private lateinit var coinsCard: MaterialCardView
    private lateinit var coinsTable: LinearLayout
    private lateinit var silverCard: MaterialCardView
    private lateinit var silverTable: LinearLayout
    private lateinit var chart: ChartView
    private lateinit var chartStats: LinearLayout
    private lateinit var chartProgress: ProgressBar
    private lateinit var tvFx: TextView

    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale("ar"))
    private var dotAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        prefs = AppPrefs(this)
        cache = Cache(this)
        bindViews()
        setupToolbar()
        setupChartControls()
        loadCache()
        render()

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.gold))
        swipe.setOnRefreshListener {
            lifecycleScope.launch {
                refreshFx(); refreshLive(); refreshChart(force = true)
                swipe.isRefreshing = false
            }
        }

        dotAnim = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.2f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        // حلقات التحديث تعمل فقط أثناء ظهور الشاشة
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    while (isActive) {
                        refreshFx()
                        delay(30 * 60_000L)
                    }
                }
                launch {
                    while (isActive) {
                        refreshLive()
                        delay(prefs.refreshSeconds * 1000L)
                    }
                }
                launch {
                    while (isActive) {
                        refreshChart(force = true)
                        delay(5 * 60_000L)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (prefs.alertsEnabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        Alerts.schedule(this)
        render()
        renderChart()
    }

    private fun bindViews() {
        swipe = findViewById(R.id.swipe)
        liveDot = findViewById(R.id.liveDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvOzUsd = findViewById(R.id.tvOzUsd)
        tvOzSar = findViewById(R.id.tvOzSar)
        tvOzSyp = findViewById(R.id.tvOzSyp)
        tvSypLabel = findViewById(R.id.tvSypLabel)
        tvChange = findViewById(R.id.tvChange)
        tvSource = findViewById(R.id.tvSource)
        tvSpreadNote = findViewById(R.id.tvSpreadNote)
        gramTable = findViewById(R.id.gramTable)
        coinsCard = findViewById(R.id.coinsCard)
        coinsTable = findViewById(R.id.coinsTable)
        silverCard = findViewById(R.id.silverCard)
        silverTable = findViewById(R.id.silverTable)
        chart = findViewById(R.id.chart)
        chartStats = findViewById(R.id.chartStats)
        chartProgress = findViewById(R.id.chartProgress)
        tvFx = findViewById(R.id.tvFx)
    }

    private fun setupToolbar() {
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.inflateMenu(R.menu.main_menu)
        tb.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_calc -> startActivity(Intent(this, CalculatorActivity::class.java))
                R.id.action_share -> sharePrices()
            }
            true
        }
    }

    private fun setupChartControls() {
        val chips = findViewById<ChipGroup>(R.id.chipRange)
        chips.check(
            when (prefs.chartRange) {
                "5d" -> R.id.chipWeek; "1mo" -> R.id.chipMonth; "3mo" -> R.id.chip3m; else -> R.id.chipDay
            }
        )
        chips.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            prefs.chartRange = when (id) {
                R.id.chipWeek -> "5d"; R.id.chipMonth -> "1mo"; R.id.chip3m -> "3mo"; else -> "1d"
            }
            lifecycleScope.launch { refreshChart(force = true) }
        }

        val curGroup = findViewById<MaterialButtonToggleGroup>(R.id.toggleCur)
        curGroup.check(
            when (prefs.chartCurrency) { Cur.SAR -> R.id.btnSar; Cur.SYP -> R.id.btnSyp; else -> R.id.btnUsd }
        )
        curGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.chartCurrency = when (id) { R.id.btnSar -> Cur.SAR; R.id.btnSyp -> Cur.SYP; else -> Cur.USD }
            renderChart()
        }

        val unitGroup = findViewById<MaterialButtonToggleGroup>(R.id.toggleUnit)
        unitGroup.check(if (prefs.chartUnitGram) R.id.btnGram else R.id.btnOz)
        unitGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.chartUnitGram = id == R.id.btnGram
            renderChart()
        }
    }

    private fun loadCache() {
        gold = cache.gold
        silver = cache.silver
        changePct = cache.changePct
        sar = cache.sar
        sypNew = cache.sypNew
        source = cache.source
        updatedAt = cache.time
    }

    // ================= جلب البيانات =================

    private suspend fun refreshLive() {
        try {
            val spot = Repo.goldSpot()
            val old = gold
            gold = spot.price
            source = spot.source
            updatedAt = System.currentTimeMillis()
            online = true
            if (prefs.showSilver) silver = Repo.silverSpot() ?: silver
            cache.gold = gold; cache.silver = silver; cache.time = updatedAt; cache.source = source
            render()
            if (old != null && prefs.flashPrices && old != spot.price) flash(spot.price > old)
        } catch (e: Exception) {
            online = false
            render()
        }
    }

    private suspend fun refreshFx() {
        try {
            val fx = Repo.fx()
            sar = fx.sar
            if (fx.sypNew != null) sypNew = fx.sypNew
            cache.sar = sar; cache.sypNew = sypNew
            cache.fxSource = fx.source; cache.fxTime = System.currentTimeMillis()
            render(); renderChart()
        } catch (_: Exception) { }
    }

    private suspend fun refreshChart(force: Boolean) {
        val range = prefs.chartRange
        if (!force && series != null && seriesRange == range) { renderChart(); return }
        chartProgress.visibility = View.VISIBLE
        try {
            // نسبة التغير اليومية من جلسة اليوم
            val day = if (range == "1d") Repo.goldSeries("1d").also { series = it } else Repo.goldSeries("1d")
            val prev = day.prevClose; val last = day.last
            if (prev != null && last != null && prev > 0) {
                changePct = (last - prev) / prev * 100.0
                cache.changePct = changePct
            }
            if (range != "1d") series = Repo.goldSeries(range)
            seriesRange = range
            chart.emptyText = "لا توجد بيانات لهذه الفترة"
        } catch (_: Exception) {
            chart.emptyText = "تعذر تحميل المخطط — تحقق من الاتصال"
        }
        chartProgress.visibility = View.GONE
        render()
        renderChart()
    }

    // ================= العرض =================

    private fun conv(usd: Double, cur: Cur): Double? = prefs.rate(cur, sar, sypNew)?.let { usd * it }

    private fun fmt(usd: Double, cur: Cur): String =
        conv(usd, cur)?.let { prefs.format(it, cur) } ?: "—"

    private fun render() {
        val g = gold
        val closed = Market.isClosed()
        tvStatus.text = when {
            !online && g != null -> "آخر سعر محفوظ"
            !online -> "جارٍ الاتصال..."
            closed -> "السوق مغلق"
            else -> "مباشر"
        }
        liveDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when {
                !online -> 0xFFEF4444.toInt()
                closed -> 0xFFF59E0B.toInt()
                else -> 0xFF22C55E.toInt()
            }
        )
        tvUpdated.text = if (updatedAt > 0) "آخر تحديث " + timeFmt.format(Date(updatedAt)) else ""
        tvSypLabel.text = if (prefs.sypOld) "بالليرة السورية (القديمة)" else "بالليرة السورية (الجديدة)"

        if (g == null) return

        tvOzUsd.text = "$ " + prefs.format(g, Cur.USD)
        tvOzSar.text = fmt(g, Cur.SAR) + " " + Cur.SAR.symbol
        tvOzSyp.text = fmt(g, Cur.SYP) + " " + Cur.SYP.symbol

        val ch = changePct
        if (ch != null) {
            tvChange.text = Fmt.pct(ch, prefs.arabicDigits) + " اليوم"
            tvChange.setTextColor(if (ch >= 0) 0xFF0B3D1E.toInt() else 0xFF5A0B0B.toInt())
            tvChange.setBackgroundResource(R.drawable.bg_pill)
            tvChange.background.mutate().setTint(if (ch >= 0) 0xAA86EFAC.toInt() else 0xAAFCA5A5.toInt())
        }
        tvSource.text = "المصدر: $source  •  التحديث كل ${prefs.refreshSeconds} ث"

        val spread = prefs.spread
        tvSpreadNote.text = if (spread > 0)
            "يشمل هامش بيع ${Fmt.num(spread, 2, prefs.arabicDigits)}% • دون مصنعية أو ضريبة"
        else "سعر الخام الصافي دون مصنعية أو ضريبة"

        val gram24 = g / OZ_GRAMS * (1 + spread / 100.0)

        // جدول الأعيرة
        gramTable.removeAllViews()
        gramTable.addView(row("العيار", "دولار", "ريال", "ليرة", header = true))
        for (k in prefs.karats) {
            val v = gram24 * k / 24.0
            gramTable.addView(row("عيار $k", fmt(v, Cur.USD), fmt(v, Cur.SAR), fmt(v, Cur.SYP), highlight = k == 21 || k == 24))
        }

        // المسكوكات
        coinsCard.visibility = if (prefs.showCoins) View.VISIBLE else View.GONE
        if (prefs.showCoins) {
            coinsTable.removeAllViews()
            coinsTable.addView(row("القطعة", "دولار", "ريال", "ليرة", header = true))
            for (c in COINS) {
                val v = gram24 * c.karat / 24.0 * c.grams
                coinsTable.addView(row(c.name, fmt(v, Cur.USD), fmt(v, Cur.SAR), fmt(v, Cur.SYP), smallFirst = true))
            }
        }

        // الفضة
        val s = silver
        silverCard.visibility = if (prefs.showSilver && s != null) View.VISIBLE else View.GONE
        if (prefs.showSilver && s != null) {
            silverTable.removeAllViews()
            silverTable.addView(row("الوحدة", "دولار", "ريال", "ليرة", header = true))
            silverTable.addView(row("أونصة الفضة", fmt(s, Cur.USD), fmt(s, Cur.SAR), fmt(s, Cur.SYP)))
            val sg = s / OZ_GRAMS
            silverTable.addView(row("غرام عيار 999", fmt(sg, Cur.USD), fmt(sg, Cur.SAR), fmt(sg, Cur.SYP)))
            val ratio = g / s
            silverTable.addView(row("نسبة الذهب/الفضة", Fmt.num(ratio, 1, prefs.arabicDigits), "", ""))
        }

        // أسعار الصرف
        val syp = prefs.sypRate(sypNew)
        val sypTxt = syp?.let { Fmt.num(it, if (it >= 1000) 0 else 2, prefs.arabicDigits) } ?: "غير متاح"
        val sypSrc = if (prefs.sypSource == "manual" && prefs.sypManual != null) "سعر يدوي من الإعدادات" else cache.fxSource.ifEmpty { "—" }
        tvFx.text = "1 دولار = ${Fmt.num(sar, 4, prefs.arabicDigits)} ريال سعودي\n" +
            "1 دولار = $sypTxt ليرة سورية ${if (prefs.sypOld) "(قديمة)" else "(جديدة)"}\n" +
            "مصدر الليرة: $sypSrc\n" +
            "1 ريال = ${syp?.let { Fmt.num(it / sar, 2, prefs.arabicDigits) } ?: "—"} ليرة سورية"
    }

    private fun renderChart() {
        val s = series ?: run { chart.setData(emptyList(), emptyList(), prefs.chartRange); chartStats.removeAllViews(); return }
        val cur = prefs.chartCurrency
        val rate = prefs.rate(cur, sar, sypNew) ?: 1.0
        // معايرة العقود الآجلة على السعر الفوري
        val calib = if (gold != null && s.last != null && s.last > 0) gold!! / s.last else 1.0
        val unit = if (prefs.chartUnitGram) 1.0 / OZ_GRAMS else 1.0
        val factor = rate * calib * unit
        val vals = s.values.map { it * factor }
        val labelCur = if (prefs.rate(cur, sar, sypNew) == null) Cur.USD else cur
        chart.valueFormatter = { v -> prefs.format(v, labelCur) }
        chart.setData(s.times, vals, seriesRange.ifEmpty { prefs.chartRange })

        chartStats.removeAllViews()
        if (vals.size >= 2) {
            val first = vals.first(); val last = vals.last()
            val pct = (last - first) / first * 100.0
            chartStats.addView(stat("الافتتاح", prefs.format(first, labelCur), null))
            chartStats.addView(stat("الأعلى", prefs.format(vals.max(), labelCur), null))
            chartStats.addView(stat("الأدنى", prefs.format(vals.min(), labelCur), null))
            chartStats.addView(stat("التغير", Fmt.pct(pct, prefs.arabicDigits), pct >= 0))
        }
    }

    private fun stat(title: String, value: String, up: Boolean?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        box.addView(TextView(this).apply {
            text = value; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when (up) {
                    true -> ContextCompat.getColor(context, R.color.up)
                    false -> ContextCompat.getColor(context, R.color.down)
                    null -> ContextCompat.getColor(context, R.color.text_primary)
                }
            )
        })
        return box
    }

    private fun row(
        a: String, b: String, c: String, d: String,
        header: Boolean = false, highlight: Boolean = false, smallFirst: Boolean = false
    ): View {
        val dp = resources.displayMetrics.density
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            if (header || highlight) setBackgroundResource(R.drawable.bg_row_header)
        }
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val goldC = ContextCompat.getColor(this, R.color.gold)
        listOf(a, b, c, d).forEachIndexed { i, t ->
            r.addView(TextView(this).apply {
                text = t
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 0) 1.25f else 1f)
                gravity = if (i == 0) Gravity.START else Gravity.CENTER
                textSize = when {
                    header -> 12f
                    i == 0 && smallFirst -> 11.5f
                    else -> 13.5f
                }
                setTextColor(
                    when {
                        header -> secondary
                        i == 0 -> goldC
                        else -> primary
                    }
                )
                if (!header) setTypeface(typeface, Typeface.BOLD)
            })
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(r)
        if (!header) {
            wrap.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
                setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
            })
        }
        return wrap
    }

    private fun flash(up: Boolean) {
        val from = ContextCompat.getColor(this, if (up) R.color.up else R.color.down)
        val to = ContextCompat.getColor(this, R.color.on_gold)
        ValueAnimator.ofArgb(from, to).apply {
            duration = 1200
            addUpdateListener { tvOzUsd.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun sharePrices() {
        val g = gold ?: return
        val gram24 = g / OZ_GRAMS * (1 + prefs.spread / 100.0)
        val sb = StringBuilder()
        sb.append("🟡 أسعار الذهب الآن — ذهب لايف\n")
        sb.append(SimpleDateFormat("yyyy/MM/dd  hh:mm a", Locale("ar")).format(Date(updatedAt))).append("\n\n")
        sb.append("الأونصة: $${prefs.format(g, Cur.USD)} | ${fmt(g, Cur.SAR)} ر.س | ${fmt(g, Cur.SYP)} ل.س\n\n")
        for (k in prefs.karats) {
            val v = gram24 * k / 24.0
            sb.append("غرام عيار $k: $${fmt(v, Cur.USD)} | ${fmt(v, Cur.SAR)} ر.س | ${fmt(v, Cur.SYP)} ل.س\n")
        }
        changePct?.let { sb.append("\nالتغير اليومي: ").append(Fmt.pct(it, prefs.arabicDigits)) }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(i, "مشاركة الأسعار"))
    }

    override fun onDestroy() {
        dotAnim?.cancel()
        super.onDestroy()
    }
}
