package com.twig.app.ui

import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局剪贴板(两个面板共享):长按菜单「复制到剪贴板」放进来,主界面底部的通栏点「粘贴」
 * 时以**活动面板**的当前目录为目标搬运,所以本侧/对侧都能作目的地——不像操作栏的
 * 复制/移动那样固定「本侧 → 对侧」。
 *
 * 只存内存(进程内),不持久化:里面装的 [XFile] 可能来自压缩包/网络会话,跨进程重建
 * 成本高、还容易指向已经失效的连接。[move] 是剪贴板栏上的复选框状态,跟着剪贴板走而不
 * 属于某个面板,换内容不重置(常用移动的人不必每次重勾)。
 */
object FileClipboard {

    data class State(
        val items: List<XFile> = emptyList(),
        val move: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val items: List<XFile> get() = _state.value.items
    val move: Boolean get() = _state.value.move

    /** 覆盖式放入(不追加):剪贴板语义与系统一致,新的一次「复制到剪贴板」换掉旧内容。 */
    fun put(files: List<XFile>) {
        if (files.isEmpty()) return
        _state.value = _state.value.copy(items = files.toList())
    }

    fun setMove(move: Boolean) {
        if (_state.value.move != move) _state.value = _state.value.copy(move = move)
    }

    fun clear() {
        if (_state.value.items.isNotEmpty()) _state.value = _state.value.copy(items = emptyList())
    }

    /**
     * 目标目录不能落时的原因(string 资源 id),null = 可以粘。栏上据此置灰「粘贴」并把原因
     * 写在目标那行,[PaneFragment.pasteFromClipboard] 再兜一次(按钮状态可能比绿框慢一拍)。
     *
     * 两种拦法:
     * - **同一目录**:源就在目标里,复制出来只能是同名冲突/副本,移动更是原地不动;
     * - **粘进自己**:把目录粘到它自身或它的子目录里,是会无限递归的搬法。
     *
     * 都要求 scheme 相同才算——不同来源(本地 vs SMB)路径字符串撞车不代表是同一个地方。
     */
    fun pasteBlockReason(dest: XFile?): Int? {
        val items = _state.value.items
        if (items.isEmpty() || dest == null) return null
        if (items.any { it.scheme == dest.scheme && it.parentPath == dest.path }) {
            return R.string.clip_same_dir
        }
        val destPath = dest.path.trimEnd('/')
        if (items.any {
                it.isDir && it.scheme == dest.scheme &&
                    (destPath == it.path.trimEnd('/') || destPath.startsWith(it.path.trimEnd('/') + "/"))
            }
        ) {
            return R.string.clip_into_self
        }
        return null
    }
}
