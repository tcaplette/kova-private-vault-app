package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop and the metering rectangle — `camera-conventions`.
 *
 * These are the half of tap-to-focus and pinch-to-zoom that can be wrong without
 * anything failing: the camera accepts a rectangle in the wrong corner as readily as
 * the right one, and what the user sees is a focus that goes somewhere else. On a
 * device that reads as "tapping does nothing", which is what the last camera bug read
 * as too, so this is checked here rather than by pointing a phone at a wall.
 *
 * `Box` exists in plain Kotlin for exactly this — `android.graphics.Rect` is a stub
 * on the JVM, the same reason `Dim` exists beside it.
 */
class CameraGeometryTest {

    private val sensor = Box(0, 0, 4000, 3000)

    @Test
    fun oneTimesZoomIsTheWholeSensor() {
        assertEquals(sensor, VaultCamera.cropFor(sensor, 1f, 8f))
    }

    @Test
    fun zoomingCropsTheCentre() {
        val crop = VaultCamera.cropFor(sensor, 2f, 8f)
        assertEquals(2000, crop.width)
        assertEquals(1500, crop.height)
        // Centred: the same margin on both sides, in both axes.
        assertEquals(crop.left, sensor.right - crop.right)
        assertEquals(crop.top, sensor.bottom - crop.bottom)
    }

    /** A pinch past the end stops at the end rather than asking for a crop of nothing. */
    @Test
    fun theZoomIsClampedBothWays() {
        assertEquals(VaultCamera.cropFor(sensor, 4f, 4f), VaultCamera.cropFor(sensor, 99f, 4f))
        assertEquals(sensor, VaultCamera.cropFor(sensor, 0.2f, 4f))
        // A device reporting no digital zoom has a ceiling of 1 and cannot be zoomed.
        assertEquals(sensor, VaultCamera.cropFor(sensor, 4f, 1f))
    }

    /** An unrotated sensor maps a tap straight through. */
    @Test
    fun anUnrotatedSensorMapsTheTapWhereItLanded() {
        val box = VaultCamera.meteringBox(0.5f, 0.5f, sensor, sensorOrientation = 0, front = false)
        assertEquals(2000, (box.left + box.right) / 2)
        assertEquals(1500, (box.top + box.bottom) / 2)
    }

    /**
     * The ordinary back camera: mounted at 90°, so the top-left of the preview is the
     * **bottom-left** of the sensor. A sign error here focuses on the opposite corner
     * of the frame from the one the user touched.
     */
    @Test
    fun aBackCameraAtNinetyDegreesTurnsTheTapBack() {
        val box = VaultCamera.meteringBox(0f, 0f, sensor, sensorOrientation = 90, front = false)
        assertTrue("x should sit at the sensor's left edge", box.left < sensor.width / 4)
        assertTrue("y should sit at the sensor's bottom edge", box.top > sensor.height / 2)
    }

    /**
     * The front lens shows a mirror, so a tap on the left of the picture is the right
     * of the sensor. This is the pair that is correct on one lens and opposite on the
     * other, and invisible to anyone who tests with one.
     */
    @Test
    fun theFrontLensIsMirroredBeforeItIsTurned() {
        val back = VaultCamera.meteringBox(0.1f, 0.5f, sensor, sensorOrientation = 0, front = false)
        val mirrored = VaultCamera.meteringBox(0.1f, 0.5f, sensor, sensorOrientation = 0, front = true)
        assertEquals(sensor.width - (back.left + back.right) / 2, (mirrored.left + mirrored.right) / 2)
    }

    /** A tap in a corner still produces a whole rectangle, inside the crop. */
    @Test
    fun aTapAtTheEdgeStaysInsideTheCropAndKeepsItsSize() {
        listOf(0f to 0f, 1f to 1f, 0f to 1f, 1f to 0f).forEach { (x, y) ->
            listOf(0, 90, 180, 270).forEach { orientation ->
                val box = VaultCamera.meteringBox(x, y, sensor, orientation, front = false)
                assertTrue("$x,$y at $orientation ran off the left", box.left >= sensor.left)
                assertTrue("$x,$y at $orientation ran off the top", box.top >= sensor.top)
                assertTrue("$x,$y at $orientation ran off the right", box.right <= sensor.right)
                assertTrue("$x,$y at $orientation ran off the bottom", box.bottom <= sensor.bottom)
                assertTrue("$x,$y at $orientation shrank at the edge", box.width == 600)
            }
        }
    }

    /** Zoomed in, the metering rectangle is measured against the crop and not the sensor. */
    @Test
    fun meteringFollowsTheZoomedCrop() {
        val crop = VaultCamera.cropFor(sensor, 2f, 8f)
        val box = VaultCamera.meteringBox(0.5f, 0.5f, crop, sensorOrientation = 0, front = false)
        assertTrue(box.left >= crop.left && box.right <= crop.right)
        assertEquals(crop.width * 15 / 100, box.width)
    }
}
