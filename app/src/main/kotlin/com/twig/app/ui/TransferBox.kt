package com.twig.app.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.databinding.DialogConflictBinding
import com.twig.app.databinding.DialogCopyProgressBinding
import com.twig.app.databinding.DialogProgressTitleBinding
import com.twig.core.CopyEngine
import com.twig.core.XFile

/**
 * 复制/移动/压缩共用的进度框:当前文件 + 两条进度条 + 剩余目录/文件数 + 速度/ETA。
 *
 * **只是 [Transfers] 会话的观察者**,不持有任务本身:关掉(「转到后台」/ 界面销毁)只是
 * 摘掉观察,搬运由前台服务接着跑;点通知栏回来再挂上,照会话里的快照一次性填满,
 * 不必等下一次回调。
 *
 * 从 `PaneFragment` 里提出来是因为目录对比页也要复制/同步——同一个会话机制、同一套
 * 进度与冲突交互,没有理由写第二份。宿主差异都收在这几个回调里:
 * @param alive 宿主界面还在不在(Fragment 的 view 可能先于会话销毁)
 * @param onBackground 「转到后台」按下,宿主去要通知权限并提示
 * @param onDetach 框摘掉了,宿主清掉自己那份引用
 * @param onFinished 传输结束(宿主刷新列表、提示结果)
 */
class TransferBox(
    private val ctx: Context,
    private val inflater: LayoutInflater,
    private val session: Transfers.Session,
    private val alive: () -> Boolean,
    private val onBackground: () -> Unit,
    private val onDetach: () -> Unit,
    private val onFinished: (Transfers.Session) -> Unit,
) : Transfers.Ui {

    private val pb = DialogCopyProgressBinding.inflate(inflater)
    // 自定义标题:「转到后台」要的位置就是标题这一行的右端
    private val tb = DialogProgressTitleBinding.inflate(inflater)
    private val dialog = AlertDialog.Builder(ctx)
        .setCustomTitle(tb.root)
        .setView(pb.root)
        .setCancelable(false)
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> session.cancel() }
        .create()

    init {
        tb.tvTitle.setText(session.titleRes)
        pb.tvDest.text = session.destLabel
        pb.ivDest.setImageResource(session.destIcon)
        tb.btnBackground.setOnClickListener {
            detach()
            onBackground()
        }
        dialog.show()
        Transfers.attach(this) // 挂上即回灌一次进度(含等待中的冲突框)
    }

    /** 摘掉观察并关框(任务不受影响)。 */
    fun detach() {
        if (Transfers.ui === this) Transfers.attach(null)
        onDetach()
        runCatching { dialog.dismiss() }
    }

    override fun onProgress(s: Transfers.Session) {
        if (!alive()) return
        pb.tvFile.text =
            if (s.pendingConflict != null) ctx.getString(R.string.transfer_waiting) else s.fileName
        pb.barFile.progress = s.fileProgress
        pb.barTotal.progress = s.totalProgress
        val plan = s.plan
        pb.tvDirs.text = if (plan == null) "…" else s.dirsLeft.toString()
        pb.tvFiles.text = if (plan == null) "…" else s.filesLeft.toString()
        pb.tvTotalSize.text =
            if (plan == null) "…" else ctx.getString(R.string.copy_total_size, Format.size(plan.bytes))
        pb.tvSpeed.text =
            if (s.speed > 1) ctx.getString(R.string.copy_speed, Format.size(s.speed.toLong())) else ""
        pb.tvEta.text = if (s.etaSeconds >= 0) formatEta(s.etaSeconds) else ""
    }

    override fun onConflict(s: Transfers.Session, c: Transfers.Conflict) {
        if (!alive()) return
        showConflictDialog(ctx, inflater, c.src, c.existing) { d, all ->
            if (d != null && all) s.applyAll = d
            c.answer(d)
        }
    }

    override fun onFinished(s: Transfers.Session) {
        onDetach()
        runCatching { dialog.dismiss() }
        if (!alive()) return
        onFinished.invoke(s)
    }
}

/** 同名冲突弹框:覆盖/跳过/重命名 + 全部同样处理;取消则中止任务。 */
fun showConflictDialog(
    ctx: Context,
    inflater: LayoutInflater,
    src: XFile,
    existing: XFile,
    onResult: (CopyEngine.Decision?, Boolean) -> Unit,
) {
    val cb = DialogConflictBinding.inflate(inflater)
    cb.tvName.text = src.name
    cb.tvDetail.text = ctx.getString(
        R.string.conflict_detail, Format.size(src.size), Format.size(existing.size),
    )
    AlertDialog.Builder(ctx)
        .setTitle(R.string.conflict_title)
        .setView(cb.root)
        .setCancelable(false)
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> onResult(null, false) }
        .setPositiveButton(R.string.dialog_ok) { _, _ ->
            val d = when (cb.rgDecision.checkedRadioButtonId) {
                R.id.rb_skip -> CopyEngine.Decision.SKIP
                R.id.rb_rename -> CopyEngine.Decision.RENAME
                else -> CopyEngine.Decision.OVERWRITE
            }
            onResult(d, cb.cbAll.isChecked)
        }
        .show()
}

/** 剩余时间:-mm:ss / -h:mm:ss。 */
private fun formatEta(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "-%d:%02d:%02d".format(h, m, s) else "-%d:%02d".format(m, s)
}
