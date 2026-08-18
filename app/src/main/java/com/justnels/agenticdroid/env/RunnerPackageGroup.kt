package com.justnels.agenticdroid.env

import com.justnels.agenticdroid.workspace.ProjectType

/**
 * A separately installable slice of the on-device toolchain. [CORE] is implicitly part of
 * every bootstrap (it's what agent CLIs and the terminal itself need: node, git, npm, curl,
 * gh, and the QEMU-user/musl/glibc sysroots - see NodeBootstrapper). The rest are opt-in
 * per-language runners, so a user working on one project type isn't forced to download
 * toolchains (Rust, Go, JVM, ...) they will never touch.
 */
enum class RunnerPackageGroup(
    val displayName: String,
    val description: String,
    val termuxPackages: List<String>
) {
    CORE(
        "Core Toolchain",
        "Node.js, npm, git, curl, gh, ripgrep/jq/fd, and the QEMU-user runtime AI agent CLIs need",
        listOf(
            "nodejs", "libc++", "openssl", "c-ares", "libicu", "libsqlite", "zlib", "libffi",
            "git", "libcurl", "libexpat", "libiconv", "pcre2", "less",
            "libnghttp2", "libnghttp3", "libngtcp2", "libssh2",
            "curl", "gh", "npm",
            // qemu-user-<arch> dependency closure, plus libzstd (libdw needs it but
            // doesn't declare it) - qemu-user-<arch> itself is added per-arch in
            // NodeBootstrapper since its package name depends on the device ABI.
            "glib", "libandroid-shmem", "libdw", "libgnutls", "libpixman", "libandroid-support",
            "argp", "libbz2", "liblzma", "libelf", "libgmp", "libnettle", "ca-certificates",
            "libidn2", "libtasn1", "libunbound", "libunistring", "p11-kit", "zstd",
            // used at agent-install time to unpack Antigravity CLI's release archive.
            "tar", "libacl", "libandroid-glob", "libandroid-selinux",
            // Everyday CLI accessories agent tool-calls commonly assume exist regardless
            // of project type - most notably ripgrep, which Claude Code/Codex/Gemini CLI
            // all shell out to for fast codebase search; without it a search tool call
            // either fails or silently falls back to a much slower plain grep.
            "ripgrep", "fd", "jq", "oniguruma", "tree", "unzip", "patch", "diffutils",
            // sqlite3 CLI binary - libsqlite above is only the shared library it (and
            // node's built-in sqlite module) link against. Verified against Termux's live
            // package index (Depends: readline, zlib; readline itself Depends:
            // libandroid-support, ncurses) - libandroid-support/zlib are already listed
            // above for other reasons, readline/ncurses are added here for this.
            "sqlite", "readline", "ncurses"
        )
    ),
    PYTHON(
        "Python",
        "Python 3 interpreter and pip",
        listOf(
            "gdbm", "libandroid-posix-semaphore", "libcrypt", "ncurses", "ncurses-ui-libs",
            "readline", "python", "python-pip", "python-ensurepip-wheels"
        )
    ),
    JVM(
        "Java / Kotlin",
        "OpenJDK 17, aapt2, and the Kotlin compiler - needed for Android and JVM builds",
        listOf("openjdk-17", "aapt2", "kotlin")
    ),
    RUST(
        "Rust",
        "Rust compiler and Cargo",
        // rustc needs a real linker to produce a binary (cargo build/run fails with
        // "linker `cc` not found" otherwise) - clang provides it, and binutils backs it.
        listOf("rust", "clang", "binutils")
    ),
    GOLANG("Go", "Go compiler toolchain", listOf("golang")),
    CPP(
        "C / C++",
        "Clang, make, binutils, and pkg-config",
        listOf("clang", "make", "binutils", "pkg-config")
    ),
    SSG("Static Site Generators", "Hugo", listOf("hugo"));

    companion object {
        /** The set of groups a project of the given type needs, [CORE] always included. */
        fun requiredFor(type: ProjectType): Set<RunnerPackageGroup> = when (type) {
            ProjectType.ANDROID -> setOf(CORE, JVM)
            ProjectType.WEB, ProjectType.NODE_JS -> setOf(CORE)
            ProjectType.PYTHON -> setOf(CORE, PYTHON)
            ProjectType.JVM -> setOf(CORE, JVM)
            ProjectType.RUST -> setOf(CORE, RUST)
            ProjectType.GOLANG -> setOf(CORE, GOLANG)
            ProjectType.SSG -> setOf(CORE, SSG)
            ProjectType.CPP -> setOf(CORE, CPP)
            ProjectType.CUSTOM -> setOf(CORE)
        }

        /** Groups a user can opt into installing beyond the always-present [CORE]. */
        val optional: List<RunnerPackageGroup> = entries.filter { it != CORE }

        /**
         * Termux package names still required if [group] were removed from
         * [installedGroups] - [CORE] always counts as installed, so anything it lists
         * (or that another remaining group still lists, e.g. RUST and CPP both list
         * clang/binutils) is never considered safe to delete for this group alone.
         */
        fun packagesNeededAfterRemoving(group: RunnerPackageGroup, installedGroups: Set<RunnerPackageGroup>): Set<String> =
            (installedGroups - group + CORE).flatMap { it.termuxPackages }.toSet()
    }
}
