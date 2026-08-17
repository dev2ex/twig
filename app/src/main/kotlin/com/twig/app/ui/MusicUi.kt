package com.twig.app.ui

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 音乐界面的集中退出。各音乐 Activity 注册一个 finish 回调,"退出播放器"时统一:
 * 释放播放器 + 停前台服务(移除通知)+ finish 掉所有已开的音乐界面(回到文件管理)。
 */
@UnstableApi
object MusicUi {

    private val finishers = CopyOnWriteArraySet<() -> Unit>()

    fun register(finish: () -> Unit) { finishers.add(finish) }
    fun unregister(finish: () -> Unit) { finishers.remove(finish) }

    fun exit(ctx: Context) {
        MusicEngine.shutdown()
        // 停服务:前台服务被销毁时其通知一并移除
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, MusicService::class.java))
        finishers.toList().forEach { it() }
        finishers.clear()
    }
}
