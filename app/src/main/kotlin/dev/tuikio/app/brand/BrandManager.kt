package dev.tuikio.app.brand

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import dev.tuikio.app.MainActivity
import dev.tuikio.app.TuikioApp
import dev.tuikio.app.ui.BrandPack
import dev.tuikio.app.ui.Brands
import dev.tuikio.app.ui.CustomBrand
import dev.tuikio.app.ui.Snack
import dev.tuikio.app.ui.Theme
import java.io.File

/**
 * Applies brands. In-app identity changes immediately. The launcher label and icon can only
 * switch between identities declared in the manifest (activity-aliases); arbitrary custom
 * names and pictures become a pinned home-screen shortcut the user approves. The package
 * name, signature, saves and accounts never change.
 */
object BrandManager {
    const val REQ_PICK_LOGO = 4201
    private const val MAX_SOURCE_PIXELS = 40_000_000L
    private const val LOGO_SIZE = 512

    /** Enables [pack]'s launcher alias and disables the others. Returns false if the system refused. */
    fun applyLauncher(context: Context, pack: BrandPack): Boolean {
        val pm = context.packageManager
        val target = ComponentName(context, pack.alias)
        val others = Brands.ALL.filter { it != pack }.map { ComponentName(context, it.alias) }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                // One atomic update: never a moment with zero or two launcher icons.
                val changes = listOf(PackageManager.ComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)) +
                    others.map { PackageManager.ComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
                pm.setComponentEnabledSettings(changes)
            } else {
                // Enable the new identity first so there is always at least one launcher entry.
                pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                others.forEach { pm.setComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            }
            true
        } catch (e: RuntimeException) {
            // Safe revert: make sure the default identity is reachable.
            runCatching { pm.setComponentEnabledSetting(ComponentName(context, Brands.TUIKIO.alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
            false
        }
    }

    fun activeLauncherAlias(context: Context): BrandPack? = Brands.ALL.firstOrNull {
        val state = context.packageManager.getComponentEnabledSetting(ComponentName(context, it.alias))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it == Brands.TUIKIO)
    }

    fun apply(context: Context, pack: BrandPack, switchLauncher: Boolean): Boolean {
        Theme.setPack(pack)
        return if (switchLauncher) applyLauncher(context, pack) else true
    }

    fun resetToTuikio(context: Context) {
        Theme.setCustom(null)
        Theme.setPhosphor(false)
        apply(context, Brands.TUIKIO, switchLauncher = true)
        File(context.filesDir, "brand").deleteRecursively()
    }

    fun pickLogo(activity: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
        activity.startActivityForResult(i, REQ_PICK_LOGO)
    }

    /** Decodes the picked image defensively off the UI thread and stores a clean 512px PNG. */
    fun onLogoPicked(activity: MainActivity, uri: Uri?) {
        uri ?: return
        val app = TuikioApp.instance
        app.background({ importLogo(activity, uri) }) { r ->
            r.onSuccess { file ->
                val c = Theme.custom ?: CustomBrand(Theme.displayName, "", Theme.pack.id, null, null)
                Theme.setCustom(c.copy(logoPath = file.absolutePath))
                Snack.show(activity.nav.overlay, "Logo added.")
            }.onFailure { Snack.show(activity.nav.overlay, it.message ?: "Couldn't use that picture.") }
        }
    }

    internal fun importLogo(context: Context, uri: Uri): File {
        val type = context.contentResolver.getType(uri)
        if (type != null && type !in setOf("image/png", "image/jpeg", "image/webp")) throw IllegalArgumentException("Use a PNG, JPEG or WebP picture.")
        // 1. Read only the header to reject absurd dimensions (decompression bombs).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("That file isn't a picture we can read.")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) throw IllegalArgumentException("That picture is too large (over 40 megapixels).")
        // 2. Decode downsampled to roughly the target size.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= LOGO_SIZE && bounds.outHeight / (sample * 2) >= LOGO_SIZE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("That picture couldn't be decoded.")
        // 3. Center-crop to a square and re-encode: this also drops EXIF/location metadata.
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(LOGO_SIZE, LOGO_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(
            decoded, android.graphics.Rect((decoded.width - side) / 2, (decoded.height - side) / 2, (decoded.width + side) / 2, (decoded.height + side) / 2),
            android.graphics.Rect(0, 0, LOGO_SIZE, LOGO_SIZE), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        decoded.recycle()
        val dir = File(context.filesDir, "brand").apply { mkdirs() }
        val out = File(dir, "logo.png")
        val tmp = File(dir, "logo.png.tmp")
        tmp.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
        square.recycle()
        if (!tmp.renameTo(out)) throw IllegalStateException("Couldn't save the logo.")
        return out
    }

    /**
     * Asks the launcher to pin a home-screen shortcut with the custom name and picture. This is
     * a shortcut, not a rename: the app's own icon in the app drawer stays a bundled identity.
     */
    fun requestPinnedShortcut(context: Context, custom: CustomBrand): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        val bmp = custom.logoFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val icon = if (bmp != null) Icon.createWithAdaptiveBitmap(padForAdaptive(bmp)) else Icon.createWithResource(context, Theme.pack.iconRes)
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val info = ShortcutInfo.Builder(context, "custom-brand")
            .setShortLabel(custom.name.take(10).ifBlank { "Tuikio" })
            .setLongLabel(custom.name.take(25).ifBlank { "Tuikio" })
            .setIcon(icon)
            .setIntent(launch)
            .build()
        return sm.requestPinShortcut(info, null)
    }

    /** Adaptive icons mask the outer third; inset the logo into the 72/108 safe area. */
    private fun padForAdaptive(src: Bitmap): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Theme.palette.ground)
        val inset = (size * 18 / 108f).toInt()
        c.drawBitmap(src, null, android.graphics.Rect(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
