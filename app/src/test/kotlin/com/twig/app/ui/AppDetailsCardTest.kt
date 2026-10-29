package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.AppDetails
import com.twig.app.FileInfo
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppDetailsCardTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val target = AppDetails.Action.Activity("com.example.inspected", "com.example.inspected.PublicActivity")
    private fun node(packageName: String = "example", activityTitle: String = "PublicActivity") = PaneViewModel.InfoNode(
        XFile("apps", "/user/$packageName", false), 2,
        FileInfo.Details(listOf(FileInfo.Section("App", listOf("Package" to packageName),
            AppDetails.Kind.values().map { kind -> AppDetails.Group(kind, listOf(AppDetails.Entry(
                when (kind) {
                    AppDetails.Kind.ACTIVITIES -> activityTitle
                    AppDetails.Kind.SIGNATURE -> "Twig Test"
                    else -> "${kind.name} detail"
                },
                if (kind == AppDetails.Kind.SIGNATURE) listOf("Serial number" to "1234AB", "Signature algorithm" to "SHA256withRSA")
                    else listOf("Value" to "Details"),
                action = when (kind) {
                    AppDetails.Kind.ACTIVITIES -> target
                    AppDetails.Kind.SHORTCUTS -> AppDetails.Action.Intents(listOf(
                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setComponent(ComponentName(target.packageName, target.className)).putExtra("shortcut", "chat")))
                    else -> null
                },
                subtitle = when (kind) {
                    AppDetails.Kind.ACTIVITIES -> "ACTIVITIES detail"
                    AppDetails.Kind.SHORTCUTS -> "Shortcut label"
                    else -> null
                },
                enabled = kind != AppDetails.Kind.SERVICES,
                exported = kind == AppDetails.Kind.SERVICES || kind == AppDetails.Kind.PROVIDERS))) }))))
    private fun adapter() = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
    private fun texts(view: View): List<TextView> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
    }
    private fun assertDetailsGray(view: TextView) {
        val text = view.text as Spanned
        val start = text.indexOf('\n') + 1
        assertTrue(start > 0)
        val color = ContextCompat.getColor(app, R.color.text_secondary)
        assertTrue(text.getSpans(start, text.length, ForegroundColorSpan::class.java).any {
            it.foregroundColor == color && text.getSpanStart(it) <= start && text.getSpanEnd(it) == text.length
        })
    }
    private fun header(holder: RecyclerView.ViewHolder, kind: AppDetails.Kind) = texts(holder.itemView)
        .single { it.text.toString() == app.getString(kind.title) +
            if (kind == AppDetails.Kind.SIGNATURE) " (Twig Test)" else " (1)" }
    private fun layout(holder: RecyclerView.ViewHolder, widthDp: Int = 400) {
        val row = holder.itemView
        val width = (widthDp * app.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
    }
    private fun tapFarEdge(holder: RecyclerView.ViewHolder, view: View) {
        layout(holder)
        val x = view.width - 2f
        val y = view.height / 2f
        assertTrue(view.width > 0 && view.height > 0)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 10, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun holder(adapter: FileAdapter): RecyclerView.ViewHolder {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(ContextThemeWrapper(activity, R.style.Theme_Twig))
        activity.setContentView(parent)
        return adapter.createViewHolder(parent, adapter.getItemViewType(0)).also {
            parent.addView(it.itemView)
        }
    }
    private fun submitAndAwait(adapter: FileAdapter, nodes: List<PaneViewModel.Node>) {
        var committed = false
        adapter.submitList(nodes) { committed = true }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!committed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("the list diff must land before binding", committed)
    }

    @Test fun `all six groups start collapsed and toggle at the far edge of the full row`() {
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        layout(holder)
        assertTrue(header(holder, AppDetails.Kind.SIGNATURE).top < header(holder, AppDetails.Kind.ACTIVITIES).top)
        assertTrue(header(holder, AppDetails.Kind.ACTIVITIES).top < header(holder, AppDetails.Kind.SHORTCUTS).top)
        assertTrue(header(holder, AppDetails.Kind.SHORTCUTS).top < header(holder, AppDetails.Kind.SERVICES).top)
        for (kind in AppDetails.Kind.values()) {
            assertNotNull(header(holder, kind))
            assertFalse(texts(holder.itemView).any { it.text.startsWith("${kind.name} detail") })
        }
        tapFarEdge(holder, header(holder, AppDetails.Kind.ACTIVITIES))
        assertTrue(texts(holder.itemView).any { it.text.startsWith("PublicActivity") })
        tapFarEdge(holder, header(holder, AppDetails.Kind.SERVICES))
        assertTrue(texts(holder.itemView).any { it.text.startsWith("SERVICES detail") })
        tapFarEdge(holder, header(holder, AppDetails.Kind.ACTIVITIES))
        assertFalse(texts(holder.itemView).any { it.text.startsWith("PublicActivity") })
        assertTrue(texts(holder.itemView).any { it.text.startsWith("SERVICES detail") })
    }

    @Test fun `actionable activities are colored and open directly anywhere on the entry row`() {
        val adapter = adapter()
        adapter.submitList(listOf(node("one"), node("two")))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.ACTIVITIES))
        tapFarEdge(holder, header(holder, AppDetails.Kind.SERVICES))
        val activity = texts(holder.itemView).single { it.text.startsWith("PublicActivity") }
        val service = texts(holder.itemView).single { it.text.startsWith("SERVICES detail") }
        assertEquals(ContextCompat.getColor(app, R.color.primary), activity.currentTextColor)
        assertEquals(ContextCompat.getColor(app, R.color.text_secondary), service.currentTextColor)
        assertTrue(activity.isClickable)
        assertFalse(service.isClickable)
        assertTrue(activity.isLongClickable)
        assertFalse(service.isLongClickable)
        assertTrue(activity.text.contains("\n"))
        assertFalse(activity.text.contains("Open"))
        assertEquals("PublicActivity\nACTIVITIES detail", activity.text.toString())
        assertDetailsGray(activity)
        assertEquals("SERVICES detail · exported", service.text.toString())
        layout(holder)
        assertEquals("activity label and class occupy separate lines", 2, activity.lineCount)
        tapFarEdge(holder, activity)
        assertEquals(ComponentName(target.packageName, target.className), shadowOf(app).nextStartedActivity.component)
        adapter.onBindViewHolder(holder, 1)
        assertFalse(texts(holder.itemView).any { it.text.startsWith("PublicActivity") })
        adapter.onBindViewHolder(holder, 0)
        assertTrue(texts(holder.itemView).any { it.text.startsWith("PublicActivity") })
        submitAndAwait(adapter, emptyList())
        submitAndAwait(adapter, listOf(node("one")))
        adapter.onBindViewHolder(holder, 0)
        assertFalse(texts(holder.itemView).any { it.text.startsWith("PublicActivity") })
    }

    @Test fun `launchable shortcuts use the action color and launch from the far edge of the row`() {
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.SHORTCUTS))
        val row = texts(holder.itemView).single { it.text.startsWith("SHORTCUTS detail") }
        assertEquals(ContextCompat.getColor(app, R.color.primary), row.currentTextColor)
        assertTrue(row.isClickable)
        assertEquals("SHORTCUTS detail\nShortcut label", row.text.toString())
        assertDetailsGray(row)
        tapFarEdge(holder, row)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(ComponentName(target.packageName, target.className), intent.component)
        assertEquals("chat", intent.getStringExtra("shortcut"))
    }

    @Test fun `signature omits certificate heading and uses gray field names with normal values`() {
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.SIGNATURE))
        val row = texts(holder.itemView).single { it.text.startsWith("Serial number") }
        assertEquals("Serial number\u00A0\u00A01234AB\nSignature algorithm\u00A0\u00A0SHA256withRSA", row.text.toString())
        val text = row.text as Spanned
        fun hasColor(start: Int, end: Int, color: Int) = text.getSpans(start, end, ForegroundColorSpan::class.java).any {
            it.foregroundColor == ContextCompat.getColor(app, color) && text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end
        }
        assertTrue(hasColor(0, "Serial number".length, R.color.text_secondary))
        val value = text.indexOf("1234AB")
        assertEquals(ContextCompat.getColor(app, R.color.text_primary), row.currentTextColor)
        assertTrue(text.getSpans(value, value + 6, ForegroundColorSpan::class.java).isEmpty())
        assertFalse(texts(holder.itemView).any { it.text.contains("SIGNATURE detail") || it.text.contains("Certificate 1") })
    }

    @Test fun `provider details appear below the name and exported marker without adding a launch action`() {
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.PROVIDERS))
        val row = texts(holder.itemView).single { it.text.startsWith("PROVIDERS detail") }
        assertEquals("PROVIDERS detail · exported\nValue\u00A0\u00A0Details", row.text.toString())
        assertDetailsGray(row)
        assertFalse(row.isClickable)
        tapFarEdge(holder, header(holder, AppDetails.Kind.RECEIVERS))
        assertEquals("RECEIVERS detail", texts(holder.itemView).single { it.text.startsWith("RECEIVERS detail") }.text.toString())
    }

    @Test fun `wrapped app signature and provider values align with the start of each value`() {
        val longValue = "1234567890ABCDEF:".repeat(15) + "\n" + "FEDCBA0987654321:".repeat(8)
        val original = node(packageName = longValue)
        val section = original.details!!.sections.single()
        val groups = section.appGroups.map { group ->
            if (group.kind == AppDetails.Kind.SIGNATURE || group.kind == AppDetails.Kind.PROVIDERS)
                group.copy(entries = group.entries.map { it.copy(rows = listOf(
                    (if (group.kind == AppDetails.Kind.SIGNATURE) "SHA-256" else "Authority") to longValue)) })
            else group
        }
        val adapter = adapter()
        adapter.submitList(listOf(original.copy(details = FileInfo.Details(listOf(section.copy(appGroups = groups))))))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.SIGNATURE))
        tapFarEdge(holder, header(holder, AppDetails.Kind.PROVIDERS))
        for (width in listOf(180, 400)) {
            layout(holder, width)
            val rows = texts(holder.itemView).filter { it.text.contains(longValue) }
            assertEquals(3, rows.size)
            for (row in rows) {
                val start = row.text.indexOf(longValue)
                val first = row.layout.getLineForOffset(start)
                val last = row.layout.getLineForOffset(start + longValue.length - 1)
                assertTrue(last > first)
                val valueX = row.layout.getPrimaryHorizontal(start)
                for (line in first + 1..last)
                    assertEquals("${row.text.take(20)} at width $width, line $line", valueX,
                        row.layout.getPrimaryHorizontal(row.layout.getLineStart(line)), 1f)
                assertTrue(row.text.toString().contains(longValue))
            }
        }
    }

    @Test fun `Unix attributes render on their own tab after General`() {
        val basicTitle = app.getString(R.string.info_section_basic)
        val unixTitle = app.getString(R.string.info_section_unix)
        val details = FileInfo.Details(listOf(
            FileInfo.Section(basicTitle, listOf(app.getString(R.string.info_name) to "file.bin")),
            FileInfo.Section(unixTitle, listOf(app.getString(R.string.info_permissions) to "rwxr-xr-x (0755)",
                app.getString(R.string.info_uid) to "10001")),
        ))
        val original = node().copy(details = details)
        val adapter = adapter()
        adapter.submitList(listOf(original))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        val row = holder.itemView.findViewById<TextView>(R.id.info_body)
        assertTrue(row.text.contains("file.bin"))
        assertFalse(row.text.contains("0755"))
        (holder as FileAdapter.InfoVH).bind(original.copy(tab = 1))
        layout(holder, 240)
        assertTrue(row.text.contains("rwxr-xr-x (0755)"))
        assertTrue(row.text.contains("10001"))
        assertFalse(row.text.contains("file.bin"))
    }

    @Test fun `basic EXIF media and hash tabs share wrapped-value alignment and keep copy actions`() {
        val longValue = "1234567890ABCDEF:".repeat(15) + "\n" + "FEDCBA0987654321:".repeat(8)
        val original = node().copy(details = FileInfo.Details(listOf(
            FileInfo.Section("Basic", listOf(app.getString(R.string.info_name) to longValue)),
            FileInfo.Section("EXIF", listOf("Camera" to longValue)),
            FileInfo.Section("Media", listOf("Track" to longValue)))), hashRows = listOf("SHA-256" to longValue))
        val adapter = adapter()
        adapter.submitList(listOf(original))
        val holder = holder(adapter)
        for (tab in 0..3) {
            submitAndAwait(adapter, listOf(original.copy(tab = tab)))
            adapter.bindViewHolder(holder, 0)
            layout(holder, 180)
            val row = holder.itemView.findViewById<TextView>(R.id.info_body)
            val start = row.text.indexOf(longValue)
            assertTrue(start >= 0)
            val first = row.layout.getLineForOffset(start)
            val last = row.layout.getLineForOffset(start + longValue.length - 1)
            val valueX = row.layout.getPrimaryHorizontal(start)
            assertTrue(last > first)
            for (line in first + 1..last)
                assertEquals("tab $tab continuation aligns with value", valueX,
                    row.layout.getPrimaryHorizontal(row.layout.getLineStart(line)), 1f)
            if (tab == 0) {
                val copy = (row.text as Spanned).getSpans(0, row.text.length, android.text.style.ClickableSpan::class.java).single()
                copy.onClick(row)
                val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(longValue, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
        }
    }

    @Test fun `wrapped names paths and link targets keep their copy icon beside the final value character`() {
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        val names = listOf(20, 31, 45, 64, 93).map { "abcdefghijklmnopqrstuvwxyz".repeat(4).take(it) + ".txt" } +
            listOf("长文件名称".repeat(8) + "👍🏽", "long_file_name_".repeat(4) + "👩‍💻")
        for (name in names) {
            val path = "/storage/emulated/0/Download/$name"
            val info = node().copy(details = FileInfo.Details(listOf(FileInfo.Section("Basic", listOf(
                app.getString(R.string.info_name) to name, app.getString(R.string.info_path) to path,
                app.getString(R.string.info_link_target) to "../target/$name")))))
            submitAndAwait(adapter, listOf(info))
            adapter.bindViewHolder(holder, 0)
            for (width in listOf(180, 220, 300, 400)) {
                layout(holder, width)
                val row = holder.itemView.findViewById<TextView>(R.id.info_body)
                val text = row.text as Spanned
                val icons = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                assertEquals(3, icons.size)
                val clicks = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
                assertEquals(3, clicks.size)
                for ((index, value) in listOf(name, path, "../target/$name").withIndex()) {
                    clicks[index].onClick(row)
                    val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
                    assertEquals(value, clipboard.primaryClip!!.getItemAt(0).text.toString())
                    val valueStart = text.indexOf(value, if (index == 0) 0 else text.indexOf('\n') + 1)
                    val valueLine = row.layout.getLineForOffset(valueStart + value.length - 1)
                    val iconLine = row.layout.getLineForOffset(text.getSpanEnd(icons[index]) - 1)
                    assertEquals("$width dp, ${name.length} characters: copy icon follows the value", valueLine, iconLine)
                }
            }
        }
    }

    @Test @Config(qualifiers = "zh")
    fun `Chinese property labels stay together before long values`() {
        val keys = listOf(app.getString(R.string.info_name), app.getString(R.string.info_path), "修改时间")
        val values = listOf("a.txt", "Make.Zhonghe.Great.Again.2026.2160p.HQ.WEB-DL.H265.HDR.60fps.DTS5.1-CHDWEB.mkv",
            "a".repeat(120), "长名称".repeat(30))
        val adapter = adapter()
        adapter.submitList(listOf(node()))
        val holder = holder(adapter)
        for (value in values) {
            val rows = keys.map { it to value }
            submitAndAwait(adapter, listOf(node().copy(details = FileInfo.Details(listOf(FileInfo.Section("Basic", rows))))))
            adapter.bindViewHolder(holder, 0)
            for (width in listOf(160, 180, 220, 300, 400)) {
                layout(holder, width)
                val row = holder.itemView.findViewById<TextView>(R.id.info_body)
                var from = 0
                for (key in keys) {
                    val start = row.text.indexOf(key, from)
                    val valueStart = row.text.indexOf(value, start + key.length)
                    val first = row.layout.getLineForOffset(start)
                    assertEquals("$width dp: $key stays on one line", first,
                        row.layout.getLineForOffset(start + key.length - 1))
                    assertEquals("$width dp: $key value starts beside its key", first,
                        row.layout.getLineForOffset(valueStart))
                    val x = row.layout.getPrimaryHorizontal(valueStart)
                    val last = row.layout.getLineForOffset(valueStart + value.length - 1)
                    for (line in first + 1..last)
                        assertEquals(x, row.layout.getPrimaryHorizontal(row.layout.getLineStart(line)), 1f)
                    from = valueStart + value.length
                }
            }
        }
    }

    @Test fun `hash values begin beside their labels even when the digest needs wrapping`() {
        val hashes = listOf("MD5" to "a1b2c3d4".repeat(4), "SHA-1" to "a1b2c3d4".repeat(5),
            "SHA-256" to "a1b2c3d4".repeat(8))
        val original = node()
        val info = original.copy(tab = original.details!!.sections.size, hashRows = hashes)
        val adapter = adapter()
        adapter.submitList(listOf(info))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        for (width in listOf(180, 220, 300, 400)) {
            layout(holder, width)
            val row = holder.itemView.findViewById<TextView>(R.id.info_body)
            for ((key, value) in hashes) {
                val keyStart = row.text.indexOf(key)
                val valueStart = row.text.indexOf(value)
                assertEquals("$width dp: $key starts on its label's line",
                    row.layout.getLineForOffset(keyStart), row.layout.getLineForOffset(valueStart))
                val first = row.layout.getLineForOffset(valueStart)
                val last = row.layout.getLineForOffset(valueStart + value.length - 1)
                for (line in first + 1..last)
                    assertEquals(row.layout.getPrimaryHorizontal(valueStart),
                        row.layout.getPrimaryHorizontal(row.layout.getLineStart(line)), 1f)
            }
        }
    }

    @Test fun `property first lines fill the available space before wrapping words`() {
        val value = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau"
        val info = node().copy(details = FileInfo.Details(listOf(FileInfo.Section("Media", listOf("Note" to value)))))
        val adapter = adapter()
        adapter.submitList(listOf(info))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        for (width in listOf(180, 220, 300, 400)) {
            layout(holder, width)
            val row = holder.itemView.findViewById<TextView>(R.id.info_body)
            assertTrue(row.lineCount > 1)
            val end = row.layout.getLineEnd(0)
            val first = row.text.subSequence(0, end).toString().trimEnd()
            val nextWord = row.text.subSequence(end, row.text.length).toString().trimStart().substringBefore(' ')
            assertTrue("$width dp: first line could still fit $nextWord",
                row.paint.measureText("$first $nextWord") > row.layout.width)
        }
    }

    @Test fun `property swipes select adjacent tabs including Hash and stop at both ends`() {
        val requests = mutableListOf<Int>()
        val adapter = FileAdapter(1, {}, {}, {}, { _, tab -> requests += tab }, {})
        val original = node().copy(details = FileInfo.Details(listOf(
            FileInfo.Section("General", listOf("Name" to "clip.mkv")),
            FileInfo.Section("Media", listOf("Video" to "H.265")))))
        adapter.submitList(listOf(original))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        layout(holder)
        fun swipe(left: Boolean) {
            val row = holder.itemView
            val start = if (left) row.width * 0.8f else row.width * 0.2f
            val end = if (left) row.width * 0.2f else row.width * 0.8f
            val y = row.height / 2f
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to end,
                MotionEvent.ACTION_UP to end)) {
                MotionEvent.obtain(0, 10, action, x, y, 0).also { row.dispatchTouchEvent(it); it.recycle() }
            }
        }
        swipe(false)
        assertTrue(requests.isEmpty())
        swipe(true)
        assertEquals(listOf(1), requests)
        (holder as FileAdapter.InfoVH).bind(original.copy(tab = 1))
        layout(holder)
        swipe(true)
        assertEquals(listOf(1, 2), requests)
        holder.bind(original.copy(tab = 2))
        layout(holder)
        swipe(true)
        assertEquals(listOf(1, 2), requests)
        swipe(false)
        assertEquals(listOf(1, 2, 1), requests)
        adapter.onViewRecycled(holder)
        assertNull((holder.itemView as InfoCardLayout).onSwipe)
    }

    @Test fun `long component names wrap without truncating`() {
        val name = "ui.settings.verylongnamespace.".repeat(5) + "SettingsActivity"
        val adapter = adapter()
        adapter.submitList(listOf(node(activityTitle = name)))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        tapFarEdge(holder, header(holder, AppDetails.Kind.ACTIVITIES))
        val row = texts(holder.itemView).single { it.text.startsWith(name) }
        layout(holder)
        assertTrue(row.lineCount > 1)
        assertNull(row.ellipsize)
        assertEquals(row.text.length, row.layout.getLineEnd(row.lineCount - 1))
    }

    @Test fun `compact App rows render while empty groups including restricted shortcuts are hidden`() {
        val adapter = adapter()
        val original = node()
        val details = original.details!!
        val section = details.sections.single()
        val groups = section.appGroups.map {
            when (it.kind) {
                AppDetails.Kind.SHORTCUTS -> it.copy(entries = emptyList(), notice = "Restricted")
                AppDetails.Kind.RECEIVERS -> it.copy(entries = emptyList())
                else -> it
            }
        }
        adapter.submitList(listOf(original.copy(details = details.copy(sections = listOf(section.copy(appGroups = groups))))))
        val holder = holder(adapter)
        adapter.bindViewHolder(holder, 0)
        assertFalse(texts(holder.itemView).any { it.text.startsWith("Shortcuts") || it.text.startsWith("Receivers") })
        holder.itemView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        tapFarEdge(holder, header(holder, AppDetails.Kind.SIGNATURE))
        tapFarEdge(holder, header(holder, AppDetails.Kind.PROVIDERS))
        tapFarEdge(holder, header(holder, AppDetails.Kind.ACTIVITIES))
        tapFarEdge(holder, header(holder, AppDetails.Kind.SERVICES))
        layout(holder)

    }
}
