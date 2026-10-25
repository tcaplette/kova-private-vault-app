package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher identity enum — P2-22.
 *
 * JVM scope: the enum and [LauncherIdentity.of] are the parts that decide which
 * manifest component is enabled, and they are decidable without a device. Whether
 * exactly one component is actually enabled is `LauncherIdentityDeviceTest`, because
 * component state is a platform fact no source can be read for.
 */
class LauncherIdentityTest {

    @Test
    fun theDefaultIsTheVaultsOwnIdentity() {
        assertSame(LauncherIdentity.Vault, LauncherIdentity.Default)
        assertSame(LauncherIdentity.Vault, LauncherIdentity.of(null))
    }

    /**
     * An unrecognised name reads as the default rather than throwing — `VaultSort`'s
     * rule and its reason: a preference written by a later build must not stop this
     * one presenting a launcher entry. It matters more here, because failing would
     * mean an app with no icon at all.
     */
    @Test
    fun anUnknownNameReadsAsTheDefault() {
        // "Calculator" was an unknown name until P2-24 made it an identity. Replaced
        // rather than dropped: the case being tested is a *stored* value this build
        // does not know, and it needs a name that is still unknown to stay that case.
        listOf(null, "", " ", "vault", "VAULT", "Weather", "Files ")
            .forEach { assertSame("read $it", LauncherIdentity.Default, LauncherIdentity.of(it)) }
    }

    @Test
    fun everyNameRoundTrips() {
        LauncherIdentity.entries.forEach {
            assertSame(it, LauncherIdentity.of(it.name))
        }
    }

    /**
     * **Stored by name, never by ordinal.** An ordinal makes the stored value depend
     * on declaration order, so inserting an identity in the middle later would
     * silently re-point every device's saved choice — and here that means the wrong
     * icon on somebody's home screen. This fails against any implementation that
     * parses a number.
     */
    @Test
    fun aStoredOrdinalIsNotAnIdentity() {
        LauncherIdentity.entries.forEach {
            assertSame(
                "ordinal ${it.ordinal} resolved to an identity",
                LauncherIdentity.Default,
                LauncherIdentity.of(it.ordinal.toString()),
            )
        }
    }

    /**
     * Counted from `entries` rather than against a literal — P2-24 took this from
     * three identities to six, and a hardcoded number is a line that has to be edited
     * every time an identity is added, for no assertion it was not already making.
     */
    @Test
    fun everyIdentityIsDistinctInEveryFieldThatIsUsed() {
        val all = LauncherIdentity.entries
        val n = all.size
        assertEquals("two identities share an alias", n, all.map { it.aliasSuffix }.toSet().size)
        assertEquals("two identities share a label", n, all.map { TestWords.text(it.label) }.toSet().size)
        assertEquals("two identities share an icon", n, all.map { it.iconRes }.toSet().size)
    }

    /**
     * P2-24. A cover identity opens a front end; the rest open the vault directly.
     * Every cover must be reachable by exactly one identity, or a configured trigger
     * would have no icon to sit behind.
     */
    @Test
    fun everyCoverHasExactlyOneIdentity() {
        val covers = LauncherIdentity.entries.mapNotNull { it.cover }
        assertEquals("a cover is claimed by two identities", covers.size, covers.toSet().size)
        assertEquals("a cover has no identity", Cover.entries.toSet(), covers.toSet())
    }

    /**
     * The manifest ships exactly one alias enabled, and it is the default. This is
     * what `LauncherIdentity.reconcile` compares an unswitched component against, so
     * a fresh install converges with no component write at all — the property
     * `LauncherIdentityDeviceTest` then asserts on a device.
     */
    @Test
    fun exactlyOneIdentityIsEnabledInTheManifest() {
        val enabled = LauncherIdentity.entries.filter { it.enabledInManifest }
        assertEquals(listOf(LauncherIdentity.Default), enabled)
    }

    @Test
    fun everyAliasSuffixIsPackageRelativeAndUnderTheAliasNamespace() {
        LauncherIdentity.entries.forEach {
            assertTrue("${it.name} suffix is not relative", it.aliasSuffix.startsWith(".alias."))
            assertNotEquals(".alias.", it.aliasSuffix)
        }
    }
}
