package com.app.vault.ui

import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.app.vault.Identity
import com.app.vault.PairingQr
import com.app.vault.R
import com.app.vault.VaultCameraSession
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/** Which of the three forward-only states the pairing screen is in — `DESIGN.md` §7.21. */
enum class PairingStep { Show, Scan, Name }

/**
 * Pairing — P4-4, `DESIGN.md` §7.21.
 *
 * Show → scan → name, forward only, one primary action each, each leavable by
 * back. **Not two tabs**, and that is the feature rather than a layout preference:
 * pairing is mutual, so after a contact is saved this returns to *show* and says
 * the other person still has to scan. A tab pair leaves that to two people looking
 * at their own phones.
 *
 * The state lives above this composable — the scan runs on the camera thread and
 * must survive recomposition, so what it finds comes back in as arguments.
 */
@Composable
fun PairingScreen(
    step: PairingStep,
    /** This install's public key, for the code being shown. */
    publicKey: ByteArray,
    /** Set after a save, so the show state can say the other side still needs to scan. */
    justSaved: String?,
    /** Whether `CAMERA` is granted right now. False draws the explanation instead. */
    permitted: Boolean,
    onRequestPermission: () -> Unit,
    /**
     * A decoded QR payload's raw bytes, on the main thread. Whether it is a pairing
     * code at all is [Identity.PairingCode]'s question and the caller's to ask —
     * this screen owns the camera, not the meaning of what it saw.
     */
    onDecoded: (ByteArray) -> Unit,
    /** A refusal under the viewfinder. The scan continues under it — §7.21. */
    scanMessage: String?,
    onScan: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember { VaultCameraSession(context) }
    val scanning = step == PairingStep.Scan && permitted

    // The camera's whole life, in one place: opened on arriving at the scan state
    // with the permission in hand, closed on leaving it. `CameraScreen` owns its
    // session the same way, for the same reason — a session held by the activity
    // outlives the screen that can see it.
    DisposableEffect(scanning, session) {
        if (scanning) {
            val main = Handler(Looper.getMainLooper())
            session.scan { luma, width, height, stride ->
                // On the camera thread. Most frames decode to nothing, which is
                // not an error and says nothing (§7.21).
                PairingQr.decode(luma, width, height, stride)?.let { payload ->
                    main.post { onDecoded(payload) }
                }
            }
            session.open()
        }
        onDispose {
            if (scanning) {
                session.stopScan()
                session.close()
            }
        }
    }

    VaultScaffold(title = stringResource(R.string.pairing_title), modifier = modifier, onBack = onBack) {
        when (step) {
            PairingStep.Show -> ShowState(publicKey, justSaved, onScan)
            PairingStep.Scan -> ScanState(
                permitted, onRequestPermission, session.takeIf { scanning }, scanMessage,
            )
            PairingStep.Name -> NameState(name, onNameChange, onSave)
        }
    }
}

@Composable
private fun ShowState(publicKey: ByteArray, justSaved: String?, onScan: () -> Unit) {
    if (justSaved != null) {
        Text(
            text = stringResource(R.string.pairing_saved, justSaved),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
    Text(
        text = stringResource(R.string.pairing_show),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )

    PairingCodeImage(publicKey)

    Text(
        text = stringResource(R.string.pairing_in_person),
        style = MaterialTheme.typography.bodySmall,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.lg),
    )

    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.pairing_scan), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The code itself — dark modules on a light field, whatever scheme the app is in.
 *
 * **Full content width, because the code is dense.** A pairing payload carries a
 * 1,216-byte X-Wing key, which is version 29 at level M — 133 modules a side — so
 * every pixel of width is module size, and module size is what the other phone's
 * camera has to resolve. On a 1080-px-wide screen a module lands at about 6–7 px.
 *
 * §4's palette governs everything a person reads; this is read by a camera, and a
 * dark-palette QR is an unscannable one. Drawn on a `Canvas` from the module grid
 * rather than rendered into a `Bitmap`: no allocation per recomposition, and no
 * resampling on the one image where resampling costs scans.
 */
@Composable
private fun PairingCodeImage(publicKey: ByteArray) {
    val modules = remember(publicKey) {
        PairingQr.matrix(Identity.PairingCode.encode(publicKey))
    }
    val yourCode = stringResource(R.string.pairing_your_code)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.container))
            .background(Color.White)
            // The quiet zone. Four modules is the format's minimum and this is
            // wider, because the card's own edge is the nearest dark thing.
            .padding(Spacing.xl)
            .semantics { contentDescription = yourCode },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val count = modules.size
            if (count == 0) return@Canvas
            // Whole pixels per module: a fractional module boundary is a blurred
            // edge, and a blurred edge is the decoder's problem.
            val module = kotlin.math.floor(minOf(size.width, size.height) / count)
            if (module < 1f) return@Canvas
            val drawn = module * count
            val left = (size.width - drawn) / 2f
            val top = (size.height - drawn) / 2f
            modules.forEachIndexed { y, row ->
                row.forEachIndexed { x, dark ->
                    if (dark) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(left + x * module, top + y * module),
                            size = Size(module, module),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanState(
    permitted: Boolean,
    onRequestPermission: () -> Unit,
    session: VaultCameraSession?,
    scanMessage: String?,
) {
    if (!permitted) {
        // §6.5 — text plus one action, in §7.10's words: the same permission, the
        // same promise, and no reason for a second wording of it.
        Text(
            text = stringResource(R.string.pairing_camera_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
        )
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.camera_allow), style = MaterialTheme.typography.labelLarge)
        }
        return
    }

    Text(
        text = stringResource(R.string.pairing_point),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )

    val looking = stringResource(R.string.pairing_camera_looking)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.container))
            .background(VaultColors.ViewerGround)
            .semantics { contentDescription = looking },
    ) {
        if (session != null) {
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
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The viewfinder frame. It says where to aim and covers nothing —
        // §6.6's rule about chrome over a frame, kept to an outline.
        Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * 0.15f
            val stroke = 3.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = 0.7f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
    }

    // A refusal, under the frame, with the scan still running — §7.21. Nothing here
    // ends the scan, because the next code is the one that works.
    if (scanMessage != null) {
        Text(
            text = scanMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }
}

@Composable
private fun NameState(name: String, onNameChange: (String) -> Unit, onSave: () -> Unit) {
    Text(
        text = stringResource(R.string.pairing_who),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )

    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.vault_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = onSave,
        enabled = name.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(stringResource(R.string.pairing_save), style = MaterialTheme.typography.labelLarge)
    }
}

