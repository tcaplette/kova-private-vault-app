package com.app.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The frame every screen sits in — P2-6.
 *
 * **This is the one component wrapper the app has, and it is the one `DESIGN.md`
 * §6 allows.** §6 forbids a component library and says the first wrapper gets
 * written when a *second* screen needs the same thing; thirteen screens needed
 * this one. A second wrapper needs its own argument, not this one's precedent.
 *
 * It exists because the alternative was proved worse in this repo. Before P2-6
 * every screen framed its own layout, and exactly one of them — `VaultGrid` — knew
 * that Android 15 draws apps edge to edge. The other thirteen rendered under the
 * status bar, and nothing anywhere handled the navigation bar or the keyboard, so
 * the passphrase field on the unlock screen sat behind the IME. An inset decision
 * repeated fourteen times is an inset decision made once and forgotten thirteen
 * times.
 *
 * [Spacing.barHeight] and [WindowInsets.Companion.safeDrawing] are therefore
 * decided here, once, for everything that uses this.
 *
 * @param title names the screen, and is announced as a heading.
 * @param onBack draws the back affordance when non-null. Entry screens — setup,
 *   unlock — pass null: there is nothing behind them, and back should leave the
 *   activity. `MainActivity`'s single `BackHandler` handles the gesture; this
 *   parameter only draws the visible control.
 * @param actions the bar's trailing slot. Text labels, per §6.2 and §6.4 — see
 *   the note on icons below.
 * @param scrollable false for screens that scroll their own content, which is
 *   `VaultGrid` and nothing else. It also suppresses the screen inset, because
 *   the grid's gutters are [Spacing.gridGutter] and not [Spacing.screenInset].
 *
 * **On icons.** The back affordance is the only icon-only control in the app, and
 * it carries a content description. Everything else in a bar is a text label with
 * an optional glyph beside it. That is not decoration policy, it is §6.2 — the
 * export action is "not an icon, not behind overflow", because a claim the app
 * makes about itself has to be readable — and §6.4, which keeps meaning off
 * colour and off shape alone.
 */
/**
 * A bar `VaultScaffold` draws under its content, supplied by an ancestor.
 *
 * Exists for `first-run-onboarding`'s step strip and nothing else so far. A
 * composition local rather than a parameter because the five screens the flow
 * hosts each call [VaultScaffold] themselves, and threading a parameter through
 * all of them would give every one of them an argument that means nothing outside
 * first run — which is exactly the duplication that change's D3 refuses.
 *
 * Null everywhere else, so every other screen is byte-for-byte the shape it was.
 */
