package com.goldlive.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalculatorActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var cache: Cache
    private val karats = listOf(24, 22, 21, 18, 14)
    private var cur = Cur.SAR

    private lateinit var etWeight: TextInputEditText
    private lateinit var etLabor: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var tilLabor: TextInputLayout
    private lateinit var tilAmount: TextInputLayout
    private lateinit var spKarat: Spinner
    private lateinit var swVat: MaterialSwitch
    private lateinit var tvResult: TextView
    private lateinit var tvGrams: TextView
    private lateinit var tvBasis: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        prefs = AppPrefs(this)
        cache = Cache(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        etWeight = findViewById(R.id.etWeight)
        etLabor = findViewById(R.id.etLabor)
        etAmount = findViewById(R.id.etAmount)
        tilLabor = findViewById(R.id.tilLabor)
        tilAmount = findViewById(R.id.tilAmount)
        spKarat = findViewById(R.id.spKarat)
        swVat = findViewById(R.id.swVat)
        tvResult = findViewById(R.id.tvResult)
        tvGrams = findViewById(R.id.tvGrams)
        tvBasis = findViewById(R.id.tvBasis)

        spKarat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, karats.map { "عيار $it" }
        )
        spKarat.setSelection(2) // 21 افتراضياً
        spKarat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = calc()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        swVat.text = "إضافة ضريبة القيمة المضافة (${Fmt.num(prefs.vat, 0, prefs.arabicDigits)}%) على المصنعية"
        swVat.setOnCheckedChangeListener { _, _ -> calc() }

        val tg = findViewById<MaterialButtonToggleGroup>(R.id.toggleCur)
        tg.check(R.id.btnSar)
        tg.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            cur = when (id) { R.id.btnUsd -> Cur.USD; R.id.btnSyp -> Cur.SYP; else -> Cur.SAR }
            calc()
        }

        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = calc()
        }
        etWeight.addTextChangedListener(w)
        etLabor.addTextChangedListener(w)
        etAmount.addTextChangedListener(w)
        calc()
    }

    private fun d(e: TextInputEditText): Double =
        e.text?.toString()?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: 0.0

    private fun calc() {
        if (!::tvResult.isInitialized) return
        tilLabor.suffixText = cur.symbol
        tilAmount.suffixText = cur.symbol
        val gold = cache.gold
        val rate = prefs.rate(cur, cache.sar, cache.sypNew)
        if (gold == null || rate == null) {
            tvResult.text = "لا تتوفر أسعار محفوظة بعد. افتح الشاشة الرئيسية مع اتصال بالإنترنت أولاً" +
                if (rate == null) "\n(سعر صرف الليرة غير متاح — يمكنك إدخاله يدوياً من الإعدادات)" else ""
            tvGrams.text = ""
            return
        }
        val k = karats[spKarat.selectedItemPosition.coerceIn(0, karats.size - 1)]
        val gramPrice = gold / OZ_GRAMS * k / 24.0 * (1 + prefs.spread / 100.0) * rate
        val weight = d(etWeight)
        val laborPerGram = d(etLabor)
        val vatRate = if (swVat.isChecked) prefs.vat / 100.0 else 0.0

        val goldValue = gramPrice * weight
        val labor = laborPerGram * weight
        val vat = labor * vatRate
        val total = goldValue + labor + vat
        val f = { v: Double -> prefs.format(v, cur) + " " + cur.symbol }

        tvResult.text = buildString {
            append("سعر غرام عيار $k: ${f(gramPrice)}\n")
            append("قيمة الذهب (${Fmt.num(weight, 2, prefs.arabicDigits)} غ): ${f(goldValue)}\n")
            if (labor > 0) append("المصنعية: ${f(labor)}\n")
            if (vat > 0) append("الضريبة: ${f(vat)}\n")
            append("━━━━━━━━━━━━\n")
            append("الإجمالي: ${f(total)}")
        }

        val amount = d(etAmount)
        val perGramAll = gramPrice + laborPerGram * (1 + vatRate)
        tvGrams.text = if (amount > 0 && perGramAll > 0) {
            val g = amount / perGramAll
            "يكفي المبلغ لشراء ${Fmt.num(g, 2, prefs.arabicDigits)} غرام من عيار $k" +
                (if (laborPerGram > 0) "\n(شاملاً المصنعية${if (vatRate > 0) " والضريبة" else ""})" else "")
        } else "أدخل المبلغ لمعرفة الوزن الممكن شراؤه"

        val t = cache.time
        tvBasis.text = "الحساب مبني على سعر الأونصة ${Fmt.num(gold, 2, prefs.arabicDigits)}$" +
            (if (t > 0) " بتاريخ " + SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale("ar")).format(Date(t)) else "")
    }
}
