package com.fainet.faisalai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

/** Home screen: search box, Main / Vision / Web tabs and the feature grid. */
class MainActivity : BaseActivity() {

    private class Tile(val label: Int, val icon: Int, val cats: String, val open: () -> Unit)

    private lateinit var tiles: List<Tile>
    private var shown: List<Tile> = emptyList()
    private var tab = "main"

    private fun go(c: Class<*>, extras: (Intent.() -> Unit)? = null) {
        val i = Intent(this, c)
        extras?.invoke(i)
        startActivity(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Web tab: no tools, only search. Tools are on the Main tab.
        tiles = listOf(
            Tile(R.string.t_chat, R.drawable.ic_chat, "main") { go(ChatActivity::class.java) },
            Tile(R.string.t_scan, R.drawable.ic_scan, "main") { go(ScannerActivity::class.java) },
            Tile(R.string.t_browser, R.drawable.ic_browser, "main") { go(BrowserActivity::class.java) },
            Tile(R.string.t_nav, R.drawable.ic_nav, "main") { go(MapActivity::class.java) },
            Tile(R.string.t_tr, R.drawable.ic_translate, "main") { go(TranslateActivity::class.java) },
            Tile(R.string.t_bvr, R.drawable.ic_bvr, "main") { go(BvrActivity::class.java) },
            Tile(R.string.t_share, R.drawable.ic_share, "main") { go(ShareActivity::class.java) },
            Tile(R.string.t_notes, R.drawable.ic_note, "main") { go(NotesActivity::class.java) },
            Tile(R.string.t_maker, R.drawable.ic_qr, "main") { go(MakerActivity::class.java) },
            Tile(R.string.t_convert, R.drawable.ic_money, "main") { go(ConverterActivity::class.java) },
            Tile(R.string.t_weather, R.drawable.ic_weather, "main") { go(WeatherActivity::class.java) },
            Tile(R.string.t_files, R.drawable.ic_file, "main") { go(FilesActivity::class.java) },
        )
        shown = tiles

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { go(SettingsActivity::class.java) }

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 4)
        val adapter = TileAdapter()
        grid.adapter = adapter

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText(R.string.tab_main).setTag("main"))
        tabs.addTab(tabs.newTab().setText(R.string.tab_vision).setTag("vision"))
        tabs.addTab(tabs.newTab().setText(R.string.tab_web).setTag("web"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.tag as String
                if (tab == "vision") {
                    // The Vision tab is the live, talking camera assistant; come back to Main afterwards.
                    go(LiveActivity::class.java)
                    tabs.post { tabs.getTabAt(0)?.select() }
                    return
                }
                shown = tiles.filter { it.cats.split(" ").contains(tab) }
                adapter.notifyDataSetChanged()
                findViewById<View>(R.id.webHint).visibility = if (tab == "web") View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        // On the Web tab the box searches the web; elsewhere it asks the AI.
        val search = findViewById<EditText>(R.id.search)
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val q = search.text.toString().trim()
            if (q.isNotEmpty()) {
                search.setText("")
                if (tab == "web") go(BrowserActivity::class.java) { putExtra("query", q) }
                else go(ChatActivity::class.java) { putExtra("q", q) }
            }
            true
        }
    }

    private inner class TileAdapter : RecyclerView.Adapter<TileAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val t = shown[position]
            h.icon.setImageResource(t.icon)
            h.label.setText(t.label)
            h.itemView.setOnClickListener { t.open() }
        }
    }
}
