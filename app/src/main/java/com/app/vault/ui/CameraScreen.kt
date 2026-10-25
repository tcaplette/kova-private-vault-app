package com.app.vault.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.app.vault.R
import com.app.vault.VaultCamera
import com.app.vault.VaultCameraSession
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import kotlinx.coroutines.delay

/**
 * Photographing and filming straight into the vault — P2-17, rebuilt by
 * `camera-conventions`.
 *
 * **Not `CaptureSheet`.** That is P2-4's intruder-capture enrolment, which turns
 * modes on and never shows a preview; this is the owner pointing the camera at
 * something. Two screens, two names, and no shared state.
 *
 * ***It used to be a form.*** A `VaultScaffold` with an app bar titled "Camera", a
 * letterboxed preview pasted underneath it, the word `Recording` as body text, two
 * text buttons reading `Mode · Photo` and `Lens · Back`, and a paragraph of prose
 * below the shutter. Every one of those is correct on a settings page and none of
 * them is what a person reaches for holding a phone up at something. A camera is a
 * viewfinder with controls floating on it, and that is not a matter of taste: it is
 * where a thumb has been taught to expect a shutter for fifteen years.
 *
 * **No wrapper, for `VaultViewer`'s reason.** §6.6 forbids framing the media, and a
 * viewfinder is the media before it exists. The frame fills the screen; the chrome is
 * drawn over it in [VaultColors.OnViewerGround], which is the foreground pinned light
 * in both schemes precisely because the ground under it never follows the scheme.
 * `safeDrawingPadding` is on the *controls*, never on the frame — the viewer's rule,
 * one screen along.
 *
 * **The controls, where every camera puts them.** Close top-left. Elapsed time and a
 * record dot top-centre, while a recording is running. A `Photo | Video` strip above
 * the shutter, the selected half filled. The shutter bottom-centre, 72dp: a white
 * ring around a white disc for a photograph, [VaultColors.Record] for video, and a
 * rounded square while recording. The lens switch bottom-right, as the two circular
 * arrows every camera uses.
 *
 * **Tap to focus, pinch to zoom.** Both are what the hardware has always been able to
 * do and neither was wired to anything; the file used to list "no zoom" among the
 * things it refused, which is a missing feature described as restraint. A tap puts a
 * reticle where it landed and meters there; a pinch crops the sensor and prints the
 * level while it changes.
 *
 * **State is in words as well as shape** (§6.4). The mode strip names both modes and
 * marks the live one `selected`; the shutter's description says which act it will
 * perform; the elapsed time is a readout and not a colour.
 *
 * ***Capture used to happen silently, and that was the reported fault.*** The shutter
 * was pressed, the frame did not change, no sound was asked for, and the photograph
 * went into the vault with nothing on screen to say so. Three signals answer it, which
 * is what both platform cameras do and for the reason they do it — each proves a
 * different thing:
 *
 * - **A haptic**, at the instant of capture. *Something happened.*
 * - **A blink** — the frame goes to ground for [BLINK_MS] and comes back. *The camera
 *   did it*, rather than a tap landing somewhere.
 * - **The receipt**, bottom-left, opposite the lens switch. *It is in the vault*, and
 *   it stays there after the moment has passed, which is the one the other two cannot
 *   make. It is the corner thumbnail every camera has had for fifteen years.
 *
 * **No shutter sound.** Directed: this app is used quietly. The haptic is the silent
 * half of the same signal and carries it alone.
 *
 * **The receipt is this session's, not the vault's most recent item.** A camera's
 * corner thumbnail normally shows the last thing in the roll, from whenever. Here that
 * would put a photograph from yesterday on screen the moment the camera opens, in
 * front of whoever is stood behind the phone — so it starts empty and holds only what
 * this session captured. That is a deliberate departure from the convention and the
 * only one.
 */
