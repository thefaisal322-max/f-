package com.fainet.faisalai

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.io.File

/** Saved notes (from Lens, Text Reader, Translator) and files received from another phone. */
class FilesActivity : BaseActivity() {

    private var items: List<File> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        bar(R.string.t_files)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val f = items[pos]
            val actions = arrayOf(getString(R.string.open), getString(R.string.share), getString(R.string.delete))
            AlertDialog.Builder(this).setTitle(f.name).setItems(actions) { _, which ->
                when (which) {
                    0 -> open(f)
                    1 -> share(f)
                    else -> {
                        f.delete()
                        refresh()
                    }
                }
            }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Storage.all(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { it.name })
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun open(f: File) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Storage.uri(this, f), mime(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            toast(getString(R.string.no_app))
        }
    }

    private fun share(f: File) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime(f))
            .putExtra(Intent.EXTRA_STREAM, Storage.uri(this, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }
}

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bar(R.string.t_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, SettingsFragment()).commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs, rootKey)

        // Theme and language take effect at once.
        findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, value ->
            Prefs.applyTheme(value as String)
            true
        }
        findPreference<ListPreference>("lang")?.setOnPreferenceChangeListener { _, value ->
            Prefs.applyLang(value as String)
            true
        }

        // The API key is typed as a password and never shown in the list.
        findPreference<EditTextPreference>("api_key")?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                if (p.text.isNullOrEmpty()) getString(R.string.s_key_sum) else "••••••••"
            }
        }

        findPreference<Preference>("erase")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setMessage(R.string.erase_q)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.s_erase) { _, _ ->
                    Storage.eraseAll(ctx)
                    Prefs.sp(ctx).edit().clear().apply()
                    Prefs.applyTheme("system")
                    Toast.makeText(ctx, R.string.erased, Toast.LENGTH_SHORT).show()
                    requireActivity().recreate()
                }
                .show()
            true
        }
    }
}
