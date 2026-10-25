package com.app.vault

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a pairing payload to a PNG the emulator can hang on a wall — P4-5's
 * two-device rig, task 6.1.
 *
 * **It uses the app's own generator.** `PairingQr.matrix` is the function the
 * pairing screen draws from, so the poster and the screen cannot disagree about
 * the bytes; a rig that rendered its own QR would be testing the rig.
 *
 * It lives in the unit-test source set because `javax.imageio` is a JVM API that
 * does not exist on Android, and because nothing here ships. Run it through
 * `./gradlew :app:renderPairingPoster -Ppayload=<hex> -Pout=<file.png>`.
 *
 * **The payload is given as hex** (`share-format-v2`). A v2 payload is `VPAIR2` and a
 * 1,216-byte key as raw binary, which no command line carries intact; the rig prints
 * and passes it as lowercase hex, and this turns it back into the bytes the pairing
 * screen encodes.
 *
 * The scale and the quiet zone are chosen for a camera looking at a wall, not for
 * a screen: a poster is photographed at an angle and at distance, so the modules
 * are large and the light margin is generous.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <payload hex> <output.png>" }
    val hex = args[0]
    require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" }) { "the payload must be lowercase hex" }
    val payload = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    require(Identity.PairingCode.parse(payload) is Identity.PairingCode.Parsed.Key) {
        "that is not a pairing payload this build reads"
    }
    val out = File(args[1])

    val modules = PairingQr.matrix(payload)
    // 133 modules at 24 px is a 3,480-px poster: large, and what a wall in the
    // virtual scene needs for a camera across the room to resolve a module.
    val scale = 24
    val quiet = 6
    val side = (modules.size + quiet * 2) * scale

    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, side, side)
    g.color = java.awt.Color.BLACK
    modules.forEachIndexed { y, row ->
        row.forEachIndexed { x, dark ->
            if (dark) g.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale)
        }
    }
    g.dispose()

    out.parentFile?.mkdirs()
    ImageIO.write(image, "png", out)
    println("wrote ${out.absolutePath} (${side}×$side, ${modules.size} modules)")
}