@Composable
fun CameraScreen(
    /** Whether `CAMERA` is granted right now. False draws the explanation instead. */
    permitted: Boolean,
    /** Asks for `CAMERA`. The one action on the unpermitted screen (§6.5). */
    onRequestPermission: () -> Unit,
    /** Asks for `RECORD_AUDIO`, on entering video mode and never for photography. */
    onRequestAudio: () -> Unit,
    /** JPEG bytes, on the main thread. The caller stores them and zeroes them. */
    onPhoto: (ByteArray) -> Unit,
    /**
     * Opens the pipe a recording streams into, or null when the vault cannot take
     * one. The caller owns both ends — this screen only hands the write end to the
     * recorder and gives it back at [endRecording].
     */
    beginRecording: () -> ParcelFileDescriptor?,
    /** The recording is over: close the write end, finish the write, commit. */
    endRecording: () -> Unit,
    /**
     * The last capture of this session, once it is in the vault — the receipt's
     * picture. Null before anything has been captured, after one has failed, and for
     * a capture whose thumbnail could not be made.
     */
    receipt: Bitmap?,
    /**
     * Whether there *is* a capture behind the receipt.
     *
     * **Not `receipt != null`**, and the difference is the whole point of the
     * control: a thumbnail is generated after the item is committed and can fail
     * where the item did not. Tying the receipt to the picture would mean a
     * photograph that is safely in the vault leaves nothing on screen, which is the
     * exact fault this screen was reported for.
     */
    captured: Boolean,
    /**
     * True from the moment a capture is handed over until it is committed or has
     * failed. The receipt shows the wait rather than appearing late: sealing a
     * photograph is not instant, and a corner that stays empty for that gap reads as
     * nothing having happened.
     */
    saving: Boolean,
    /**
     * Opens the last capture. The convention exactly — a camera's thumbnail opens
     * that item, and back returns here with the viewfinder still live.
     */
    onOpenReceipt: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when the vault or the hardware could not do it; says so instead of failing silently. */
    problem: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    var mode by remember { mutableStateOf(VaultCamera.Mode.PHOTO) }
    var front by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var askedAudio by remember { mutableStateOf(VaultCamera.audioPermitted(context)) }
    var hardwareProblem by remember { mutableStateOf<String?>(null) }
    val recordingFailed = stringResource(R.string.camera_recording_failed)
    var busy by remember { mutableStateOf(false) }
    val session = remember { VaultCameraSession(context) }
    val bothLenses = remember { VaultCamera.hasBothLenses(context) }

    // Where the last tap landed, in preview pixels, and when. The reticle is drawn
    // there and fades on its own — a mark that stayed would be a smudge on the frame.
    var focus by remember { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    // Counting up from the moment the recorder said it had started, which is not the
    // moment the shutter was pressed: a timer that starts on the tap counts the
    // hardware's start-up into the recording's length.
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    // The capture instant, as a stamp rather than a flag: two shutter taps in quick
    // succession are two blinks, and a boolean would swallow the second.
    var blinkAt by remember { mutableLongStateOf(0L) }
    var blinking by remember { mutableStateOf(false) }
    // Honours "Remove animations". The blink and the receipt's entrance are the two
    // things that move here; the haptic and the receipt itself are not motion and are
    // never withheld, because they are the feedback rather than the flourish.
    val animates = remember { ValueAnimator.areAnimatorsEnabled() }

    /**
     * The instant of capture, in the two channels that are not the vault's to give.
     *
     * `CONFIRM` is the platform's own constant for exactly this — an action the user
     * asked for has completed — and it is what a camera shutter uses.
     */
    fun captured() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (animates) blinkAt = System.nanoTime()
    }

    LaunchedEffect(blinkAt) {
        if (blinkAt != 0L) {
            blinking = true
            delay(BLINK_MS)
            blinking = false
        }
    }

    DisposableEffect(session) {
        session.onProblem = {
            hardwareProblem = it
            // A capture that failed is not a capture still running: without this the
            // shutter stays disabled for a frame that is never coming.
            busy = false
        }
        onDispose {
            // A screen that is leaving stops its recording first: the recorder must
            // release the pipe before the write can finish, and the display must
            // stop being held awake whether the exit was back, a lock, or a crash
            // upstream.
            if (recording) {
                session.stopRecording()
                endRecording()
            }
            view.keepScreenOn = false
            session.close()
        }
    }

    DisposableEffect(permitted) {
        if (permitted) session.open()
        onDispose {}
    }

    // The reticle's own life. Keyed on the tap, so a second tap restarts it rather
    // than inheriting what was left of the first.
    LaunchedEffect(focus) {
        if (focus != null) {
            delay(RETICLE_MS)
            focus = null
        }
    }

    // One tick a second while running. Not a stopwatch: the readout is seconds, and
    // a frame-rate timer would be recomposing the whole screen for a digit that
    // changes sixty times less often.
    LaunchedEffect(recording, startedAt) {
        while (recording) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(TICK_MS)
        }
    }

    if (!permitted) {
        // §6.5 — an empty state is text plus one action, and it keeps the wrapper:
        // there is no viewfinder here to frame, so this is an ordinary screen and
        // should look like one.
        VaultScaffold(title = stringResource(R.string.fab_camera), modifier = modifier, onBack = onCancel) {
            Text(
                text = stringResource(R.string.camera_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.camera_allow), style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }

    Box(modifier.fillMaxSize().background(VaultColors.ViewerGround)) {
        // **The frame fills the screen and is never inset.** A preview letterboxed
        // to the sensor's ratio inside a scaffold is honest about the sensor and
        // wrong about the act: you are pointing a phone at something, and the phone
        // is the frame. The surface crops to fill, as both platform cameras do.
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) =
                            session.surface(holder.surface)

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = session.surface(holder.surface)

                        override fun surfaceDestroyed(holder: SurfaceHolder) =
                            session.surface(null)
                    })
                }
            },
            modifier = Modifier
                .fillMaxSize()
                // Both gestures on the frame itself, which is where they are aimed.
                // Separate `pointerInput` blocks: a tap detector and a transform
                // detector in one block would race, and the pinch would eat the tap.
                .pointerInput(front) {
                    detectTapGestures { tap ->
                        focus = tap
                        session.focusAt(
                            nx = (tap.x / size.width).coerceIn(0f, 1f),
                            ny = (tap.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                }
                .pointerInput(front) {
                    detectTransformGestures { _, _, gesture, _ ->
                        if (gesture != 1f) session.pinch(gesture) { zoom = it }
                    }
                },
        )

        // Over the frame, under the controls: the shutter closing is about the
        // picture, and the chrome does not go dark with it. Drawn on the viewer
        // ground rather than white — a white flash reads as the flash firing, which
        // is a different claim about what the camera just did.
        if (blinking) {
            Box(Modifier.fillMaxSize().background(VaultColors.ViewerGround))
        }

        focus?.let { at ->
            // The reticle sits *on* the tap, so it is offset by half its own size.
            // Drawn in the pinned foreground: it is chrome over a viewfinder, and the
            // viewfinder is whatever the lens is looking at.
            val half = with(density) { (Reticle / 2).roundToPx() }
            Box(
                Modifier
                    .offset { IntOffset(at.x.toInt() - half, at.y.toInt() - half) }
                    .size(Reticle)
                    .border(Hairline, VaultColors.OnViewerGround, CircleShape),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                // **The inset is on the controls and never on the frame** —
                // `VaultViewer`'s rule, and the reason this screen is exempt from
                // `insetPaddingLivesOnlyInTheWrapper` at all. A shutter under the
                // gesture bar is a shutter that cannot be pressed.
                .safeDrawingPadding()
                .padding(Spacing.lg),
        ) {
            Box(Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.CenterStart).size(Spacing.touchTarget),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.camera_close),
                        tint = VaultColors.OnViewerGround,
                    )
                }

                if (recording) {
                    // Top-centre, a dot and a clock — the readout every camera has
                    // in this position. Mono with tabular figures, so the row does
                    // not shift under the eye as the seconds turn over.
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Box(
                            Modifier
                                .size(RecordDot)
                                .background(VaultColors.Record, CircleShape),
                        )
                        Text(
                            text = clock(elapsed),
                            style = VaultMono.bodyMedium,
                            color = VaultColors.OnViewerGround,
                        )
                    }
                }

                if (zoom > 1f) {
                    // Only while it is not 1×. A permanent `1.0×` over the frame is
                    // chrome stating the absence of a state.
                    Text(
                        text = "%.1f×".format(zoom),
                        style = VaultMono.bodyMedium,
                        color = VaultColors.OnViewerGround,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            val message = problem ?: hardwareProblem
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Record,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                )
            }

            Box(Modifier.weight(1f))

            // The mode strip sits above the shutter and names both halves, so what a
            // tap does is readable before it is tapped. The old control was one text
            // button reading `Mode · Photo`, which is the state and the act sharing
            // one label and neither being legible.
            if (!recording) {
                ModeStrip(
                    mode = mode,
                    onMode = { chosen ->
                        if (chosen == VaultCamera.Mode.VIDEO && !askedAudio) {
                            // P2-4's rule, one screen along: a permission is asked for
                            // at the moment the thing needing it is chosen — never at
                            // the moment the screen opens, and never for photography.
                            askedAudio = true
                            onRequestAudio()
                        }
                        mode = chosen
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = Spacing.lg),
                )
            }

            Box(Modifier.fillMaxWidth()) {
                Shutter(
                    recording = recording,
                    mode = mode,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.Center),
                    onClick = {
                        val rotation = VaultCamera.rotationDegrees(
                            (context as? Activity)?.display?.rotation ?: 0
                        )
                        when {
                            mode == VaultCamera.Mode.PHOTO -> {
                                busy = true
                                session.takePhoto(rotation) { jpeg ->
                                    busy = false
                                    // On the frame arriving, not on the tap: the tap
                                    // is a request and this is the exposure. A blink
                                    // fired at the press would blink for a capture
                                    // the hardware then failed.
                                    captured()
                                    onPhoto(jpeg)
                                }
                            }
                            recording -> {
                                recording = false
                                view.keepScreenOn = false
                                // The haptic alone: a blink says a frame was taken,
                                // and stopping a recording did not take one. What
                                // says the clip is saved is the receipt.
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                session.stopRecording { endRecording() }
                            }
                            else -> {
                                val fd = beginRecording()
                                if (fd == null) {
                                    hardwareProblem = recordingFailed
                                } else {
                                    busy = true
                                    session.startRecording(fd.fileDescriptor, rotation) { started ->
                                        busy = false
                                        // The same confirmation the other two acts
                                        // get, and only when the recorder actually
                                        // started — a buzz for a recording that did
                                        // not begin is a lie the user acts on.
                                        if (started) {
                                            view.performHapticFeedback(
                                                HapticFeedbackConstants.CONFIRM
                                            )
                                        }
                                        recording = started
                                        startedAt = System.currentTimeMillis()
                                        elapsed = 0L
                                        // Held while recording only. A screen timeout
                                        // must not end a recording the user is watching
                                        // happen; it is a display flag and not an
                                        // auto-lock suppression, and the vault's lock
                                        // rules are untouched by it.
                                        view.keepScreenOn = started
                                        // The pipe the vault opened has to be given
                                        // back even when nothing was recorded into it.
                                        if (!started) endRecording()
                                    }
                                }
                            }
                        }
                    },
                )

                // Bottom-left, opposite the lens switch and where every camera
                // has put it. Hidden while recording, the switch's rule: the
                // chrome recedes to the two things a recording is about, and the
                // receipt is about a capture that is over.
                if (!recording) {
                    CaptureReceipt(
                        bitmap = receipt,
                        captured = captured,
                        saving = saving,
                        animates = animates,
                        onOpen = onOpenReceipt,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }

                if (bothLenses && !recording) {
                    // Bottom-right, where a thumb already reaches for it. Absent
                    // while recording: the other lens is a different device and
                    // reopening it mid-recording would end the recording.
                    IconButton(
                        onClick = {
                            front = !front
                            zoom = 1f
                            session.lens(front)
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(Spacing.touchTarget),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_flip_camera),
                            contentDescription = if (front) {
                                stringResource(R.string.camera_switch_back)
                            } else {
                                stringResource(R.string.camera_switch_front)
                            },
                            tint = VaultColors.OnViewerGround,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `Photo | Video`, as a two-segment strip — `camera-conventions`.
 *
 * Both modes are named and the live one is filled, which is the control iOS, Keep's
 * camera and every stock Android camera draw. The old one was a single text button
 * whose label was the mode it was in, so the only way to learn what tapping did was
 * to tap it and change modes.
 *
 * The fill is [VaultColors.OnViewerGround] with the ground's own colour as its
 * foreground — a chrome inversion rather than an accent, because §6.1 gives this
 * screen exactly one primary-coloured control and the shutter has it.
 */
@Composable
private fun ModeStrip(
    mode: VaultCamera.Mode,
    onMode: (VaultCamera.Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(VaultColors.Scrim.copy(alpha = STRIP_ALPHA), CircleShape)
            .padding(Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Segment(stringResource(R.string.capture_mode_photo), mode == VaultCamera.Mode.PHOTO) { onMode(VaultCamera.Mode.PHOTO) }
        Segment(stringResource(R.string.capture_mode_video), mode == VaultCamera.Mode.VIDEO) { onMode(VaultCamera.Mode.VIDEO) }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                color = if (on) VaultColors.OnViewerGround else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) VaultColors.ViewerGround else VaultColors.OnViewerGround,
        )
    }
}

/**
 * The one primary action on this screen (§6.1) — 72dp, bottom-centre.
 *
 * **A white ring around a disc**, which is the shutter on both platforms and on every
 * camera app either store carries. It was 60dp and filled with the app's `Primary`:
 * a shutter in the app's action colour stands where a colour everybody already knows
 * belongs.
 *
 * The disc is [VaultColors.Record] in video mode and a rounded square while a
 * recording runs. The shape is not the only thing that says which — the elapsed time
 * is running above it and the content description changes with the act. §6.4 is the
 * rule being met, not worked around.
 */
@Composable
private fun Shutter(
    recording: Boolean,
    mode: VaultCamera.Mode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        when {
            recording -> R.string.stop_recording
            mode == VaultCamera.Mode.PHOTO -> R.string.camera_take_photo
            else -> R.string.start_recording
        }
    )
    Box(
        modifier = modifier
            .size(ShutterSize)
            .border(ShutterRing, VaultColors.OnViewerGround, CircleShape)
            .padding(Spacing.xs)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) ShutterStop else ShutterDisc)
                .background(
                    color = when {
                        !enabled -> VaultColors.OnViewerGroundVariant
                        mode == VaultCamera.Mode.VIDEO -> VaultColors.Record
                        else -> VaultColors.OnViewerGround
                    },
                    shape = if (recording) RoundedCornerShape(Radius.control) else CircleShape,
                ),
        )
    }
}

