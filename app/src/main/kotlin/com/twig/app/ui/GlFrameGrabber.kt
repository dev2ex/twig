package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 系统 MediaMetadataRetriever 不支持的容器(如 AVI)靠它抓一帧:自己起一个不可见的
 * ExoPlayer 解码到 SurfaceTexture(避免走 MMR 的系统级 demux 限制),再用一套最小
 * EGL/GL 管线把外部 OES 纹理转绘到普通 2D 纹理 FBO 上 `glReadPixels` 出来——
 * 不能直接把解码输出接 [android.media.ImageReader]:硬解视频吐出来的是设备相关的
 * 不透明/YUV 缓冲区,不透过 GL 采样直接读会在不少厂商设备上花屏/格式不兼容,这也是
 * media3 官方另起一整套 `media3-effect` GL 管线做 FrameExtractor 的原因;为了不引入
 * 那一整包依赖(增大体积),这里手写一条最小路径。
 *
 * 全程单独一条 [HandlerThread] 跑:EGL 上下文/纹理只能在创建它的线程上用,
 * ExoPlayer 用同一个 Looper 构建,回调天然落在这条线程上,不用再互相 post。
 */
@UnstableApi
object GlFrameGrabber {

    private const val TAG = "twig"

    /**
     * @param dataSourceFactory 为 null 时用本地文件默认 [androidx.media3.datasource.FileDataSource]
     * @param targetDivisor 取时长 1/[targetDivisor] 处的代表帧;AVI 这类容器的时长要
     *   解完容器头(甚至 idx1)才知道,不能像 MP4/MKV 那样提前算好时间点再传进来——
     *   所以这里先从 0 开始 prepare,时长一到手([onEvents] 里 [ExoPlayer.getDuration]
     *   不再是 [C.TIME_UNSET])就补一次 seek,再等第一帧渲染出来抓帧。
     */
    fun grab(
        context: Context,
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory?,
        targetDivisor: Long,
        timeoutMs: Long,
        extractorsFactory: androidx.media3.extractor.ExtractorsFactory? = null,
    ): Bitmap? {
        val thread = HandlerThread("twig-frame-grab").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        var player: ExoPlayer? = null
        var gl: GlContext? = null

        handler.post {
            try {
                gl = GlContext()
                val renderers = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                val srcFactory = dataSourceFactory ?: DefaultDataSource.Factory(context)
                val p = ExoPlayer.Builder(context, renderers).build()
                player = p
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    // m2ts 的 SCTE-35 元数据轨解析有些文件会直接抛异常崩播放器,反正
                    // 抓帧用不上,一并关掉(见 MediaPlayerActivity 里同样的处理)。
                    .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                    .build()
                var captured = false
                var sought = false
                p.addListener(object : Player.Listener {
                    // 用 tracks(而非 onVideoSizeChanged)拿视频宽高建 Surface:
                    // MediaCodecVideoRenderer 没有输出 Surface 时不会真正启动解码
                    // 管线,onVideoSizeChanged 也就永远不触发——先有 Surface 才有
                    // 尺寸回调、先有尺寸才能建 Surface,原先那版正卡死在这个死循环里。
                    override fun onTracksChanged(tracks: Tracks) {
                        val g = gl ?: return
                        if (g.hasSurface()) return
                        val videoGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
                        val format = videoGroup.getTrackFormat(0)
                        if (format.width <= 0 || format.height <= 0) return
                        p.setVideoSurface(g.prepareSurface(format.width, format.height))
                    }
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (sought || captured) return
                        val dur = p.duration
                        if (dur != C.TIME_UNSET && dur > 0) {
                            sought = true
                            Log.d(TAG, "thumbs: gl grab seeking to ${dur / targetDivisor}ms of ${dur}ms")
                            p.seekTo(dur / targetDivisor)
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        if (captured) return
                        captured = true
                        result = runCatching { gl?.captureFrame() }
                            .onFailure { Log.w(TAG, "thumbs: gl grab capture failed: $it") }
                            .getOrNull()
                        latch.countDown()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "thumbs: gl grab player error: ${error.errorCodeName}", error)
                        latch.countDown()
                    }
                })
                val sourceFactory = if (extractorsFactory != null) {
                    ProgressiveMediaSource.Factory(srcFactory, extractorsFactory)
                } else {
                    ProgressiveMediaSource.Factory(srcFactory)
                }
                p.setMediaSource(sourceFactory.createMediaSource(mediaItem))
                p.playWhenReady = true
                p.prepare()
            } catch (e: Throwable) {
                Log.w(TAG, "thumbs: gl grab setup failed: $e", e)
                latch.countDown()
            }
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val cleanupDone = CountDownLatch(1)
        handler.post {
            runCatching { player?.release() }
            runCatching { gl?.release() }
            cleanupDone.countDown()
        }
        cleanupDone.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        return result
    }

    /** 一条线程独享的 EGL 上下文 + OES 外部纹理→普通 2D 纹理 FBO 转绘管线。 */
    private class GlContext {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var oesTextureId = 0
        private var fbo = 0
        private var fboTexture = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var width = 0
        private var height = 0

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                "eglChooseConfig failed"
            }
            val config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            pbuffer = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

            program = buildProgram()
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
        }

        fun hasSurface() = surface != null

        /** 首次拿到视频真实宽高后调用,建 SurfaceTexture/FBO,交给播放器当输出面。 */
        fun prepareSurface(w: Int, h: Int): Surface {
            width = w; height = h
            val st = SurfaceTexture(oesTextureId)
            st.setDefaultBufferSize(w, h)
            surfaceTexture = st
            val surf = Surface(st)
            surface = surf

            val fboTex = IntArray(1)
            GLES20.glGenTextures(1, fboTex, 0)
            fboTexture = fboTex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            val fboArr = IntArray(1)
            GLES20.glGenFramebuffers(1, fboArr, 0)
            fbo = fboArr[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0,
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "framebuffer incomplete"
            }
            return surf
        }

        /** SurfaceTexture 已有新帧(播放器刚渲染完 onRenderedFirstFrame)时调用。 */
        fun captureFrame(): Bitmap? {
            val st = surfaceTexture ?: return null
            if (width <= 0 || height <= 0) return null
            st.updateTexImage()
            val texMatrix = FloatArray(16)
            st.getTransformMatrix(texMatrix)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            val matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, QUAD_POSITIONS)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEXCOORDS)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(samplerHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(texHandle)

            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

            // GL 行序是从下到上,Bitmap 要从上到下——逐行翻转
            val rowBytes = width * 4
            val flipped = ByteBuffer.allocateDirect(buf.capacity()).order(ByteOrder.nativeOrder())
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                buf.position((height - 1 - y) * rowBytes)
                buf.get(row, 0, rowBytes)
                flipped.put(row)
            }
            flipped.position(0)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(flipped)
            bitmap.setHasAlpha(false)
            return bitmap
        }

        fun release() {
            runCatching { surface?.release() }
            runCatching { surfaceTexture?.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglTerminate(display)
            }
        }

        private fun buildProgram(): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        companion object {
            private val QUAD_POSITIONS = floatBuffer(
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            )
            private val QUAD_TEXCOORDS = floatBuffer(
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            )

            private fun floatBuffer(a: FloatArray) =
                ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(a); position(0)
                }

            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """
        }
    }
}
