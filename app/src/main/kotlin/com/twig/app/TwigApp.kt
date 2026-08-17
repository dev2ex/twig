package com.twig.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.twig.core.FsRegistry
import com.twig.fs.archive.RarFileSystem
import com.twig.fs.archive.SevenZFileSystem
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.local.LocalFileSystem

/** 应用入口:尽早应用持久化的主题,避免启动闪烁;顺带登记不依赖用户配置的基础来源。 */
class TwigApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this))
        registerBaseFs(this)
        MediaScan.install(this) // 本地写入 → 通知系统媒体库,拷进去的图才会出现在相册
        Privileged.restore(this) // 上次开过 root/Shizuku 就后台重新接上(失败静默留在关闭态)
    }

    companion object {
        /**
         * 基础文件系统(本地/压缩包/SAF/应用/外部 content:// 来源)。
         * ★ 必须在 Application 里注册,不能只在 MainActivity ——「用 Twig 打开」
         * ([ui.ViewIntentActivity])、分享目标这些入口会在没有主界面的情况下冷启动进程,
         * 那时 FsRegistry 还是空的,查看器一取 FileSystem 就抛"未注册的文件系统"。
         */
        fun registerBaseFs(ctx: Context) {
            val app = ctx.applicationContext
            FsRegistry.register(LocalFileSystem())
            FsRegistry.register(ZipFileSystem())
            FsRegistry.register(SevenZFileSystem())
            FsRegistry.register(RarFileSystem())
            FsRegistry.register(SafFileSystem(app))
            FsRegistry.register(AppsFileSystem(app))
            FsRegistry.register(ShareSourceFileSystem(app))
        }
    }
}
