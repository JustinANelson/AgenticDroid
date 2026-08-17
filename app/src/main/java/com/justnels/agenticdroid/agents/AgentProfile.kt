package com.justnels.agenticdroid.agents

/**
 * Data model for AI agent profiles.
 */
data class AgentProfile(
    val id: String,
    val name: String,
    val command: String,
    val installCommand: String,
    val prepareCommand: String? = null,
    val iconResId: Int? = null,
    val defaultArgs: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
    val isInstalled: Boolean = false
) {
    /**
     * Starts an existing agent immediately, or installs it first when it is absent.
     * The profiles below are application-owned constants, so these shell fragments
     * never contain user-provided input.
     *
     * This is sent as input to one persistent interactive shell that lives for as long
     * as the Terminal screen does (see TerminalViewModel) - it isn't a fresh one-shot
     * process per launch. Wrapped in a subshell so the `exit`/`exec` calls inside it (on
     * install failure, or to hand off the controlling terminal to the agent) only affect
     * that subshell, not the outer interactive session: without this, one failed install
     * or one agent exiting would kill the whole session, leaving nothing to send the
     * next command to.
     */
    fun launchCommand(): String {
        val invocation = (listOf(command) + defaultArgs).joinToString(" ")
        val startAgent = prepareCommand?.let { "$it && exec $invocation" } ?: "exec $invocation"
        return """
        (
        if command -v $command >/dev/null 2>&1; then
          $startAgent
        else
          echo "Installing $name..."
          $installCommand
          hash -r 2>/dev/null || true
          if command -v $command >/dev/null 2>&1; then
            $startAgent
          else
            echo "$name installation failed: '$command' was not found in PATH." >&2
            exit 127
          fi
        fi
        )
        """.trimIndent()
    }
}

object DefaultAgents {
    /**
     * Builds an install command for an npm-distributed CLI whose actual work is done by
     * a musl-linked native binary shipped as a separate "<package>-linux-<arch>-musl"
     * optional dependency. Android can't exec() that binary directly (its ELF
     * interpreter is /lib/ld-musl-*.so.1, which doesn't exist on Android), so this fetches
     * it explicitly (bypassing npm's os/libc gate, since it reports "android" and the
     * package only declares "linux") and generates a wrapper that runs it under QEMU
     * user-mode emulation instead of invoking musl's loader directly.
     *
     * Direct musl-loader invocation is blocked: Android's Zygote installs a seccomp-bpf
     * filter on every real app-spawned process that kills a syscall musl's dynamic
     * loader needs during relocation (adb shell's `run-as` bypasses Zygote entirely,
     * which is why testing through it looked like this worked - it doesn't, for an
     * actual app child). QEMU-user does its own ELF loading rather than ptrace-ing a
     * natively-relocating loader, so it never trips that filter - confirmed empirically
     * end-to-end through a real app-spawned child (Launch button), not just `run-as`.
     * The wrapper below test-runs itself once and reports plainly if the binary still
     * doesn't come up, since a bad QEMU_SYSROOT/library mismatch fails opaquely
     * otherwise. Once a CLI publishes a real "<package>-linux-<arch>-android" build
     * (Bionic-native, no loader, no QEMU needed), this install flow just needs the
     * package-name suffix swapped.
     */
    private fun npmMuslAgentInstallCommand(npmPackage: String, binaryName: String): String = """
        node "${'$'}NPM_CLI" install -g --ignore-scripts $npmPackage >/dev/null 2>&1
        arch="${'$'}(uname -m)"
        case "${'$'}arch" in
          aarch64) natpkg="$npmPackage-linux-arm64-musl" ;;
          x86_64) natpkg="$npmPackage-linux-x64-musl" ;;
          *) echo "Unsupported architecture: ${'$'}arch" >&2; exit 1 ;;
        esac
        node "${'$'}NPM_CLI" install -g --force "${'$'}natpkg" >/dev/null 2>&1
        natdir="${'$'}NPM_CONFIG_PREFIX/lib/node_modules/${'$'}natpkg"
        globalbin="${'$'}NPM_CONFIG_PREFIX/bin"
        mkdir -p "${'$'}globalbin"
        rm -f "${'$'}globalbin/$binaryName"
        printf '#!/system/bin/sh\nexport LD_LIBRARY_PATH="%s/lib"\nexec "%s" -L "%s" "%s/$binaryName" "${'$'}@"\n' \
          "${'$'}QEMU_SYSROOT" "${'$'}QEMU_BIN" "${'$'}QEMU_SYSROOT" "${'$'}natdir" > "${'$'}globalbin/$binaryName"
        chmod 755 "${'$'}globalbin/$binaryName"
        if ! "${'$'}globalbin/$binaryName" --version </dev/null >/dev/null 2>&1; then
          rm -f "${'$'}globalbin/$binaryName"
          echo "$binaryName's native binary failed to run under QEMU-user" \
               "(check QEMU_BIN/QEMU_SYSROOT setup - see NodeRuntime/NodeBootstrapper)." >&2
          echo "Use an SSH environment to run $binaryName on a real machine in the" \
               "meantime." >&2
        fi
    """.trimIndent()

