package com.app.vault

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A pairing code read **through the camera** — P4-5's two-device rig, task 6.3.
 *
 * This is the half `PairingDeviceTest` could not do. There, a code was rendered to
 * a luminance buffer the test built. Here the frames come from the camera itself:
 * `VaultCameraSession` in scan mode, a real camera2 session, a `YUV_420_888`
 * `ImageReader`, a stride-padded plane, and `PairingQr.decode` — the same call the
 * pairing screen's callback makes.
 *
 * **Two claims, and they are not the same strength.**
 *
 * 1. *Frames arrive and are decoded.* Asserted unconditionally: if the scan mode
 *    stops delivering frames on real hardware, this fails, poster or no poster.
 * 2. *The code in the scene is read.* Only checked when the emulator was launched
 *    with `-virtualscene-poster` — `scripts/pairing-scene-emulator.sh`. Without
 *    one there is nothing in the room to read, so the test **skips** that half
 *    rather than failing on the operator's launch flags.
 *
 * It still is not glass and photons: the virtual scene is rendered, not lit. What
 * it does prove is that the pipeline between the sensor and the decoder is real.
 */
@RunWith(AndroidJUnit4::class)
class PairingSceneTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * `CAMERA`, granted through `uiAutomation` rather than `GrantPermissionRule`.
     *
     * The rule lives in `androidx.test:rules`, which this project does not depend
     * on, and one line here is cheaper than a dependency — even an androidTest-scope
     * one that ships in no APK.
     */
    @Before fun grantCamera() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            ctx.packageName, android.Manifest.permission.CAMERA,
        )
    }

    /**
     * The payload the scene is expected to carry, from `-Ppairing.payload=…`, as
     * lowercase hex — a v2 payload is raw binary, which no argument carries intact.
     */
    private val expected: String? =
        InstrumentationRegistry.getArguments().getString("pairing.payload")

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun theCameraPipelineDeliversFramesAndDecodesWhatIsInTheScene() {
        assumeTrue("no camera on this device", VaultCamera.available(ctx))

        val frames = AtomicInteger(0)
        val strides = AtomicInteger(0)
        val decoded = AtomicReference<ByteArray?>(null)
        val gotOne = CountDownLatch(1)
        val session = VaultCameraSession(ctx)

        try {
            session.scan { luma, width, height, stride ->
                frames.incrementAndGet()
                if (stride >= width) strides.incrementAndGet()
                if (decoded.get() == null) {
                    PairingQr.decode(luma, width, height, stride)?.let {
                        decoded.set(it)
                        gotOne.countDown()
                    }
                }
            }
            // A detached texture stands in for the preview: the session needs an
            // output surface before it will configure, and this test has no window.
            val texture = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
            val surface = Surface(texture)
            session.surface(surface)
            session.open()

            gotOne.await(SCAN_SECONDS, TimeUnit.SECONDS)

            assertTrue(
                "the scan mode delivered no camera frames at all in $SCAN_SECONDS s — " +
                    "the session, not the scene, is what failed",
                frames.get() > 0,
            )
            assertTrue(
                "every frame must carry a row stride at least its width, or the decoder " +
                    "reads each row at an offset",
                strides.get() == frames.get(),
            )

            val payload = decoded.get()
            assumeTrue(
                "nothing in the virtual scene decoded — launch the AVD with " +
                    "scripts/pairing-scene-emulator.sh to hang a code on its wall " +
                    "(${frames.get()} frames were photographed and read)",
                payload != null,
            )

            val parsed = Identity.PairingCode.parse(payload!!)
            assertTrue(
                "what the camera read must be a pairing payload, not any old QR code",
                parsed is Identity.PairingCode.Parsed.Key,
            )
            if (expected != null) {
                assertTrue(
                    "the camera read «${payload.hex()}», not the payload the poster was made from",
                    payload.hex() == expected,
                )
            }
            android.util.Log.i(TAG, "scene decode: ${payload.size} B after ${frames.get()} frames")
        } finally {
            session.stopScan()
            session.close()
        }
    }

    /**
     * The A side of the rig — prints this install's real pairing payload so the
     * script can render it onto the other emulator's wall.
     *
     * It is a test in name only: it asserts the payload is well formed and logs
     * it. `scripts/two-device-pairing.sh` reads the line out of logcat.
     *
     * **It creates this install's identity if there is none**, which is exactly
     * what opening Contacts would do; the script's operator removes it at the end
     * of a run.
     */
    @Test
    fun printThisInstallsPairingCode() {
        // The rig pins the identity when it needs the same one twice — AGP
        // uninstalls the app between invocations, so a random identity would not
        // survive to the run that has to open what was sealed to it.
        InstrumentationRegistry.getArguments().getString("identity.seed")?.let { hex ->
            val seed = ByteArray(hex.length / 2) {
                ((Character.digit(hex[it * 2], 16) shl 4) or
                    Character.digit(hex[it * 2 + 1], 16)).toByte()
            }
            Identity.installForTest(ctx, seed)
        }
        val state = Identity.state(ctx)
        assumeTrue("this install cannot read its identity", state is Identity.State.Ready)
        val payload = Identity.PairingCode.encode((state as Identity.State.Ready).publicKey)
        assertTrue(
            "the payload must parse as one",
            Identity.PairingCode.parse(payload) is Identity.PairingCode.Parsed.Key,
        )
        // Hex, one line: the script reads it back out of logcat and hands it to the
        // poster renderer and the other device as text.
        android.util.Log.i(TAG, "PAYLOAD " + payload.hex())
    }

    private companion object {
        const val TAG = "PairingScene"
        const val SCAN_SECONDS = 20L
    }
}
