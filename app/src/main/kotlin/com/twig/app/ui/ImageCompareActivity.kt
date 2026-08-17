package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.databinding.ActivityImageCompareBinding
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片左右对比:两张图并排,缩放/平移联动。
 *
 * 解码走 [decodeImage] + [decodeRegion](与图片查看器同一套),所以放大后照样按区域重解
 * 高清块——"放大比细节"正是这个页面的主要用途,糊着就没意义了。
 *
 * 同步用的是**归一化图片坐标**([ZoomableImageView.viewport]):两张尺寸不同的图
 * (同一张照片改过分辨率是最常见的情形)也要能对齐同一块内容,按视图像素位移同步会立刻错位。
 *
 * 竖屏上下并排而不是左右:横向手势整条留给图片平移,不与"切换侧"抢;半屏宽的照片也根本看不清。
 */
class ImageCompareActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageCompareBinding
    private var fileA: XFile? = null
    private var fileB: XFile? = null
    private var decA: Decoded? = null
    private var decB: Decoded? = null
    private var sync = true
    private var itemSync: MenuItem? = null
    // ★ 两侧各一个 token:共用一个的话,B 发起高清块请求会把 A 刚要回填的那块判成过期
    private var tokenA = 0
    private var tokenB = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageCompareBinding.inflate(layoutInflater)
        setContentView(b.root)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.toolbar.title = title
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(title))
        buildMenu()
        applyOrientation()

        val a = fileFrom(EXTRA_A_SCHEME, EXTRA_A_PATH, EXTRA_A_SIZE, EXTRA_A_TIME)
        val c = fileFrom(EXTRA_B_SCHEME, EXTRA_B_PATH, EXTRA_B_SIZE, EXTRA_B_TIME)
        if (a == null || c == null) return finish()
        fileA = a
        fileB = c

        // 默认按原图真实像素显示,超过容器才缩到放得下——对比时最想看的是"这一张
        // 本身多大/多清楚",FIT 会把小图拉大、看着跟大图一样清楚,反而误导
        b.imageA.initialMode = ZoomableImageView.MODE_FIT_ACTUAL
        b.imageB.initialMode = ZoomableImageView.MODE_FIT_ACTUAL
        wireView(b.imageA, b.imageB) { decA }
        wireView(b.imageB, b.imageA) { decB }
        load()
    }

    private fun fileFrom(schemeKey: String, pathKey: String, sizeKey: String, timeKey: String): XFile? {
        val scheme = intent.getStringExtra(schemeKey) ?: return null
        val path = intent.getStringExtra(pathKey) ?: return null
        return XFile(
            scheme, path, isDir = false,
            size = intent.getLongExtra(sizeKey, 0L),
            lastModified = intent.getLongExtra(timeKey, 0L),
        )
    }

    /** 两侧共用的接线:视口变化推给对侧,放大时按区域重解高清块。 */
    private fun wireView(self: ZoomableImageView, other: ZoomableImageView, dec: () -> Decoded?) {
        self.onViewport = {
            if (sync) self.viewport()?.let { other.applyViewport(it) }
        }
        self.onNeedHiRes = { rect, scale -> loadHiRes(self, dec(), rect, scale, self === b.imageA) }
    }

    private fun load() {
        val a = fileA ?: return
        val c = fileB ?: return
        lifecycleScope.launch {
            val (da, db) = withContext(Dispatchers.IO) {
                runCatching { decodeImage(this@ImageCompareActivity, a) }.getOrNull() to
                    runCatching { decodeImage(this@ImageCompareActivity, c) }.getOrNull()
            }
            decA = da
            decB = db
            da?.let { b.imageA.setImage(it.bmp, it.actual) }
            db?.let { b.imageB.setImage(it.bmp, it.actual) }
            b.infoA.text = sideInfo(getString(R.string.compare_side_left), a, da)
            b.infoB.text = sideInfo(getString(R.string.compare_side_right), c, db)
            b.toolbar.subtitle = summary(da, db)
        }
    }

    /** 一侧的信息条:`左 · 1920×1080 · 2.3 MB · 时间`;解不出来就直说,别留一片黑让人猜。 */
    private fun sideInfo(side: String, f: XFile, d: Decoded?): String {
        val dim = if (d == null) getString(R.string.img_cmp_undecodable)
        else "${(d.bmp.width * d.actual).toInt()}×${(d.bmp.height * d.actual).toInt()}"
        return "$side · $dim · ${Format.size(f.size)} · ${Format.time(f.lastModified)}"
    }

    /** 副标题只说结论:尺寸一不一样、哪边像素多——并排看时最先想知道的就是这个。 */
    private fun summary(da: Decoded?, db: Decoded?): String {
        if (da == null || db == null) return ""
        val pa = (da.bmp.width * da.actual).toLong() * (da.bmp.height * da.actual).toLong()
        val pb = (db.bmp.width * db.actual).toLong() * (db.bmp.height * db.actual).toLong()
        return when {
            pa == pb -> getString(R.string.img_cmp_same_size)
            pa > pb -> getString(R.string.img_cmp_bigger, getString(R.string.compare_side_left))
            else -> getString(R.string.img_cmp_bigger, getString(R.string.compare_side_right))
        }
    }

    private fun loadHiRes(view: ZoomableImageView, d: Decoded?, rect: RectF, scale: Float, isA: Boolean) {
        if (d == null || d.actual <= 1f) return // 原图就没有更多像素可挖
        val vw = view.width.coerceAtLeast(1)
        val vh = view.height.coerceAtLeast(1)
        val token = if (isA) ++tokenA else ++tokenB
        lifecycleScope.launch {
            val bmp = runCatching {
                // 显示位图已经烧进了 EXIF 旋转,这里不再做 autoFit,总旋转就是 exifRot
                withContext(Dispatchers.IO) { decodeRegion(d, d.exifRot, rect, scale, vw, vh) }
            }.getOrNull()
            if (token != (if (isA) tokenA else tokenB) || bmp == null) return@launch
            view.setHiRes(bmp, rect)
        }
    }

    private fun buildMenu() {
        val m = b.toolbar.menu
        m.showIcons()
        itemSync = m.add(getString(R.string.img_cmp_sync)).apply {
            isCheckable = true
            isChecked = true
            icon = ContextCompat.getDrawable(this@ImageCompareActivity, R.drawable.ic_sync)?.mutate()
                ?.apply { setTint(ContextCompat.getColor(this@ImageCompareActivity, R.color.white)) }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                sync = !sync
                it.isChecked = sync
                // 重新对上:关掉再打开时,两侧应当立刻回到同一视口
                if (sync) b.imageA.viewport()?.let { v -> b.imageB.applyViewport(v) }
                true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        b.panes.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // 两侧在主轴上各占一半:横屏是宽,竖屏是高
        for (side in listOf(b.sideA, b.sideB)) {
            side.layoutParams = LinearLayout.LayoutParams(
                if (land) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (land) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1f,
            )
        }
        b.divider.layoutParams = LinearLayout.LayoutParams(
            if (land) dp(1) else LinearLayout.LayoutParams.MATCH_PARENT,
            if (land) LinearLayout.LayoutParams.MATCH_PARENT else dp(1),
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_A_SCHEME = "a_scheme"
        private const val EXTRA_A_PATH = "a_path"
        private const val EXTRA_A_SIZE = "a_size"
        private const val EXTRA_A_TIME = "a_time"
        private const val EXTRA_B_SCHEME = "b_scheme"
        private const val EXTRA_B_PATH = "b_path"
        private const val EXTRA_B_SIZE = "b_size"
        private const val EXTRA_B_TIME = "b_time"
        private const val EXTRA_TITLE = "title"

        fun start(ctx: Context, left: XFile, right: XFile, title: String) {
            ctx.startActivity(
                Intent(ctx, ImageCompareActivity::class.java)
                    .putExtra(EXTRA_A_SCHEME, left.scheme)
                    .putExtra(EXTRA_A_PATH, left.path)
                    .putExtra(EXTRA_A_SIZE, left.size)
                    .putExtra(EXTRA_A_TIME, left.lastModified)
                    .putExtra(EXTRA_B_SCHEME, right.scheme)
                    .putExtra(EXTRA_B_PATH, right.path)
                    .putExtra(EXTRA_B_SIZE, right.size)
                    .putExtra(EXTRA_B_TIME, right.lastModified)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
