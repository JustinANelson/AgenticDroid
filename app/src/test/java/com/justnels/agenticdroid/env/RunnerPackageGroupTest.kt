package com.justnels.agenticdroid.env

import com.justnels.agenticdroid.workspace.ProjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerPackageGroupTest {

    @Test
    fun `core includes the complete declared openssh runtime closure`() {
        assertTrue(
            RunnerPackageGroup.CORE.termuxPackages.containsAll(
                setOf(
                    "openssh",
                    "krb5",
                    "ldns",
                    "libandroid-support",
                    "libedit",
                    "openssh-sftp-server",
                    "openssl",
                    "termux-auth",
                    "libresolv-wrapper",
                    "libdb",
                    "zlib"
                )
            )
        )
    }

    @Test
    fun corePackagesAreNeverRemovable() {
        // CORE isn't itself in installedGroups (it's implicit), but every package it
        // lists must survive removing any other single group.
        val stillNeeded = RunnerPackageGroup.packagesNeededAfterRemoving(
            RunnerPackageGroup.PYTHON,
            installedGroups = setOf(RunnerPackageGroup.PYTHON)
        )
        assertTrue(RunnerPackageGroup.CORE.termuxPackages.all { it in stillNeeded })
    }

    @Test
    fun sharedPackageSurvivesWhileAnotherGroupStillNeedsIt() {
        // RUST and CPP both list clang/binutils - removing RUST while CPP is still
        // installed must not mark them removable.
        val stillNeeded = RunnerPackageGroup.packagesNeededAfterRemoving(
            RunnerPackageGroup.RUST,
            installedGroups = setOf(RunnerPackageGroup.RUST, RunnerPackageGroup.CPP)
        )
        assertTrue("clang" in stillNeeded)
        assertTrue("binutils" in stillNeeded)
    }

    @Test
    fun sharedPackageIsRemovableOnceNoGroupNeedsItAnymore() {
        val stillNeeded = RunnerPackageGroup.packagesNeededAfterRemoving(
            RunnerPackageGroup.RUST,
            installedGroups = setOf(RunnerPackageGroup.RUST)
        )
        assertFalse("clang" in stillNeeded)
        assertFalse("binutils" in stillNeeded)
        // rust itself is exclusive to the RUST group and must be removable too.
        assertFalse("rust" in stillNeeded)
    }

    @Test
    fun overlapWithCoreSurvivesEvenAloneInstalled() {
        // ncurses/readline are listed by both CORE (for the sqlite3 CLI) and PYTHON -
        // uninstalling PYTHON alone must never remove them.
        val stillNeeded = RunnerPackageGroup.packagesNeededAfterRemoving(
            RunnerPackageGroup.PYTHON,
            installedGroups = setOf(RunnerPackageGroup.PYTHON)
        )
        assertTrue("ncurses" in stillNeeded)
        assertTrue("readline" in stillNeeded)
        // python itself is exclusive to PYTHON and must be removable.
        assertFalse("python" in stillNeeded)
    }

    @Test
    fun everyNonCoreProjectTypeRequiresExactlyOneOptionalGroupPlusCore() {
        for (type in ProjectType.entries) {
            val required = RunnerPackageGroup.requiredFor(type)
            assertTrue("CORE must always be required for $type", RunnerPackageGroup.CORE in required)
        }
    }
}
