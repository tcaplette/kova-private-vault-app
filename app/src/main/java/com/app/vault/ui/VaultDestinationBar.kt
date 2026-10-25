package com.app.vault.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.app.vault.VaultDestination
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The four destinations, at the bottom — `vault-destinations`.
 *
 * **Present in every state but two**: withheld while selecting and in the trash, for the
 * reason at the foot of this note. **With the keyboard open it stays**, at full height,
 * above the IME — it used to be squeezed to zero height there, and that is written up
 * where the cause was, on the bottom block in `VaultGrid`.
 *
 * ***This is primary navigation and it is deliberately not in the drawer.*** Material
 * names a drawer holding primary navigation as an anti-pattern, and the reason is
 * arithmetic: behind a hamburger every switch costs two taps and no destination is visible
 * until the user goes looking for one. `VaultDrawerSheet` records that it already failed
 * once by becoming the surface things landed on because it had room; putting the vault's
 * contents there would repeat exactly that. It holds the install. This holds the vault.
 *
 * **Mark *and* label on every entry.** An icon-only bar makes the user learn four glyphs
 * before they can find anything, and `screen-chrome` requires every control be named to
 * accessibility services regardless — a label is that name, said once, visibly.
 *
 * **The active entry is identifiable with colour disregarded.** Three things say it and
 * only one of them is colour: the indicator behind the mark, the label's weight, and the
 * `selected` state a screen reader announces. `ui-design-direction` forbids meaning carried
 * by colour alone, and a bar whose only signal is a tint fails that on its own terms.
 *
 * ***[VaultColors.Secondary] for the indicator and never [VaultColors.Primary].*** The
 * primary colour is the one primary action on the screen, which on this screen is the
 * floating button — `grid-fab` says so in as many words. Secondary is the structural
 * accent that "carries no meaning of its own", which is precisely what a selection
 * indicator is.
 *
 * **Where it sits, and what it takes.** Last in the grid's measured bottom column, below
 * the floating action button, above the system navigation — and it takes **no inset of its
 * own**; `VaultScaffold` has already paid it. See the `windowInsets` argument below. The
 * column's measured height is what the item list clears — no constant, which is the rule
 * `search-at-the-bottom` set. **It is the only member of that column the keyboard reaches**:
 * the search field is at the top of the screen's content, where the keyboard cannot cover it,
 * so the bar is what rides above the IME, on `VaultScaffold`'s insets alone.
 *
 * **Absent while selecting and in the trash.** The caller withholds it, under the same
 * condition that withholds the search field: a selection acts on the list beneath it, and
 * the trash holds every kind at once, so neither has a destination to be on.
 */
@Composable
fun VaultDestinationBar(
    current: VaultDestination,
    onChoose: (VaultDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        containerColor = VaultColors.SurfaceContainer,
        contentColor = VaultColors.OnSurface,
        // ***Zero, and that is not an oversight.***
        //
        // `NavigationBar` consumes the navigation-bar inset by default, and here that
        // would be the **second** time it was paid: this bar sits in `VaultScaffold`'s
        // overlay slot, which is inside `Box(Modifier.padding(innerPadding))`, and that
        // padding comes from `contentWindowInsets = WindowInsets.safeDrawing`. The inset
        // is already gone by the time this composes. Left on the default it draws a band
        // of empty ground under the bar the height of the gesture bar.
        //
        // `ScreenChromeTest` says the same thing as a rule: insets are decided in
        // `VaultScaffold` and nowhere else. A bar that named its own would be a second
        // place deciding one thing — which is the failure that file exists to prevent.
        windowInsets = WindowInsets(0),
        modifier = modifier,
    ) {
        VaultDestination.entries.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onChoose(destination) },
                // `Spacing.touchTarget` written down rather than inherited. The bar's own
                // 80dp already clears it, but a component default is not a design
                // decision — `VaultListScreen`'s lock action was left on `IconButton`'s
                // 40dp for exactly that reason and a device test caught it.
                modifier = Modifier.defaultMinSize(minHeight = Spacing.touchTarget),
                icon = { DestinationMark(destination) },
                label = { DestinationLabel(destination, selected) },
                // Always. A bar that hid the labels of the three the user is not on would
                // be an icon-only bar three quarters of the time.
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = VaultColors.Secondary,
                    selectedIconColor = VaultColors.OnSecondary,
                    selectedTextColor = VaultColors.OnSurface,
                    unselectedIconColor = VaultColors.OnSurfaceVariant,
                    unselectedTextColor = VaultColors.OnSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * The same four destinations on the leading edge — `landscape-rotation`, `vault-destinations`.
 *
 * ***Drawn instead of [VaultDestinationBar] in a window under 480dp tall***, which is a phone held
 * sideways. There the bottom edge is the shortest one the screen has, and a bar across it spends that
 * edge on navigation while the tiles it navigates get a strip too short to scroll. The rail moves the
 * same four places onto the long edge — where Material's adaptive navigation puts a sideways phone's
 * destinations — and gives the height back to the content.
 *
 * **The same entries, said the same way.** Order, mark, one-word label, the [VaultColors.Secondary]
 * indicator, the label's weight and the `selected` state all come from the pieces the bar uses, so
 * the two forms cannot drift into saying different things about where the user is.
 *
 * **Centred on the edge**, not packed at the top: held sideways, the leading thumb rests at the
 * middle of that edge, and four entries at the top of it would be the far end of the reach.
 *
 * **`windowInsets = WindowInsets(0)`, for the bar's reason.** The rail sits inside `VaultScaffold`'s
 * content, which is already padded by `safeDrawing` — a side navigation bar or a cutout is paid there.
 * The component's default would pay it a second time and open a band of empty ground beside the rail.
 */
@Composable
fun VaultDestinationRail(
    current: VaultDestination,
    onChoose: (VaultDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        containerColor = VaultColors.SurfaceContainer,
        contentColor = VaultColors.OnSurface,
        windowInsets = WindowInsets(0),
        modifier = modifier,
    ) {
        Spacer(Modifier.weight(1f))
        VaultDestination.entries.forEach { destination ->
            val selected = destination == current
            NavigationRailItem(
                selected = selected,
                onClick = { onChoose(destination) },
                modifier = Modifier.defaultMinSize(minHeight = Spacing.touchTarget),
                icon = { DestinationMark(destination) },
                label = { DestinationLabel(destination, selected) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = VaultColors.Secondary,
                    selectedIconColor = VaultColors.OnSecondary,
                    selectedTextColor = VaultColors.OnSurface,
                    unselectedIconColor = VaultColors.OnSurfaceVariant,
                    unselectedTextColor = VaultColors.OnSurfaceVariant,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** A destination's mark, for the bar and the rail alike. */
@Composable
private fun DestinationMark(destination: VaultDestination) {
    Icon(
        painter = painterResource(destination.icon),
        // Null: the label below says the same word, and a screen reader
        // announcing both would say it twice. `selected` carries the
        // state — see `SearchField`'s leading glyph for the same call.
        contentDescription = null,
    )
}

/** A destination's one-word label, for the bar and the rail alike. */
@Composable
private fun DestinationLabel(destination: VaultDestination, selected: Boolean) {
    Text(
        text = stringResource(destination.label),
        style = MaterialTheme.typography.labelMedium,
        // The second of the three signals. A weight change survives a
        // greyscale screenshot; a tint does not.
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        // One word each (`VaultDestinationTest.everyLabelIsOneWord`), so
        // this is a floor under the largest text scale rather than an
        // expected outcome: four labels share a bar measured at 320dp.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