/** `m:ss`, or `h:mm:ss` once there is an hour to show. */
private fun clock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** The shutter, at the size both platforms draw it. */
/**
 * The corner receipt — the capture that just went into the vault.
 *
 * ***Three states and one of them is nothing.*** Empty before a capture, because an
 * empty frame with a border reads as a control that does not work. A wait while the
 * item is sealed, which is not instant and is the gap the old screen spent showing
 * nothing at all. Then the picture, which is the only one of the three that persists
 * after the moment has passed — and the whole point of the control.
 *
 * **It enters by shrinking**, which is the motion both platform cameras use: the frame
 * you just took contracts into the corner, and that is what ties the thing on screen to
 * the thing that was in front of the lens a moment ago. A picture that simply appeared
 * would be a new object rather than the same one, arrived.
 *
 * The border is what makes it a control over an unknown frame — a thumbnail of a dark
 * photograph on a dark viewfinder has no edge of its own.
 */
@Composable
private fun CaptureReceipt(
    bitmap: Bitmap?,
    captured: Boolean,
    saving: Boolean,
    animates: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!captured && !saving) return
    val savingLabel = stringResource(R.string.camera_saving)

    // Keyed on the capture, so a second one enters as its own arrival rather than
    // inheriting the first's finished animation.
    val entrance = remember(bitmap, captured) {
        Animatable(if (animates && captured) 0.6f else 1f)
    }
    LaunchedEffect(bitmap, captured) {
        if (captured && animates) {
            entrance.animateTo(1f, tween(RECEIPT_MS, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier
            .size(Spacing.touchTarget)
            .graphicsLayer {
                scaleX = entrance.value
                scaleY = entrance.value
                alpha = entrance.value
            }
            .clip(RoundedCornerShape(Radius.control))
            .background(VaultColors.ViewerGround)
            .border(Hairline, VaultColors.OnViewerGround, RoundedCornerShape(Radius.control))
            .then(
                if (captured) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.camera_open_receipt), onClick = onOpen)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                // Named as what it is and what it proves. A screen reader user takes
                // the confirmation from this line, and nothing else on this screen
                // says it.
                contentDescription = stringResource(RECEIPT_LABEL),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Saved, and no picture to show for it. The mark says the half that
            // matters — a capture with no thumbnail is still a capture, and an empty
            // corner would report it as nothing having happened.
            captured -> Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = stringResource(RECEIPT_LABEL),
                tint = VaultColors.OnViewerGround,
                modifier = Modifier.size(Spacing.xl),
            )
            else -> CircularProgressIndicator(
                color = VaultColors.OnViewerGround,
                strokeWidth = Hairline,
                modifier = Modifier
                    .size(Spacing.lg)
                    .semantics { contentDescription = savingLabel },
            )
        }
    }
}

private val ShutterSize = 72.dp
private val ShutterRing = 3.dp
private val ShutterDisc = 58.dp

/** The stop square, inside the same ring. */
private val ShutterStop = 28.dp

/** The focus reticle, and how long it stays. */
private val Reticle = 72.dp
private val Hairline = 2.dp
private const val RETICLE_MS = 900L

/** The record dot beside the elapsed time. */
private val RecordDot = 10.dp

/** The mode strip's ground: dark enough to hold letters over a bright frame. */
private const val STRIP_ALPHA = 0.55f

/** One tick a second — see the effect that drives it. */
private const val TICK_MS = 500L

/**
 * The shutter blink. 110ms: long enough to register as the frame going away and
 * coming back, short enough that it is the shutter and not a stall.
 */
private const val BLINK_MS = 110L

/** The receipt's entrance — inside the 150–300ms a micro-interaction gets. */
private const val RECEIPT_MS = 220

/**
 * What the receipt is called to a screen reader, in both of its saved states: the
 * claim is that the item is in the vault, and that is true with or without a picture
 * of it.
 */
private val RECEIPT_LABEL = R.string.camera_receipt
