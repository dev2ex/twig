package com.twig.app.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * ★ 回归:「特权访问」对话框里的选项**必须真的在屏幕上**。
 *
 * 第一版同时调了 `setMessage()`(那段 Shizuku 说明)和 `setSingleChoiceItems()`。
 * AlertDialog 的内容面板只放得下一样东西,两个都设时 message 赢,**列表整个不渲染** ——
 * 用户看到的是一段说明加一个「取消」,一个选项都点不到。代码读起来毫无破绽,
 * 而后果是整个功能没有入口:选不了 → 从不请求 Shizuku 授权 → 在 Shizuku 的应用
 * 列表里也永远不出现,三个症状看着像三个 bug。
 *
 * 所以这条测的不是"builder 上设没设选项",而是**对话框拿出来后 ListView 里有几行**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedDialogTest {

    private fun openSettings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    /**
     * 按标题文案找到设置页里的那一行(手写布局,没有 id 可用)。找到标题 TextView 后
     * **往上走到第一个真正装了点击监听的祖先** —— 不写死"往上两层",布局嵌套一改
     * 那种写法会静默点到不相干的 View 上,测试反而变成假绿。
     */
    private fun rowWithTitle(activity: SettingsActivity, title: String): android.view.View? {
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        var hit: android.view.View? = null
        fun walk(v: android.view.View) {
            if (hit == null && v is TextView && v.text?.toString() == title) hit = v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        var cur: android.view.View? = hit
        while (cur != null && !cur.hasOnClickListeners()) cur = cur.parent as? android.view.View
        return cur
    }

    /** AppCompat 的对话框不归 ShadowAlertDialog 管,只能从最近弹出的 Dialog 拿。 */
    private fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    /**
     * ★ 判据必须是"**挂进视图树没有**",不能是 `listView != null`。
     *
     * AlertController 在有 message 时**照样把 ListView 构造出来**,只是不往内容面板里
     * 加 —— 于是 `dialog.listView` 非空、adapter 里三行俱全,而屏幕上一个选项都没有。
     * 拿非空当判据,这条测试在出 bug 的那版上会照样绿。
     */
    private fun isShown(dialog: AlertDialog, target: android.view.View?): Boolean {
        if (target == null) return false
        var found = false
        fun walk(v: android.view.View) {
            if (v === target) found = true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        dialog.window?.decorView?.let { walk(it) }
        return found
    }

    @Test
    fun `特权访问对话框把三个选项真的渲染出来`() {
        val activity = openSettings()
        val title = activity.getString(R.string.settings_privileged)
        val row = rowWithTitle(activity, title)
        assertNotNull("设置页里没找到「$title」这一行", row)

        row!!.performClick()

        val dialog = latestDialog()
        assertNotNull("点了没弹出对话框", dialog)
        val listView = dialog!!.listView
        assertTrue(
            "选项列表没有出现在对话框里——多半是又给它设了 message,内容面板被占掉了",
            isShown(dialog, listView),
        )
        assertEquals("应当是 关闭 / Root / Shizuku 三选一", 3, listView.adapter.count)
        assertEquals(activity.getString(R.string.priv_mode_off), listView.adapter.getItem(0).toString())
        assertEquals(activity.getString(R.string.priv_mode_shizuku), listView.adapter.getItem(2).toString())
    }

    /**
     * 说明文字不能因为挪走就丢了 —— 它解释的是"Shizuku 不是 root",
     * 没有它用户会以为选了 Shizuku 就能看任意应用的私有数据。
     */
    @Test
    fun `Shizuku 说明仍然出现在对话框里`() {
        val activity = openSettings()
        rowWithTitle(activity, activity.getString(R.string.settings_privileged))!!.performClick()

        val dialog = latestDialog()!!
        val note = activity.getString(R.string.priv_note_shizuku)
        val texts = ArrayList<String>()
        fun walk(v: android.view.View) {
            if (v is TextView) texts += v.text?.toString().orEmpty()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        dialog.window?.decorView?.let { walk(it) }
        assertTrue("说明文字没了", texts.any { it == note })
    }

    /**
     * `setMessage` + 列表同时用会出什么事,直接钉在这里 —— 免得以后有人"顺手把说明
     * 挪回 setMessage",而那看上去是个无害的简化。
     */
    @Test
    fun `同时设置 message 与选项时列表会消失`() {
        val activity = openSettings()
        AlertDialog.Builder(activity)
            .setTitle("t")
            .setMessage("一段说明")
            .setSingleChoiceItems(arrayOf("a", "b", "c"), 0, null)
            .show()

        val dialog = latestDialog()!!
        // 注意它**不是 null** —— 列表被造出来了,只是没挂上去。这正是这条坑难查的原因。
        assertNotNull("列表对象本身还在", dialog.listView)
        assertTrue(
            "AlertDialog 竟然同时显示了 message 和列表——那这条坑不存在了,主测试的判据该收紧",
            !isShown(dialog, dialog.listView),
        )
    }
}
