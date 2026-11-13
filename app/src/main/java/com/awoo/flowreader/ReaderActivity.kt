package com.awoo.flowreader

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Seamless reader: every episode of a series sits in ONE long scroll, separated by a small
 * header. Tap the lower part of the screen to scroll down, the top strip to scroll up.
 */
class ReaderActivity : AppCompatActivity() {

    private class Session(val pfd: ParcelFileDescriptor, val r: PdfRenderer) {
        fun close() {
            try { r.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    private class Seg(val pdf: Pdf) {
        var pages = 0
        var headerIdx = -1
        var startIdx = -1
        var lastIdx = -1
        var finished = false
        var failed = false
    }

    private sealed class Item {
        class Header(val seg: Int) : Item()
        class Page(val seg: Int, val page: Int, val ratio: Float) : Item()
        object Footer : Item()
    }

    private class TextVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    private class PageVH(val img: ImageView) : RecyclerView.ViewHolder(img)

    private lateinit var store: PdfStore
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var bar: ProgressBar
    private lateinit var label: TextView

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val sessions = HashMap<Int, Session>() // only touched on the exec thread
    private val items = ArrayList<Item>()
    private val adapter = FlowAdapter()

    private var segs: List<Seg> = emptyList()
    private var nextToLoad = 0
    private var startSeg = 0
    private var loading = false
    private var loadedAny = false
    private var current = -1
    private var wasMoving = false
    private val hideLabel = Runnable { label.visibility = android.view.View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PdfStore(this)

        val bg = ContextCompat.getColor(this, R.color.reader_bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        lm = LinearLayoutManager(this)
        rv = RecyclerView(this)
        rv.layoutManager = lm
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setItemViewCacheSize(4)
        root.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
        root.addView(bar, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP))

        label = TextView(this)
        label.setTextColor(Color.WHITE)
        label.textSize = 14f
        label.setPadding(dp(16), dp(8), dp(16), dp(8))
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(18).toFloat()
        }
        label.visibility = android.view.View.GONE
        val llp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        llp.topMargin = dp(16)
        root.addView(label, llp)
        setContentView(root)

        items.add(Item.Footer) // shows "Opening..." until the first episode is in

        // Tap zones. The listener only watches; scrolling still works normally.
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (wasMoving || !loadedAny) return false
                val h = rv.height.toFloat()
                when {
                    e.y > h * 0.7f -> scrollPage(1)
                    e.y < h * 0.2f -> scrollPage(-1)
                    else -> showLabel()
                }
                return true
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    wasMoving = r.scrollState != RecyclerView.SCROLL_STATE_IDLE
                }
                gd.onTouchEvent(e)
                return false
            }
        })
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                trackProgress()
                maybeLoadMore()
            }

            override fun onScrollStateChanged(r: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) saveProgress()
            }
        })

        val startPath = savedInstanceState?.getString("cur") ?: intent.getStringExtra("path")
        if (startPath == null) {
            finish()
            return
        }
        exec.execute {
            val start = File(startPath)
            val key = SeriesParser.parse(start.name).key
            var list = store.scan().filter { it.info.key == key }
                .sortedWith(compareBy<Pdf>({ it.info.episode ?: Int.MAX_VALUE }, { it.modified }))
            if (list.none { it.file.path == start.path }) list = listOf(Pdf(start, start.lastModified()))
            val segList = list.map { Seg(it) }
            val si = segList.indexOfFirst { it.pdf.file.path == start.path }.coerceAtLeast(0)
            main.post {
                if (!isDestroyed) {
                    segs = segList
                    startSeg = si
                    nextToLoad = si
                    loadNext()
                }
            }
        }
    }

    // Loading episodes

    private fun sessionFor(idx: Int): Session {
        sessions[idx]?.let { return it }
        val pfd = ParcelFileDescriptor.open(segs[idx].pdf.file, ParcelFileDescriptor.MODE_READ_ONLY)
        val s = try {
            Session(pfd, PdfRenderer(pfd))
        } catch (t: Throwable) {
            try { pfd.close() } catch (e: Exception) { }
            throw t
        }
        sessions[idx] = s
        while (sessions.size > 3) {
            val far = sessions.keys.filter { it != idx }.maxByOrNull { abs(it - idx) } ?: break
            sessions.remove(far)?.close()
        }
        return s
    }

    private fun loadNext() {
        if (loading || nextToLoad >= segs.size) return
        val idx = nextToLoad
        loading = true
        exec.execute {
            var ratios: FloatArray? = null
            try {
                val s = sessionFor(idx)
                val n = s.r.pageCount
                val arr = FloatArray(n)
                for (i in 0 until n) {
                    val p = s.r.openPage(i)
                    try {
                        arr[i] = if (p.width > 0) p.height.toFloat() / p.width.toFloat() else 1.4f
                    } finally {
                        p.close()
                    }
                }
                if (n > 0) ratios = arr
            } catch (t: Throwable) {
                ratios = null
            }
            val result = ratios
            main.post { if (!isDestroyed) onSegLoaded(idx, result) }
        }
    }

    private fun onSegLoaded(idx: Int, ratios: FloatArray?) {
        loading = false
        val seg = segs[idx]
        if (ratios == null && !loadedAny) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val at = items.size - 1 // insert in front of the footer
        val add = ArrayList<Item>()
        add.add(Item.Header(idx))
        if (ratios == null) {
            seg.failed = true
            seg.finished = true
        } else {
            seg.pages = ratios.size
            for (i in ratios.indices) add.add(Item.Page(idx, i, ratios[i]))
        }
        seg.headerIdx = at
        seg.startIdx = at + 1
        seg.lastIdx = at + seg.pages
        items.addAll(at, add)
        adapter.notifyItemRangeInserted(at, add.size)
        nextToLoad = idx + 1

        if (!loadedAny) {
            loadedAny = true
            val file = seg.pdf.file
            var page = if (store.isRead(file)) 0 else store.lastPage(file)
            if (page >= seg.pages) page = 0
            val pos = if (page <= 0) seg.headerIdx else seg.startIdx + page
            lm.scrollToPositionWithOffset(pos, 0)
            current = idx
            store.setLast(file)
            store.touchToday()
        }
        adapter.notifyItemChanged(items.size - 1)
        rv.post {
            trackProgress()
            maybeLoadMore()
        }
    }

    private fun maybeLoadMore() {
        if (!loadedAny) return
        val lv = lm.findLastVisibleItemPosition()
        if (lv >= items.size - 1 - 6) loadNext()
    }

    // Progress, read marks, resume

    private fun segOf(it: Item): Int = when (it) {
        is Item.Header -> it.seg
        is Item.Page -> it.seg
        else -> nextToLoad - 1
    }

    private fun trackProgress() {
        if (!loadedAny || items.isEmpty()) return
        val fv = lm.findFirstVisibleItemPosition()
        val lv = lm.findLastVisibleItemPosition()
        if (fv < 0 || fv >= items.size) return
        val fi = items[fv]
        val cur = segOf(fi)
        if (cur !in segs.indices) return
        if (cur != current) {
            current = cur
            store.setLast(segs[cur].pdf.file)
            store.touchToday()
            showLabel()
        }
        // Mark an episode as read once the bottom of its last page is on screen
        var k = cur
        while (k <= cur + 1 && k < segs.size) {
            val s = segs[k]
            if (!s.finished && s.lastIdx >= 0 && reachedEnd(s, lv)) markDone(s)
            k++
        }
        val s = segs[cur]
        bar.progress = if (fi is Item.Page && s.pages > 0) (fi.page + 1) * 1000 / s.pages else 0
    }

    private fun reachedEnd(s: Seg, lv: Int): Boolean {
        if (lv > s.lastIdx) return true
        if (lv == s.lastIdx) {
            val v = lm.findViewByPosition(lv) ?: return false
            return v.bottom <= rv.height + dp(2)
        }
        return false
    }

    private fun markDone(s: Seg) {
        s.finished = true
        store.setRead(s.pdf.file, true)
        store.recordFinish(s.pdf.file, s.pages)
        store.setLastPage(s.pdf.file, 0)
    }

    private fun saveProgress() {
        if (!loadedAny || items.isEmpty()) return
        val fv = lm.findFirstVisibleItemPosition()
        if (fv < 0 || fv >= items.size) return
        val it = items[fv]
        if (it is Item.Page) {
            val s = segs[it.seg]
            if (!s.finished) store.setLastPage(s.pdf.file, it.page)
        }
    }

    private fun showLabel() {
        if (!loadedAny || current !in segs.indices) return
        val s = segs[current]
        val fv = lm.findFirstVisibleItemPosition()
        val page = if (fv in items.indices) (items[fv] as? Item.Page)?.page ?: 0 else 0
        val name = s.pdf.info.label ?: s.pdf.info.name
        label.text = if (s.pages > 0) "$name  ·  ${page + 1} / ${s.pages}" else name
        label.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideLabel)
        main.postDelayed(hideLabel, 1800)
    }

    private fun scrollPage(dir: Int) {
        rv.smoothScrollBy(0, dir * (rv.height * 0.85f).toInt(), DecelerateInterpolator(), 260)
    }

    // Rendering

    private fun render(key: String, seg: Int, page: Int, w: Int, h: Int, img: ImageView) {
        exec.execute {
            if (img.tag != key) return@execute
            try {
                val s = sessionFor(seg)
                var bw = w
                var bh = h
                if (bh > 4096) {
                    val f = 4096f / bh
                    bh = 4096
                    bw = (w * f).toInt().coerceAtLeast(1)
                }
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val p = s.r.openPage(page)
                try {
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    p.close()
                }
                cache.put(key, bmp)
                main.post { if (img.tag == key) img.setImageBitmap(bmp) }
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
            } catch (t: Throwable) {
            }
        }
    }

    // Adapter

    private inner class FlowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Item.Header -> 0
            is Item.Page -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val img = ImageView(parent.context)
                img.scaleType = ImageView.ScaleType.FIT_XY
                img.setBackgroundColor(Color.WHITE)
                img.layoutParams = RecyclerView.LayoutParams(MATCH, dp(300))
                return PageVH(img)
            }
            val tv = TextView(parent.context)
            tv.gravity = Gravity.CENTER
            tv.setTextColor(0xFFE6E6EA.toInt())
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setPadding(dp(24), dp(8), dp(24), dp(8))
            if (viewType == 0) {
                tv.textSize = 15f
                tv.maxLines = 2
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.setBackgroundColor(0xFF2B2D31.toInt())
                tv.layoutParams = RecyclerView.LayoutParams(MATCH, dp(72))
            } else {
                tv.textSize = 16f
                tv.layoutParams = RecyclerView.LayoutParams(MATCH, dp(240))
                tv.setOnClickListener { if (loadedAny) finish() }
            }
            return TextVH(tv)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is Item.Header -> {
                    val info = segs[it.seg].pdf.info
                    val tv = (holder as TextVH).tv
                    tv.text = if (segs[it.seg].failed) {
                        (info.label ?: info.name) + "\nCould not open this episode"
                    } else {
                        (info.label ?: "Next") + "\n" + info.name
                    }
                }
                is Item.Page -> {
                    val img = (holder as PageVH).img
                    val w = if (rv.width > 0) rv.width else resources.displayMetrics.widthPixels
                    val h = (w * it.ratio).toInt().coerceAtLeast(1)
                    val lp = img.layoutParams
                    if (lp.height != h || lp.width != MATCH) {
                        lp.width = MATCH
                        lp.height = h
                        img.layoutParams = lp
                    }
                    val key = "${it.seg}:${it.page}"
                    img.tag = key
                    val hit = cache.get(key)
                    if (hit != null) {
                        img.setImageBitmap(hit)
                    } else {
                        img.setImageDrawable(null)
                        render(key, it.seg, it.page, w, h, img)
                    }
                }
                else -> {
                    val tv = (holder as TextVH).tv
                    tv.text = if (!loadedAny) {
                        "Opening..."
                    } else {
                        val n = segs.firstOrNull()?.pdf?.info?.name ?: ""
                        "End of $n\n\nTap here to go back to your shelf"
                    }
                }
            }
        }
    }

    // Lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveProgress()
        if (current in segs.indices) outState.putString("cur", segs[current].pdf.file.path)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        exec.execute {
            for (s in sessions.values) s.close()
            sessions.clear()
        }
        exec.shutdown()
    }
}