val LocalScaffoldBottomBar = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScaffold(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * P2-11. The bar's title is `titleLarge` everywhere except a title that is a
     * **count**, which takes the mono face so it does not reflow as it changes.
     * A parameter rather than a slot: a slot would let any screen put arbitrary
     * composition in the bar, which is the wrapper losing the one job it has.
     */
    titleStyle: TextStyle? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * A leading control for screens that have no back — P2-7 puts the grid's lock
     * here. Ignored when [onBack] is set: back owns the leading slot wherever
     * there is something to go back to, and that contract does not loosen because
     * a screen wants a shortcut there.
     */
    leadingAction: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    /**
     * The setup screen centres its content, and P2-6 is a framing change, not a
     * restyling one. This passes through rather than being flattened into the
     * default so that screen keeps the layout it was designed with.
     */
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    /**
     * A layer drawn over [content], inset exactly as content is — the grid's
     * floating action button and the scrim that dismisses it, and nothing else so
     * far.
     *
     * **Why a slot here and not a `Scaffold` FAB slot.** `Scaffold` positions a FAB
     * for you but draws it inside itself, so a scrim that dismisses the button's
     * expanded set would have to sit *under* the button to avoid swallowing its own
     * taps — and `Scaffold` gives no layer between the two. This slot is a `BoxScope`
     * over the content, so the caller stacks the scrim and the button in the order it
     * needs and aligns the button itself.
     *
     * **Why it is here at all rather than in `VaultGrid`.** `insetPaddingLivesOnlyInTheWrapper`
     * is not a formality: a floating control that ignores the navigation bar sits
     * under it, and `FLAG_SECURE` means no screenshot would ever show anyone that.
     * Taking `innerPadding` here is the only way the caller gets the inset without
     * naming an inset modifier of its own.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
    /**
     * `vault-list-refresh`, D6. The home screen's large title: `LargeTopAppBar` at
     * [Spacing.largeBarHeight], collapsing into the ordinary [Spacing.barHeight] bar as the
     * content scrolls. The vault list passes true and nothing else does.
     */
    largeTitle: Boolean = false,
    /**
     * `unlock-screen-redesign`, D2. False omits the bar entirely — the unlock screen
     * opens on its brand mark and names itself with its own heading, and an empty-title
     * bar would be 64dp of dead chrome above it. Insets are still applied here, so the
     * content clears the status bar exactly as it does under a bar.
     */
    topBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = if (largeTitle) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else null
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = VaultColors.Surface,
        scrolledContainerColor = VaultColors.Surface,
        titleContentColor = VaultColors.OnSurface,
        navigationIconContentColor = VaultColors.OnSurface,
        actionIconContentColor = VaultColors.OnSurface,
    )
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier),
        containerColor = VaultColors.Surface,
        // `first-run-onboarding`. The flow's step strip, supplied through a
        // composition local rather than a parameter on all five hosted screens.
        //
        // **It goes here because insets are decided here.** `ScreenChromeTest`
        // enforces that `statusBarsPadding`, `navigationBarsPadding`,
        // `systemBarsPadding` and `safeDrawingPadding` appear in this file and
        // nowhere else, and it is right to: a strip that named its own inset
        // modifier would be a second place deciding the same thing. `Scaffold`
        // insets its own `bottomBar` from `contentWindowInsets` below, so the
        // strip clears the navigation bar without naming an inset at all.
        // **The bar gets the bottom inset here, and nowhere else.**
        //
        // `Scaffold` applies `contentWindowInsets` to the *content* it hands back as
        // `innerPadding`; it does **not** apply them to this slot. Material's own
        // `BottomAppBar` consumes them internally, so a screen that puts one here is
        // fine by accident — and a bare `Row`, which is what the note editor's
        // formatting bar is, sits underneath the gesture bar. That was reported: the
        // bottom of the note screen unreadable, in the same place as the device's own
        // bar.
        //
        // `safeDrawing.only(Bottom)` and not `navigationBarsPadding`, because this
        // slot has to clear *whichever* is there: the gesture bar when the keyboard is
        // down, and the keyboard when it is up. One modifier answers both, and the
        // `Scaffold` measures the result, so the content below clears the taller bar
        // without anything else being told.
        bottomBar = {
            LocalScaffoldBottomBar.current?.let { bar ->
                Box(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    )
                ) { bar() }
            }
        },
        // The whole inset story, in one parameter. `safeDrawing` is system bars ∪
        // display cutout ∪ IME, so this covers the status bar, the navigation bar
        // and the keyboard together. Choosing `systemBars` here instead would fix
        // the two bars and leave the keyboard covering the passphrase field, which
        // is the bug that motivated this change — the narrower token looks correct
        // and is not.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!topBar) return@Scaffold
            val barTitle: @Composable () -> Unit = {
                val style = if (largeTitle) LocalTextStyle.current
                    else titleStyle ?: MaterialTheme.typography.titleLarge
                Text(
                    text = title,
                    // No explicit style when large: Material draws `headlineMedium`
                    // expanded and `titleLarge` collapsed, both on the type scale.
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A count title shrinks before it truncates: "1 ausgewählt" in the mono
                    // face beside four actions is wider than the bar, and cut to
                    // "1 ausgewäh…" it no longer says what the number counts.
                    autoSize = if (titleStyle == null || largeTitle) null else TextAutoSize.StepBased(
                        minFontSize = 16.sp,
                        maxFontSize = style.fontSize,
                    ),
                    // Announced as a heading so a screen reader can jump
                    // between screens' structure rather than reading every
                    // control to find out where it is.
                    modifier = Modifier.semantics { heading() },
                )
            }
            val navigationIcon: @Composable () -> Unit = {
                if (onBack == null) {
                    leadingAction?.invoke()
                } else {
                    // Sized explicitly, and not because 48dp reads well.
                    // material3 1.4.0's IconButton defaults to **40dp**, which
                    // is under §7.1's floor — the instrumented Verify measured
                    // 40dp and failed, which is the only reason this is known.
                    // The glyph inside stays 24dp: §7.1 is "met by padding
                    // around the glyph, never by inflating the glyph".
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(Spacing.touchTarget),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                }
            }
            if (scrollBehavior != null) {
                LargeTopAppBar(
                    title = barTitle,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    collapsedHeight = Spacing.barHeight,
                    expandedHeight = Spacing.largeBarHeight,
                    colors = barColors,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = barTitle,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    // Named rather than left to the component default, so the height
                    // is a value `DESIGN.md` §7.1 records like every other one.
                    expandedHeight = Spacing.barHeight,
                    colors = barColors,
                )
            }
        },
    ) { innerPadding ->
        // The inset is applied once, here, and both layers inherit it — the overlay
        // needs it as much as the content does, so a floating control clears the
        // navigation bar without naming an inset modifier of its own.
        //
        // Applied once **and marked consumed**. A hosted screen that insets itself —
        // a cover in identity setup, which runs full-window everywhere else — then
        // reads zero for every side paid here, instead of paying the status bar and
        // the navigation bar a second time.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (scrollable) {
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.screenInset)
                        } else {
                            Modifier
                        }
                    ),
                verticalArrangement = verticalArrangement,
                horizontalAlignment = horizontalAlignment,
                content = content,
            )
            overlay()
        }
    }
}
