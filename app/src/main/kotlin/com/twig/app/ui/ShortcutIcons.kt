package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.IconCompat

/**
 * Icon for a launcher shortcut, with the drawable's `android:tint` baked in.
 *
 * ★ Never hand the launcher a bare `IconCompat.createWithResource()` for one of
 * our vectors (measured on a real device, 2026-08-27): the launcher loads the
 * resource itself and **the vector's `android:tint` is dropped**. Every icon in
 * this app draws its paths with `fillColor="@android:color/white"` and gets its
 * colour purely from that tint (see the media-server icon lesson in CLAUDE.md),
 * so what lands on the desktop is a pure white glyph — invisible on a launcher's
 * light icon plate. Rendering the drawable here keeps the colour ours.
 *
 * The same trap applies to `<shortcut android:icon>` in `res/xml/shortcuts.xml`,
 * which the launcher likewise loads by resource and which no code path can bake:
 * those must point at a drawable whose paths carry **literal** colours.
 */
object ShortcutIcons {

    /** Launcher icon size; a sane fallback if the system will not say. */
    private const val FALLBACK_PX = 192

    fun of(ctx: Context, @DrawableRes res: Int): IconCompat {
        val d = AppCompatResources.getDrawable(ctx, res)
            ?: return IconCompat.createWithResource(ctx, res)
        val size = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.launcherLargeIconSize?.takeIf { it > 0 } ?: FALLBACK_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return IconCompat.createWithBitmap(bmp)
    }

    /**
     * A real bitmap (a file's own thumbnail) rather than one of our tinted vectors — no tint
     * to bake in, so this just center-crops to square and hands it straight to the launcher,
     * which scales/masks it like any adaptive icon.
     */
    fun of(ctx: Context, bitmap: Bitmap): IconCompat {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        val cropped = Bitmap.createBitmap(bitmap, x, y, side, side)
        return IconCompat.createWithBitmap(cropped)
    }
}
