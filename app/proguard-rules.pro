# Twig — R8/ProGuard rules
# core-fs / fs-local are pure Kotlin model layers with few reflective touchpoints;
# add more keep rules here only if/when we serialise things.

# Preserve line numbers for crash localisation (negligible size impact)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin coroutines common keeps (R8 handles most of these already; this is the safety net)
-dontwarn kotlinx.coroutines.**

# junrar/sshj log via slf4j-api at runtime with no binder; ignore the optional impl classes
-dontwarn org.slf4j.**

# SSHJ / BouncyCastle: JCE Providers reflect algorithm classes by string name, must be kept wholesale
-keep class org.bouncycastle.jcajce.provider.** { <init>(...); }
-keep class org.bouncycastle.jce.provider.** { <init>(...); }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**

# OkHttp's optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Preserve JNI native method names and their declaring classes (libsmb2 binds by name; obfuscation would break it)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.twig.fs.smb.NativeSmbClient { *; }

# media3 loads the ffmpeg extension renderer reflectively (DefaultRenderersFactory)
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Termux terminal: reflectively accesses TerminalSession's internal fields (mEmulator / mShellPid / queue), cannot be obfuscated
-keep class com.termux.** { *; }
-dontwarn com.termux.**

# Shizuku: the binder side looks up AIDL interfaces by name
# (IShizukuService.Stub.asInterface and ShizukuProvider's cross-process handshake);
# obfuscate the class/method names and they won't match — symptoms look like
# "the service is running but nothing connects".
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Privileged helper: Shizuku reflectively instantiates TwigPrivService **in another process**
# (classpath is our own APK), and AIDL's Stub/Proxy also bind by name;
# obfuscate and you get ClassNotFound.
# The pty native methods bind to libtwigpty.so by class name + method name; same rule.
-keep class com.twig.app.priv.** { *; }
