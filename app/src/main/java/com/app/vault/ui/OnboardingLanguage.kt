package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.vault.AppLanguage
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * First run's first screen: the app's language — `onboarding-language-screen`.
 *
 * **Before the welcome, and not one of the eight**, so it is drawn outside [OnboardingFrame]
 * with no counter. Everything after it — the welcome, the steps, restore — is read in the
 * language chosen here, so it comes before any of them.
 *
 * The Language sheet's content as a full screen: the globe in the welcome's tinted tile (the
 * two screens before the steps read as one pair, and the globe is what someone who cannot
 * read the screen finds), the title, the sheet's explainer, then [LanguageChoices] — the
 * sheet's own list, so the two never differ.
 *
 * **A tap applies at once and the screen stays**, redrawn in that language with that row
 * selected. No Undo bar: the redrawn screen is the confirmation and every other language is a
 * tap away right here. **Continue is pinned**, never scrolled off below nine rows.
 */
@Composable
fun OnboardingLanguageScreen(
    /** A language, or null for the device's own. */
    onChoose: (AppLanguage.Choice?) -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val chosen = remember(configuration) { AppLanguage.current(context) }
    val device = remember(configuration) { AppLanguage.systemResolved(context) }
    val suggested = remember(configuration) { AppLanguage.suggested(context) }

    Scaffold(
        containerColor = VaultColors.Surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
                    Spacer(Modifier.height(Spacing.xxl))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(VaultColors.Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_language),
                            contentDescription = null,
                            tint = VaultColors.Primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        text = stringResource(R.string.language_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = VaultColors.OnSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.language_explainer),
                        style = MaterialTheme.typography.bodyLarge,
                        color = VaultColors.OnSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                }
                // The list's headings and rows carry the sheet's 16dp inset; 8 more here puts
                // them on the 24dp line the title and the button sit on.
                Box(modifier = Modifier.padding(horizontal = Spacing.sm)) {
                    LanguageChoices(
                        selectedTag = chosen?.tag,
                        device = device,
                        suggested = suggested,
                        rowModifier = { Modifier.padding(horizontal = Spacing.screenInset) },
                        onChoose = onChoose,
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.md)
                    .heightIn(min = Spacing.touchTarget),
            ) {
                Text(
                    text = stringResource(R.string.action_continue),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
