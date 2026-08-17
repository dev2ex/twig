# Twig — R8/ProGuard 规则
# core-fs / fs-local 是纯 Kotlin 模型层,被反射处不多;如后续用到序列化再补 keep。

# 保留行号便于崩溃定位(体积影响极小)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin 协程的常见 keep(R8 已基本内置,这里兜底)
-dontwarn kotlinx.coroutines.**

# junrar/sshj 通过 slf4j-api 记日志,运行期无 binder;忽略可选实现类
-dontwarn org.slf4j.**

# SSHJ / BouncyCastle:JCE Provider 以类名字符串反射实例化算法类,必须整体保留
-keep class org.bouncycastle.jcajce.provider.** { <init>(...); }
-keep class org.bouncycastle.jce.provider.** { <init>(...); }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**

# OkHttp 的可选平台类
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# 保留 JNI native 方法名与所在类名(libsmb2 按名称绑定,混淆会失配)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.twig.fs.smb.NativeSmbClient { *; }

# media3 通过反射加载 ffmpeg 扩展渲染器(DefaultRenderersFactory)
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Termux 终端:反射访问 TerminalSession 内部字段(mEmulator/mShellPid/队列),不可混淆
-keep class com.termux.** { *; }
-dontwarn com.termux.**

# Shizuku:binder 那一头按名字查 AIDL 接口(IShizukuService.Stub.asInterface 与
# ShizukuProvider 的跨进程握手),混淆掉类名/方法名就对不上,表现为"服务在跑却连不上"。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# 特权助手:Shizuku 在**另一个进程**里按类名反射实例化 TwigPrivService(classpath 是
# 我们自己的 APK),AIDL 的 Stub/Proxy 也按名字绑定;混淆掉就是 ClassNotFound。
# Pty 的 native 方法按 类名+方法名 绑定到 libtwigpty.so,同样不能改名。
-keep class com.twig.app.priv.** { *; }
