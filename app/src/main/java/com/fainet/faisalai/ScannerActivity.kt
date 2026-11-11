package com.fainet.faisalai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Document scanner. Each photo of a paper becomes a page; the pages are cleaned up
 * (even light, white paper, dark text) and saved as PDF, Word, PNG or JPG in Files.
 */
class ScannerActivity : BaseActivity() {

    private companion object {
        const val MAX_PAGES = 20
    }

    private val pages = ArrayList<Bitmap>() // the photos as taken
    private var mode = Enhance.COLOR
    private var busy = false
    private var cameraUri: Uri? = null
    private var previewRequest = 0

    private lateinit var preview: ImageView
    private lateinit var count: TextView
    private lateinit var status: TextView

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { addPage(it) }
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { addPage(it) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else toast(getString(R.string.perm_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        bar(R.string.t_scan)
        scanTabs(0)
        preview = findViewById(R.id.preview)
        count = findViewById(R.id.count)
        status = findViewById(R.id.status)

        val modes = findViewById<TabLayout>(R.id.modes)
        listOf(R.string.m_color, R.string.m_gray, R.string.m_bw, R.string.m_orig)
            .forEach { modes.addTab(modes.newTab().setText(it)) }
        modes.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = tab.position
                showPreview()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { pickImages.launch("image/*") }
        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            if (pages.isNotEmpty() && !busy) {
                val last = pages.last()
                val turn = Matrix().apply { postRotate(90f) }
                pages[pages.lastIndex] = Bitmap.createBitmap(last, 0, 0, last.width, last.height, turn, true)
                showPreview()
            }
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            if (!busy) {
                pages.clear()
                showPreview()
            }
        }
        findViewById<Button>(R.id.btnPdf).setOnClickListener { export("pdf") }
        findViewById<Button>(R.id.btnDocx).setOnClickListener { export("docx") }
        findViewById<Button>(R.id.btnPng).setOnClickListener { export("png") }
        findViewById<Button>(R.id.btnJpg).setOnClickListener { export("jpg") }
        showPreview()
    }

    private fun openCamera() {
        val uri = Storage.uri(this, File(cacheDir, "scan.jpg"))
        cameraUri = uri
        takePicture.launch(uri)
    }

    private fun addPage(uri: Uri) {
        if (busy || pages.size >= MAX_PAGES) return
        val bmp = Storage.loadBitmap(this, uri, 2000) ?: return
        pages.add(bmp)
        showPreview()
    }

    /** Shows the last page with the chosen cleaning applied. */
    private fun showPreview() {
        count.text = getString(R.string.scan_pages, pages.size)
        val request = ++previewRequest
        val page = pages.lastOrNull()
        if (page == null) {
            preview.setImageDrawable(null)
            return
        }
        val chosen = mode
        Net.pool.execute {
            // A smaller copy keeps the preview quick; saving uses the full page.
            val big = maxOf(page.width, page.height)
            val small = if (big > 1000)
                Bitmap.createScaledBitmap(page, page.width * 1000 / big, page.height * 1000 / big, true)
            else page
            val cleaned = Enhance.apply(small, chosen)
            Net.main.post { if (request == previewRequest && !isDestroyed) preview.setImageBitmap(cleaned) }
        }
    }

    private fun export(kind: String) {
        if (busy) return
        if (pages.isEmpty()) {
            toast(getString(R.string.scan_none))
            return
        }
        busy = true
        status.text = getString(R.string.working)
        val originals = ArrayList(pages)
        val chosen = mode
        val name = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "scan_out").apply { mkdirs() }
        Net.pool.execute {
            var saved: String? = null
            var cleaned: List<Bitmap> = emptyList()
            try {
                cleaned = originals.map { Enhance.apply(it, chosen) }
                when (kind) {
                    "pdf" -> {
                        writePdf(cleaned, File(dir, "$name.pdf"))
                        saved = "$name.pdf"
                    }
                    "png", "jpg" -> {
                        cleaned.forEachIndexed { i, page ->
                            val suffix = if (cleaned.size > 1) "_${i + 1}" else ""
                            FileOutputStream(File(dir, "$name$suffix.$kind")).use { out ->
                                if (kind == "png") page.compress(Bitmap.CompressFormat.PNG, 100, out)
                                else page.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }
                        saved = if (cleaned.size > 1) "$name (1–${cleaned.size}).$kind" else "$name.$kind"
                    }
                }
            } catch (e: Exception) {
                saved = null
            } catch (e: OutOfMemoryError) {
                saved = null // too many large pages for this phone's memory
            }
            val result = cleaned
            Net.main.post {
                if (isDestroyed) return@post
                if (kind == "docx" && result.isNotEmpty()) {
                    // Word files also get the page's text, so it can be edited. That needs the AI.
                    readTexts(result, 0, ArrayList()) { texts -> writeDocxAsync(result, texts, File(dir, "$name.docx")) }
                } else {
                    showSaved(saved)
                }
            }
        }
    }

    private fun showSaved(saved: String?) {
        if (saved == null) {
            busy = false
            status.text = getString(R.string.save_failed)
            return
        }
        // Move the finished files into Download/Faisal/Scans.
        Net.pool.execute {
            val dir = File(cacheDir, "scan_out")
            var ok = true
            dir.listFiles()?.forEach { f ->
                if (Public.save(this, "Scans", f.name, f) == null) ok = false
                f.delete()
            }
            Net.main.post {
                busy = false
                status.text = if (ok) getString(R.string.saved_as, "Download/Faisal/Scans/$saved") else getString(R.string.save_failed)
            }
        }
    }

    // ---------- PDF ----------

    private fun writePdf(images: List<Bitmap>, file: File) {
        val doc = PdfDocument()
        try {
            images.forEachIndexed { i, image ->
                // 595 points is the width of an A4 sheet; the height follows the photo's shape.
                val width = 595
                val height = (595f * image.height / image.width).toInt().coerceAtLeast(1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, i + 1).create())
                page.canvas.drawBitmap(image, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
            }
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    // ---------- Word ----------

    /** Reads the text of each page with the AI, one page after another. A page that fails gets no text. */
    private fun readTexts(images: List<Bitmap>, index: Int, texts: ArrayList<String>, done: (List<String>) -> Unit) {
        if (index >= images.size) {
            done(texts)
            return
        }
        status.text = getString(R.string.reading_page, index + 1)
        AI.ask(
            this,
            "You are a careful text reader (OCR) for every language and script.",
            listOf(
                AI.Turn(
                    "user",
                    "Transcribe all text in this scanned page exactly as written, in its original language and " +
                        "script, keeping the line breaks. Reply with only the text. If the page has no text, reply with the single word NONE."
                )
            ),
            images[index]
        ) { answer, _ ->
            texts.add(if (answer == null || answer.trim() == "NONE") "" else answer)
            if (!isDestroyed) readTexts(images, index + 1, texts, done)
        }
    }

    private fun writeDocxAsync(images: List<Bitmap>, texts: List<String>, file: File) {
        status.text = getString(R.string.working)
        Net.pool.execute {
            val ok = try {
                writeDocx(images, texts, file)
                true
            } catch (e: Exception) {
                false
            }
            Net.main.post { if (!isDestroyed) showSaved(if (ok) file.name else null) }
        }
    }

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A .docx file is a zip of XML files. Each page is its picture followed by its text. */
    private fun writeDocx(images: List<Bitmap>, texts: List<String>, file: File) {
        val body = StringBuilder()
        val rels = StringBuilder()
        images.forEachIndexed { i, image ->
            val n = i + 1
            // Sizes are in EMU (914400 per inch): 6 inches wide, at most 8.5 inches tall.
            var cx = 5486400L
            var cy = cx * image.height / image.width
            if (cy > 7772400L) {
                cx = cx * 7772400L / cy
                cy = 7772400L
            }
            rels.append("<Relationship Id=\"rId$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$n.jpeg\"/>")
            body.append(
                "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                    "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Page $n\"/>" +
                    "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
                    "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                    "<pic:blipFill><a:blip r:embed=\"rId$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                    "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                    "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                    "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
            )
            for (line in texts.getOrElse(i) { "" }.lines()) {
                if (line.isBlank()) continue
                // Arabic-script lines (Pashto, Dari, Arabic, Urdu) are laid out right to left.
                val rtl = line.any { it in '֐'..'ࣿ' }
                body.append("<w:p>")
                if (rtl) body.append("<w:pPr><w:bidi/></w:pPr>")
                body.append("<w:r>")
                if (rtl) body.append("<w:rPr><w:rtl/></w:rPr>")
                body.append("<w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>")
            }
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        val document = head +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
        val types = head +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rootRels = head +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        val docRels = head +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>"

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("[Content_Types].xml", types.toByteArray(Charsets.UTF_8))
            put("_rels/.rels", rootRels.toByteArray(Charsets.UTF_8))
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            put("word/_rels/document.xml.rels", docRels.toByteArray(Charsets.UTF_8))
            images.forEachIndexed { i, image ->
                val jpeg = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 88, jpeg)
                put("word/media/image${i + 1}.jpeg", jpeg.toByteArray())
            }
        }
    }
}

/** Makes a photo of a paper look like a scan. */
object Enhance {
    const val COLOR = 0
    const val GRAY = 1
    const val BW = 2
    const val ORIGINAL = 3

    /**
     * Evens out the light across the page so the paper turns white, then deepens the ink.
     * The paper's brightness is measured in a grid of cells and every pixel is divided by
     * the brightness of the paper around it, which removes shadows and uneven lighting.
     */
    fun apply(src: Bitmap, mode: Int): Bitmap {
        if (mode == ORIGINAL) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val cell = maxOf(16, maxOf(w, h) / 40)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val paper = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                java.util.Arrays.fill(hist, 0)
                var n = 0
                var y = gy * cell
                val yEnd = minOf(h, y + cell)
                val xEnd = minOf(w, gx * cell + cell)
                while (y < yEnd) {
                    var x = gx * cell
                    while (x < xEnd) {
                        val p = px[y * w + x]
                        hist[(((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8]++
                        n++
                        x += 2
                    }
                    y += 2
                }
                // The paper is the bright part of the cell: take the level that 10% of its pixels exceed.
                var above = 0
                var level = 255
                while (level > 40 && above + hist[level] < n / 10) {
                    above += hist[level]
                    level--
                }
                paper[gy * gw + gx] = level.toFloat()
            }
        }

        // Ink gets darker and near-white gets fully white.
        val curve = IntArray(256) { v -> ((v - 60) * 255 / 175).coerceIn(0, 255) }
        val half = cell / 2f
        for (y in 0 until h) {
            val fy = ((y - half) / cell).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x - half) / cell).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(gw - 1, x0 + 1)
                val tx = fx - x0
                val top = paper[y0 * gw + x0] * (1 - tx) + paper[y0 * gw + x1] * tx
                val bottom = paper[y1 * gw + x0] * (1 - tx) + paper[y1 * gw + x1] * tx
                val scale = 255f / (top * (1 - ty) + bottom * ty)

                val p = px[y * w + x]
                val r = minOf(255, (((p shr 16) and 255) * scale).toInt())
                val g = minOf(255, (((p shr 8) and 255) * scale).toInt())
                val b = minOf(255, ((p and 255) * scale).toInt())
                px[y * w + x] = when (mode) {
                    COLOR -> (255 shl 24) or (curve[r] shl 16) or (curve[g] shl 8) or curve[b]
                    GRAY -> {
                        val v = curve[(r * 77 + g * 150 + b * 29) shr 8]
                        (255 shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    else -> if ((r * 77 + g * 150 + b * 29) shr 8 < 165) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