    /**
     * Codex's npm package (@openai/codex) is just a JS shim (bin/codex.js) that resolves
     * and spawns a native binary from a separate optional dependency package by exact
     * path - "@openai/codex-linux-x64" etc, actually published as version-tagged aliases
     * of the same @openai/codex package ("npm:@openai/codex@<version>-linux-x64"), read
     * here from the already-installed shim's own package.json rather than hardcoded
     * since the version changes on every release. That native binary is confirmed
     * musl-linked (target triple x86_64-unknown-linux-musl / aarch64-unknown-linux-musl)
     * despite the "linux-x64" (no musl suffix) package name, and confirmed
     * statically-linked (static-pie, no ELF interpreter at all) - which does NOT dodge
     * Android's Zygote seccomp filter: unlike the direct-musl-loader case, a static
     * binary invoked directly runs fine under `run-as` but a real Zygote child still
     * gets killed with SIGSYS ("Bad system call"), confirmed empirically. So it needs
     * the same QEMU-user wrapping as Claude, just applied by swapping the file in place
     * at its resolved vendor path (codex.js spawns that exact path, not a PATH lookup)
     * rather than generating a new PATH-level command.
     *
     * npm's own bin-link for the "codex" command can't be trusted, either: the JS shim
     * (bin/codex.js) starts with `#!/usr/bin/env node`, and Android has no /usr/bin/env
     * - so this regenerates globalbin/codex as a plain `#!/system/bin/sh` wrapper that
     * execs node directly, matching how NodeBootstrapper already handles npm/npx. That
     * globalbin/codex path is npm's own bin-link for the package - a symlink to
     * codex.js - so writing to it with a bare `>` follows the symlink and clobbers
     * codex.js itself rather than replacing the link (confirmed the hard way: codex.js
     * ended up containing this script's own wrapper text). Removing it first avoids
     * that.
     */
    private fun codexInstallCommand(): String = """
        node "${'$'}NPM_CLI" install -g --ignore-scripts @openai/codex >/dev/null 2>&1
        codexjs="${'$'}NPM_CONFIG_PREFIX/lib/node_modules/@openai/codex/bin/codex.js"
        globalbin="${'$'}NPM_CONFIG_PREFIX/bin"
        mkdir -p "${'$'}globalbin"
        rm -f "${'$'}globalbin/codex"
        printf '#!/system/bin/sh\nexec node "%s" "${'$'}@"\n' "${'$'}codexjs" > "${'$'}globalbin/codex"
        chmod 755 "${'$'}globalbin/codex"
        arch="${'$'}(uname -m)"
        case "${'$'}arch" in
          aarch64) codexarch="arm64"; triple="aarch64-unknown-linux-musl" ;;
          x86_64) codexarch="x64"; triple="x86_64-unknown-linux-musl" ;;
          *) echo "Unsupported architecture: ${'$'}arch" >&2; exit 1 ;;
        esac
        platkey="@openai/codex-linux-${'$'}codexarch"
        codexpkgjson="${'$'}NPM_CONFIG_PREFIX/lib/node_modules/@openai/codex/package.json"
        spec="${'$'}(node -e 'process.stdout.write(require(process.argv[1]).optionalDependencies[process.argv[2]] || "")' "${'$'}codexpkgjson" "${'$'}platkey")"
        if [ -z "${'$'}spec" ]; then
          echo "Could not resolve ${'$'}platkey from @openai/codex's optionalDependencies" >&2
          exit 1
        fi
        node "${'$'}NPM_CLI" install -g --force "${'$'}platkey@${'$'}spec" >/dev/null 2>&1
        natdir="${'$'}NPM_CONFIG_PREFIX/lib/node_modules/${'$'}platkey/vendor/${'$'}triple/bin"
        for f in codex codex-code-mode-host; do
          if [ -f "${'$'}natdir/${'$'}f" ] && [ ! -f "${'$'}natdir/${'$'}f.real" ]; then
            mv "${'$'}natdir/${'$'}f" "${'$'}natdir/${'$'}f.real"
            printf '#!/system/bin/sh\nexport LD_LIBRARY_PATH="%s/lib"\nexec "%s" -L "%s" "%s/'"${'$'}f"'.real" "${'$'}@"\n' \
              "${'$'}QEMU_SYSROOT" "${'$'}QEMU_BIN" "${'$'}QEMU_SYSROOT" "${'$'}natdir" > "${'$'}natdir/${'$'}f"
            chmod 755 "${'$'}natdir/${'$'}f"
          fi
        done
        if ! codex --version </dev/null >/dev/null 2>&1; then
          echo "Codex's native binary failed to run under QEMU-user" \
               "(check QEMU_BIN/QEMU_SYSROOT setup - see NodeRuntime/NodeBootstrapper)." >&2
        fi
    """.trimIndent()

