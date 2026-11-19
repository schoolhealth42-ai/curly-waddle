package com.goldlive.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val decimalKeys = listOf("syp_manual_rate", "spread_percent", "vat_percent", "alert_above", "alert_below")
            for (k in decimalKeys) {
                findPreference<EditTextPreference>(k)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    it.setSelection(it.text.length)
                }
            }

            val manual = findPreference<EditTextPreference>("syp_manual_rate")
            val src = findPreference<ListPreference>("syp_source")
            manual?.isEnabled = src?.value == "manual"
            src?.setOnPreferenceChangeListener { _, v ->
                manual?.isEnabled = v == "manual"
                true
            }

            findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, v ->
                GoldApp.applyTheme(v as String)
                true
            }

            findPreference<Preference>("alerts_enabled")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { Alerts.schedule(requireContext()) }
                true
            }

            findPreference<Preference>("reset")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("إعادة الضبط")
                    .setMessage("هل تريد إرجاع جميع الإعدادات إلى القيم الافتراضية؟")
                    .setPositiveButton("نعم") { _, _ ->
                        val sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        sp.edit().clear().apply()
                        GoldApp.applyTheme("dark")
                        Alerts.schedule(requireContext())
                        Toast.makeText(requireContext(), "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        requireActivity().recreate()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                true
            }
        }
    }
}
