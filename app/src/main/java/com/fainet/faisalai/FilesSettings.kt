package com.fainet.faisalai

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.format.Formatter
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.io.File

/**
 * Every file the app made or received: saved notes, files from another phone,
 * and BVR videos. Each row shows a thumbnail, the name, the size and the link.
 */
class FilesActivity : BaseActivity() {

    /** [file] is null for BVR videos, which live in the phone's Movies folder. */
    private class Item(val name: String, val uri: Uri, val size: Long, val file: File?, val mime: String) {
        val link: String get() = file?.absolutePath ?: uri.toString()
    }

    private var items: List<Item> = emptyList()
    private val thumbs = HashMap<String, Bitmap>()
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
            val item = items[pos]
            val actions = arrayOf(
                getString(R.string.open), getString(R.string.share),
                getString(R.string.copy_link), getString(R.string.delete)
            )
            AlertDialog.Builder(this).setTitle(item.name).setItems(actions) { _, which ->
                when (which) {
                    0 -> open(item)
                    1 -> share(item)
                    2 -> copy(item.link)
                    else -> {
                        delete(item)
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

    private fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

    private fun refresh() {
        val all = ArrayList<Item>()
        Storage.all(this).forEach { f -> all.add(Item(f.name, Storage.uri(this, f), f.length(), f, mime(f.name))) }
        all.addAll(recordedVideos())
        items = all
        list.adapter = Adapter()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** BVR videos. Without a storage permission Android lists only the videos this app recorded. */
    private fun recordedVideos(): List<Item> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val found = ArrayList<Item>()
        try {
            val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(
                base,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("Movies/FaisalAI%"),
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(base, c.getLong(0).toString())
                    found.add(Item(c.getString(1) ?: "video.mp4", uri, c.getLong(2), null, "video/mp4"))
                }
            }
        } catch (e: Exception) {
            // The media library is unavailable; the other files are still listed.
        }
        return found
    }

    private fun thumbnail(item: Item): Bitmap? = try {
        when {
            item.file == null && Build.VERSION.SDK_INT >= 29 -> contentResolver.loadThumbnail(item.uri, Size(160, 160), null)
            item.mime.startsWith("image/") -> Storage.loadBitmap(this, item.uri, 160)
            item.mime.startsWith("video/") && item.file != null && Build.VERSION.SDK_INT >= 29 ->
                ThumbnailUtils.createVideoThumbnail(item.file, Size(160, 160), null)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_file, parent, false)
            val item = items[position]
            row.findViewById<TextView>(R.id.name).text = item.name
            row.findViewById<TextView>(R.id.sub).text =
                Formatter.formatShortFileSize(this@FilesActivity, item.size) + "  •  " + item.link

            val thumb = row.findViewById<ImageView>(R.id.thumb)
            thumb.tag = item.link
            val cached = thumbs[item.link]
            if (cached != null) {
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.setImageBitmap(cached)
            } else {
                thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                thumb.setImageResource(R.drawable.ic_file)
                Net.pool.execute {
                    val bmp = thumbnail(item) ?: return@execute
                    Net.main.post {
                        thumbs[item.link] = bmp
                        if (thumb.tag == item.link) { // the row may have been reused for another file
                            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                            thumb.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return row
        }
    }

    private fun open(item: Item) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: Exception) {
            toast(getString(R.string.no_app))
        }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun delete(item: Item) {
        try {
            if (item.file != null) item.file.delete() else contentResolver.delete(item.uri, null, null)
        } catch (e: Exception) {
            // Android refused to delete the video; it stays in the list.
        }
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
            Prefs.applyNight(value as String)
            // Recreate after the new value is stored, so the screen is rebuilt with the new theme.
            Net.main.post { activity?.recreate() }
            true
        }
        findPreference<ListPreference>("lang")?.setOnPreferenceChangeListener { _, value ->
            Prefs.applyLang(value as String)
            true
        }

        // Keys are typed as passwords and never shown in the list.
        for (key in listOf("cf_token", "gemini_key")) {
            findPreference<EditTextPreference>(key)?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    if (p.text.isNullOrEmpty()) getString(R.string.s_key_sum) else "••••••••"
                }
            }
        }
        findPreference<EditTextPreference>("cf_account")?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { p -> p.text ?: "" }

        findPreference<Preference>("policy")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), InfoActivity::class.java).putExtra("page", "policy"))
            true
        }
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), InfoActivity::class.java).putExtra("page", "about"))
            true
        }

        findPreference<Preference>("erase")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setMessage(R.string.erase_q)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.s_erase) { _, _ ->
                    Storage.eraseAll(ctx)
                    Prefs.sp(ctx).edit().clear().apply()
                    Prefs.applyNight("classic")
                    Toast.makeText(ctx, R.string.erased, Toast.LENGTH_SHORT).show()
                    requireActivity().recreate()
                }
                .show()
            true
        }
    }
}

/** Privacy policy and About. */
class InfoActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)
        val text = findViewById<TextView>(R.id.text)
        if (intent.getStringExtra("page") == "about") {
            bar(R.string.s_about)
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
            text.text = getString(R.string.about_text, version)
        } else {
            bar(R.string.s_policy)
            text.setText(R.string.policy_text)
        }
    }
}