    val Codex = AgentProfile(
        id = "codex",
        name = "OpenAI Codex",
        command = "codex",
        installCommand = codexInstallCommand(),
        prepareCommand = "(codex login status >/dev/null 2>&1 || codex login --device-auth)",
        defaultArgs = listOf("--no-alt-screen")
    )

    val Claude = AgentProfile(
        id = "claude",
        name = "Claude Code",
        command = "claude",
        installCommand = npmMuslAgentInstallCommand("@anthropic-ai/claude-code", "claude"),
        defaultArgs = listOf("--ax-screen-reader")
    )

    /**
     * Antigravity CLI isn't distributed via npm at all - it's a single compiled Go
     * binary Google publishes behind a manifest API and fetches with a curl|bash
     * installer (antigravity.google/cli/install.sh). This reimplements just enough of
     * that installer for our sandbox: resolve the per-arch manifest, download the
     * tar.gz release, and run the extracted binary under QEMU-user rather than trying
     * to exec it directly.
     *
     * Confirmed (by downloading and inspecting both linux-x64 and linux-arm64 release
     * binaries) that this is a dynamically-linked *glibc* binary (interpreter
     * /lib64/ld-linux-x86-64.so.2 or /lib/ld-linux-aarch64.so.1) - the installer script
     * has dead code implying a musl build might exist, but its manifest endpoint 404s
     * for every platform tried, so it apparently isn't actually published. That means
     * this is the one agent needing GLIBC_SYSROOT (Debian's libc6 + libgcc-s1) rather
     * than musl - QEMU-user's -L sysroot mechanism doesn't care which libc a guest
     * binary was built against, so the same wrapping approach as Claude/Codex applies,
     * just pointed at a different sysroot.
     *
     * The release is a gzip'd tar; decompression goes through node's own zlib (writing
     * a plain .tar) rather than `tar -z`, since that would fork an external `gzip`
     * binary this environment doesn't bundle.
     *
     * The self-test below redirects stdin from /dev/null - confirmed the hard way that
     * without it, `agy --version` blocks forever reading from the terminal session's
     * live PTY (which never provides input or EOF) instead of exiting immediately like
     * a plain version check should, hanging the whole install step. Same fix applied to
     * Claude/Codex's self-tests as a precaution, though they didn't hit it in testing.
     */
    private fun antigravityInstallCommand(): String = """
        arch="${'$'}(uname -m)"
        case "${'$'}arch" in
          aarch64) agyarch="arm64" ;;
          x86_64) agyarch="amd64" ;;
          *) echo "Unsupported architecture: ${'$'}arch" >&2; exit 1 ;;
        esac
        platform="linux_${'$'}agyarch"
        vendordir="${'$'}NPM_CONFIG_PREFIX/vendor/antigravity"
        mkdir -p "${'$'}vendordir"
        cat > "${'$'}vendordir/fetch.js" <<'JSEOF'
        const https = require("https");
        const fs = require("fs");
        const zlib = require("zlib");
        const crypto = require("crypto");
        function get(url, cb, redirects = 0) {
          const parsed = new URL(url);
          if (parsed.protocol !== "https:" || redirects > 5) {
            process.stderr.write("Refusing unsafe download URL\n");
            process.exit(1);
          }
          https.get(url, res => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
              get(new URL(res.headers.location, parsed).toString(), cb, redirects + 1);
              return;
            }
            if (res.statusCode !== 200) {
              process.stderr.write("HTTP " + res.statusCode + " fetching " + url + "\n");
              process.exit(1);
            }
            cb(res);
          }).on("error", e => { process.stderr.write(String(e) + "\n"); process.exit(1); });
        }
        const platform = process.argv[2];
        const outTar = process.argv[3];
        get("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/" + platform + ".json", res => {
          let data = "";
          res.on("data", c => data += c);
          res.on("end", () => {
            const manifest = JSON.parse(data);
            if (!/^[a-f0-9]{128}$/i.test(manifest.sha512 || "")) {
              process.stderr.write("Manifest did not provide a valid SHA-512 digest\n");
              process.exit(1);
            }
            get(manifest.url, res2 => {
              const compressed = outTar + ".gz";
              const file = fs.createWriteStream(compressed);
              res2.pipe(file);
              file.on("finish", () => {
                file.close(() => {
                  const hash = crypto.createHash("sha512");
                  const input = fs.createReadStream(compressed);
                  input.on("data", chunk => hash.update(chunk));
                  input.on("end", () => {
                    const actual = hash.digest("hex");
                    if (actual.toLowerCase() !== manifest.sha512.toLowerCase()) {
                      fs.rmSync(compressed, { force: true });
                      process.stderr.write("Antigravity archive checksum mismatch\n");
                      process.exit(1);
                    }
                    const output = fs.createWriteStream(outTar);
                    fs.createReadStream(compressed).pipe(zlib.createGunzip()).pipe(output);
                    output.on("finish", () => fs.rmSync(compressed, { force: true }));
                    output.on("error", e => { process.stderr.write(String(e) + "\n"); process.exit(1); });
                  });
                });
              });
              file.on("error", e => { process.stderr.write(String(e) + "\n"); process.exit(1); });
            });
          });
        });
        JSEOF
        node "${'$'}vendordir/fetch.js" "${'$'}platform" "${'$'}vendordir/agy.tar"
        tar -xf "${'$'}vendordir/agy.tar" -C "${'$'}vendordir" antigravity
        rm -f "${'$'}vendordir/agy.tar"
        if [ ! -f "${'$'}vendordir/antigravity" ]; then
          echo "Failed to download Antigravity CLI's native binary." >&2
          exit 1
        fi
        mv "${'$'}vendordir/antigravity" "${'$'}vendordir/antigravity.real"
        chmod 755 "${'$'}vendordir/antigravity.real"
        globalbin="${'$'}NPM_CONFIG_PREFIX/bin"
        mkdir -p "${'$'}globalbin"
        rm -f "${'$'}globalbin/agy"
        printf '#!/system/bin/sh\nexport LD_LIBRARY_PATH="%s/lib"\nexec "%s" -L "%s" "%s/antigravity.real" "${'$'}@"\n' \
          "${'$'}QEMU_SYSROOT" "${'$'}QEMU_BIN" "${'$'}GLIBC_SYSROOT" "${'$'}vendordir" > "${'$'}globalbin/agy"
        chmod 755 "${'$'}globalbin/agy"
        if ! "${'$'}globalbin/agy" --version </dev/null >/dev/null 2>&1; then
          rm -f "${'$'}globalbin/agy"
          echo "Antigravity's native binary failed to run under QEMU-user" \
               "(check QEMU_BIN/GLIBC_SYSROOT setup - see NodeRuntime/NodeBootstrapper)." >&2
        fi
    """.trimIndent()

    val Antigravity = AgentProfile(
        id = "agy",
        name = "Antigravity",
        command = "agy",
        installCommand = antigravityInstallCommand(),
        defaultArgs = listOf("--sandbox=false")
    )

    val All = listOf(Codex, Claude, Antigravity)
}
