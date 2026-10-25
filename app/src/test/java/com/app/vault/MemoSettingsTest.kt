package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `memos-settings` and `notes-settings` — the defaults, unknown names, and the quality mapping. */
class MemoSettingsTest {

    @Test
    fun memoDefaultsAreHowMemosWereRecordedAndPlayed() {
        val d = MemoSettings()
        assertEquals(RecordingQuality.Standard, d.quality)
        assertFalse("mono, as before", d.stereo)
        assertFalse("the phone's microphone, as before", d.bluetooth)
        assertEquals(PlaybackSpeed.Normal, d.speed)
        assertFalse(d.skipSilence)
    }

    @Test
    fun standardIsTheRecorderTheAppAlwaysHad() {
        assertEquals(64_000, RecordingQuality.Standard.bitRate)
        assertEquals(44_100, RecordingQuality.Standard.sampleRate)
    }

    @Test
    fun theQualitiesMapToTheirRates() {
        assertEquals(128_000, RecordingQuality.High.bitRate)
        assertEquals(48_000, RecordingQuality.High.sampleRate)
        assertEquals(256_000, RecordingQuality.Highest.bitRate)
        assertEquals(48_000, RecordingQuality.Highest.sampleRate)
        assertEquals(listOf("Standard", "High", "Highest"), RecordingQuality.entries.map { TestWords.text(it.label) })
    }

    @Test
    fun anUnknownQualityReadsStandard() {
        assertEquals(RecordingQuality.Standard, RecordingQuality.of("Lossless"))
        assertEquals(RecordingQuality.Standard, RecordingQuality.of(null))
        assertEquals(RecordingQuality.Highest, RecordingQuality.of("Highest"))
    }

    @Test
    fun noteDefaults() {
        val d = NoteSettings()
        assertFalse("ticked items stay where they are, as before", d.moveChecked)
        assertTrue("the incognito keyboard is on — D4", d.incognito)
    }
    /** The ADTS header the encoder writes ahead of each frame — what every player reads first. */
    @Test
    fun theAdtsHeaderStatesRateChannelsAndLength() {
        val h = ByteArray(7)
        VoiceMemo.adtsHeader(h, frameLength = 371, sampleRate = 48_000, channels = 2)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals("syncword, MPEG-4, no CRC", 0xF1, h[1].toInt() and 0xFF)
        assertEquals("AAC LC", 1, (h[2].toInt() and 0xC0) shr 6)
        assertEquals("48 kHz is index 3", 3, (h[2].toInt() and 0x3C) shr 2)
        assertEquals("two channels", 2, ((h[2].toInt() and 0x01) shl 2) or ((h[3].toInt() and 0xC0) shr 6))
        val length = ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
        assertEquals(371, length)
        VoiceMemo.adtsHeader(h, frameLength = 200, sampleRate = 44_100, channels = 1)
        assertEquals("44.1 kHz is index 4", 4, (h[2].toInt() and 0x3C) shr 2)
        assertEquals(1, ((h[2].toInt() and 0x01) shl 2) or ((h[3].toInt() and 0xC0) shr 6))
    }

    @Test
    fun theBluetoothMicrophoneIsChosenOnlyWhenOnAndPresent() {
        val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val builtIn = android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
        assertTrue(VoiceMemo.useBluetooth(true, listOf(builtIn, sco)))
        assertFalse("off", VoiceMemo.useBluetooth(false, listOf(builtIn, sco)))
        assertFalse("no headset", VoiceMemo.useBluetooth(true, listOf(builtIn)))
    }
}
