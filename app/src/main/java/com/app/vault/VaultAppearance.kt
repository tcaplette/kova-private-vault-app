package com.app.vault

/**
 * A vault's icon and colour — `vault-appearance`.
 *
 * **One byte in the keyring entry and nowhere else** ([VaultKeyring.Entry.appearance]):
 * bits 0–2 the colour's [FolderColour] ordinal, bits 3–7 the icon, 0 for the name's letter
 * and 1–24 for [ICONS] in order. The folders' colours and not a set of the vault's own, so
 * the app has one colour vocabulary.
 *
 * **The icon index is serialised**, so [ICONS] is appended to and never reordered — the rule
 * [FolderColour]'s ordinal already lives by.
 */
data class VaultAppearance(val colour: FolderColour, val icon: Int) {

    fun pack(): Int = (icon shl 3) or colour.ordinal

    /** The icon's drawable, or null for the letter. */
    val iconRes: Int? get() = ICONS.getOrNull(icon - 1)?.second

    companion object {

        /** The letter with no colour — what every entry below keyring version 7 means. */
        val LETTER = VaultAppearance(FolderColour.None, 0)

        /**
         * The 24 glyphs, as the name a screen reader reads and the drawable. Index + 1 is the
         * stored value. No padlock: it marks a vault with its own passphrase and cannot also
         * be decoration.
         */
        val ICONS: List<Pair<Int, Int>> = listOf(
            R.string.vault_icon_home to R.drawable.ic_vault_home,
            R.string.vault_icon_family to R.drawable.ic_vault_family_restroom,
            R.string.vault_icon_person to R.drawable.ic_vault_person,
            R.string.vault_icon_child to R.drawable.ic_vault_child_care,
            R.string.vault_icon_favourite to R.drawable.ic_vault_favorite,
            R.string.vault_icon_pets to R.drawable.ic_vault_pets,
            R.string.vault_icon_work to R.drawable.ic_vault_work,
            R.string.vault_icon_id to R.drawable.ic_vault_badge,
            R.string.vault_icon_bank to R.drawable.ic_vault_account_balance,
            R.string.vault_icon_savings to R.drawable.ic_vault_savings,
            R.string.vault_icon_receipts to R.drawable.ic_vault_receipt_long,
            R.string.vault_icon_documents to R.drawable.ic_vault_description,
            R.string.vault_icon_medical to R.drawable.ic_vault_medical_services,
            R.string.vault_icon_school to R.drawable.ic_vault_school,
            R.string.vault_icon_travel to R.drawable.ic_vault_flight,
            R.string.vault_icon_car to R.drawable.ic_vault_directions_car,
            R.string.vault_icon_camera to R.drawable.ic_vault_photo_camera,
            R.string.vault_icon_music to R.drawable.ic_vault_music_note,
            R.string.vault_icon_books to R.drawable.ic_vault_menu_book,
            R.string.vault_icon_celebration to R.drawable.ic_vault_celebration,
            R.string.vault_icon_star to R.drawable.ic_vault_star,
            R.string.vault_icon_diamond to R.drawable.ic_vault_diamond,
            R.string.vault_icon_shopping to R.drawable.ic_vault_shopping_bag,
            R.string.vault_icon_archive to R.drawable.ic_vault_inventory_2,
        )

        /**
         * The appearance a stored byte means. A colour or an icon this build does not define
         * reads as none or the letter **in that field alone** — decoration never refuses a keyring.
         */
        fun of(byte: Int): VaultAppearance {
            val colour = byte and 0x07
            val icon = (byte and 0xFF) ushr 3
            return VaultAppearance(
                if (colour < FolderColour.entries.size) FolderColour.entries[colour] else FolderColour.None,
                if (icon <= ICONS.size) icon else 0,
            )
        }

        /**
         * A new vault's appearance: the letter, and a colour rotated through the five by
         * [position], so adjacent vaults differ before anyone chooses — D2.
         */
        fun defaultFor(position: Int): VaultAppearance =
            VaultAppearance(FolderColour.entries[1 + position % (FolderColour.entries.size - 1)], 0)
    }
}
