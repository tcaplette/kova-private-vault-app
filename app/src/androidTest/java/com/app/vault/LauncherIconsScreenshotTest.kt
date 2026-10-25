package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * `launcher-icon-artwork`, task 4.5 — each identity's real adaptive icon, drawn by the platform
 * from its own layers under the three launcher mask shapes, in colour and as a themed icon.
 *
 * Launchers mask the middle 72 dp of the 108 dp canvas; that is what is drawn here. The themed
 * form is the monochrome layer tinted the way the Pixel launcher tints it, on its light tonal
 * ground. One sheet is written with `dd` to [DIR]: rows are identities, columns are circle,
 * squircle and rounded square in colour, then the same three themed.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconsScreenshotTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val DIR = "/data/local/tmp/launcher-icon-artwork"
        const val SIZE = 288
        const val GAP = 24
        val THEMED_GROUND = Color.rgb(0xD6, 0xE3, 0xFF)
        val THEMED_INK = Color.rgb(0x1E, 0x34, 0x6E)
    }

    private fun circle() = Path().apply { addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW) }

    private fun roundedSquare() = Path().apply {
        addRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), SIZE * 0.2f, SIZE * 0.2f, Path.Direction.CW)
    }

    /** A superellipse, n = 4 — the squircle launchers use. */
    private fun squircle() = Path().apply {
        val r = SIZE / 2f
        for (i in 0..360) {
            val t = Math.toRadians(i.toDouble())
            val c = kotlin.math.cos(t)
            val s = kotlin.math.sin(t)
            val x = r + r * sign(c) * abs(c).pow(0.5)
            val y = r + r * sign(s) * abs(s).pow(0.5)
            if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }

    /** A 108 dp layer, drawn so its middle 72 dp fills [SIZE]. */
    private fun Canvas.layer(d: Drawable?) {
        d ?: return
        d.setBounds(-SIZE / 4, -SIZE / 4, SIZE * 5 / 4, SIZE * 5 / 4)
        d.draw(this)
    }

    private fun tile(icon: AdaptiveIconDrawable, mask: Path, themed: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(mask)
        if (themed) {
            c.drawColor(THEMED_GROUND)
            val mono = icon.monochrome!!.mutate()
            mono.colorFilter = PorterDuffColorFilter(THEMED_INK, PorterDuff.Mode.SRC_IN)
            c.layer(mono)
        } else {
            c.layer(icon.background)
            c.layer(icon.foreground)
        }
        return bmp
    }

    @Test
    fun theFourIdentitiesUnderEveryMask() {
        val identities = listOf(
            LauncherIdentity.Vault, LauncherIdentity.Calculator,
            LauncherIdentity.MusicPlayer, LauncherIdentity.VoiceRecorder,
        )
        val masks = listOf(circle(), squircle(), roundedSquare())
        val sheet = Bitmap.createBitmap(
            6 * SIZE + 7 * GAP, identities.size * SIZE + (identities.size + 1) * GAP, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheet).apply { drawColor(Color.WHITE) }
        val grounds = mutableSetOf<Int>()
        identities.forEachIndexed { row, identity ->
            val icon = ctx.getDrawable(identity.iconRes) as AdaptiveIconDrawable
            assertNotNull("${identity.name} has a monochrome layer", icon.monochrome)
            // The ground's colour, read from its own layer: no two identities may share one.
            val ground = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            Canvas(ground).layer(icon.background)
            grounds += ground.getPixel(2, 2)
            (0 until 6).forEach { col ->
                val t = tile(icon, masks[col % 3], themed = col >= 3)
                canvas.drawBitmap(
                    t, (GAP + col * (SIZE + GAP)).toFloat(), (GAP + row * (SIZE + GAP)).toFloat(), Paint(),
                )
            }
        }
        assertTrue("the four identities' grounds are four colours: $grounds", grounds.size == identities.size)
        save("sheet", sheet)
    }

    private fun save(name: String, bmp: Bitmap) {
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee` — CLAUDE.md rule 21.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
