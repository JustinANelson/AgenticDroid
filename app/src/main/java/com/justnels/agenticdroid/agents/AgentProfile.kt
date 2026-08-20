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
    // Set for npm-distributed agents (Codex/Claude/Gemini) so an update check can ask
    // the registry for the latest published version without installing anything.
    // Antigravity is intentionally left null: it isn't npm-distributed, and its manifest
    // endpoint only ever serves "whatever is current" with no distinct version to query
    // ahead of a reinstall - see DefaultAgents.antigravityInstallCommand.
    val npmPackageForVersionCheck: String? = null,
    // The flag(s) that switch this CLI from its interactive TUI into a single-shot,
    // non-interactive "run this prompt and exit" mode (e.g. listOf("-p") for Claude,
    // listOf("exec") for Codex) - null for agents with no such mode (Aider, Antigravity),
    // which [HeadlessAgentRunService] uses to decide whether "Run" is offered at all.
    val headlessPromptArgs: List<String>? = null
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
        if command -v $command >/dev/null 2>&1 && $command --version </dev/null >/dev/null 2>&1; then
          $startAgent
        else
          echo "Installing or repairing $name..."
          $installCommand
          hash -r 2>/dev/null || true
          if command -v $command >/dev/null 2>&1 && $command --version </dev/null >/dev/null 2>&1; then
            $startAgent
          else
            echo "$name installation or repair failed: '$command' could not run successfully." >&2
            exit 127
          fi
        fi
        )
        """.trimIndent()
    }

    /** The argv for an unattended, non-PTY invocation of this agent (no shell string, no
     * install-fallback logic - a headless run assumes the agent is already installed and
     * simply won't start otherwise), or null if [headlessPromptArgs] isn't set for this
     * agent. Deliberately excludes [defaultArgs]: those (`--ax-screen-reader`,
     * `--no-alt-screen`) tune the interactive TUI this mode never opens. Passed directly
     * to [com.justnels.agenticdroid.env.ExecutionEnvironment.exec]'s argv overload, which
     * shell-quotes each element itself. */
    fun headlessArgv(prompt: String): List<String>? =
        headlessPromptArgs?.let { listOf(command) + it + listOf(prompt) }

    /** stdin is redirected from /dev/null since a bare `--version` on some of these
     * (confirmed: agy) blocks forever reading from a live PTY instead of exiting - see
     * DefaultAgents.antigravityInstallCommand. */
    fun installedVersionCommand(): String = "$command --version </dev/null 2>&1"

    /** Queries npm's registry for the latest published version, without installing
     * anything - null for agents [npmPackageForVersionCheck] doesn't apply to. */
    fun latestVersionCommand(): String? =
        npmPackageForVersionCheck?.let { pkg -> """node "${'$'}NPM_CLI" view $pkg version 2>&1""" }

    /**
     * Forces a fresh install even if [command] is already on PATH - unlike
     * [launchCommand], which only installs when the command is missing entirely and so
     * never re-invokes an update once installed. Each installCommand's own
     * `npm install -g <pkg>` (no version pin) or (for Antigravity) direct
     * fetch-current-manifest already resolves whatever is newest, so simply re-running
     * it is sufficient - no separate "downgrade to an older pin" is supported, since the
     * install scripts have no such concept for 3 of the 4 agents today.
     */
    fun updateCommand(): String = installCommand
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
        if [ ! -d /system/bin ]; then
          echo "Detecting non-Android environment, performing standard install..."
          npm install -g $npmPackage
          exit $?
        fi
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
        if [ ! -d /system/bin ]; then
          echo "Detecting non-Android environment, performing standard install..."
          npm install -g @openai/codex
          exit $?
        fi
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
        defaultArgs = listOf("--no-alt-screen"),
        npmPackageForVersionCheck = "@openai/codex",
        headlessPromptArgs = listOf("exec")
    )

    val Claude = AgentProfile(
        id = "claude",
        name = "Claude Code",
        command = "claude",
        installCommand = npmMuslAgentInstallCommand("@anthropic-ai/claude-code", "claude"),
        defaultArgs = listOf("--ax-screen-reader"),
        npmPackageForVersionCheck = "@anthropic-ai/claude-code",
        headlessPromptArgs = listOf("-p")
    )

    /**
     * Unlike Claude/Codex/Antigravity, Gemini CLI (@google/gemini-cli) is pure JS/TS end to
     * end - there's no separate native binary to smuggle past Android's exec restrictions,
     * so no QEMU wrapping is needed at all. Its own optional native addons (node-pty for a
     * real PTY when running its own internal shell tool, keytar for OS-keychain credential
     * storage) publish no linux-arm64 build in the first place (confirmed: their
     * package.json optionalDependencies only list darwin/win32/linux-x64 variants), so npm
     * simply can't install them here regardless of Android - gemini-cli already has to
     * handle that build failing/missing gracefully on any unsupported arch, which is
     * exactly what optionalDependencies is for.
     *
     * One Android-specific landmine, confirmed empirically: a nested dependency
     * (clipboardy) throws unconditionally at module-load time - not lazily, so it breaks
     * before gemini even parses argv - when process.platform === "android" (which Node
     * reports for any Bionic build, including this bundled one) unless $TERMUX_VERSION is
     * set; it doesn't actually check Termux is present, just that one env var. Set
     * generically in NodeRuntime.configureEnvironment rather than just here, since any
     * future pure-JS agent could hit the same check.
     *
     * Like Codex, npm's own bin-link (global/bin/gemini) is a symlink to a script starting
     * with `#!/usr/bin/env node`, and Android has no /usr/bin/env - so this regenerates it
     * as a plain #!/system/bin/sh wrapper that execs node directly, matching how
     * NodeBootstrapper already handles npm/npx and how Codex's install command handles its
     * own JS entry point.
     *
     * That regeneration - and $NPM_CLI/$NPM_CONFIG_PREFIX, which only NodeRuntime's
     * on-device env setup defines - is Android-only, so (matching Codex/Claude/Antigravity)
     * this is skipped entirely on a real machine (e.g. reached over an SSH environment):
     * plain `npm install -g` already produces a working `gemini` on PATH there, since
     * there's no /usr/bin/env restriction or QEMU/musl indirection to work around.
     */
    private fun geminiInstallCommand(): String = """
        if [ ! -d /system/bin ]; then
          echo "Detecting non-Android environment, performing standard install..."
          npm install -g @google/gemini-cli
          exit $?
        fi
        node "${'$'}NPM_CLI" install -g --ignore-scripts @google/gemini-cli >/dev/null 2>&1
        geminijs="${'$'}NPM_CONFIG_PREFIX/lib/node_modules/@google/gemini-cli/bundle/gemini.js"
        globalbin="${'$'}NPM_CONFIG_PREFIX/bin"
        mkdir -p "${'$'}globalbin"
        rm -f "${'$'}globalbin/gemini"
        printf '#!/system/bin/sh\nexec node "%s" "${'$'}@"\n' "${'$'}geminijs" > "${'$'}globalbin/gemini"
        chmod 755 "${'$'}globalbin/gemini"
        if ! gemini --version </dev/null >/dev/null 2>&1; then
          rm -f "${'$'}globalbin/gemini"
          echo "Gemini CLI failed to start after install." >&2
        fi
    """.trimIndent()

    val Gemini = AgentProfile(
        id = "gemini",
        name = "Gemini CLI",
        command = "gemini",
        installCommand = geminiInstallCommand(),
        npmPackageForVersionCheck = "@google/gemini-cli",
        headlessPromptArgs = listOf("-p")
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
        if [ ! -d /system/bin ]; then
          echo "Detecting non-Android environment, performing standard install..."
          curl -sSL https://antigravity.google/cli/install.sh | bash
          exit $?
        fi
        arch="${'$'}(uname -m)"
        case "${'$'}arch" in
          aarch64) agyarch="arm64" ;;
          x86_64) agyarch="amd64" ;;
          *) echo "Unsupported architecture: ${'$'}arch" >&2; exit 1 ;;
        esac
        platform="linux_${'$'}agyarch"
        vendordir="${'$'}NPM_CONFIG_PREFIX/vendor/antigravity"
        rm -rf "${'$'}vendordir"
        mkdir -p "${'$'}vendordir"
        cat > "${'$'}vendordir/fetch.js" <<'JSEOF'
        const https = require("https");
        const fs = require("fs");
        const zlib = require("zlib");
        const crypto = require("crypto");
        const maxAttempts = 3;
        function get(url, cb, onError, redirects = 0) {
          const parsed = new URL(url);
          if (parsed.protocol !== "https:" || redirects > 5) {
            onError("Refusing unsafe download URL");
            return;
          }
          https.get(url, res => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
              get(new URL(res.headers.location, parsed).toString(), cb, onError, redirects + 1);
              return;
            }
            if (res.statusCode !== 200) {
              onError("HTTP " + res.statusCode + " fetching " + url);
              return;
            }
            cb(res);
          }).on("error", e => onError(String(e)));
        }
        const platform = process.argv[2];
        const outTar = process.argv[3];
        // Retries the whole manifest-fetch + download + checksum pipeline, matching
        // NodeBootstrapper.downloadFile's 3-attempt/2s-backoff behavior - a plain
        // one-shot download here would fail the entire agent install on a single
        // transient network blip while every other download in this app retries.
        function attempt(n) {
          const compressed = outTar + ".gz";
          fs.rmSync(compressed, { force: true });
          fs.rmSync(outTar, { force: true });
          function fail(msg) {
            process.stderr.write(msg + "\n");
            if (n < maxAttempts) {
              process.stderr.write("Retrying download (attempt " + (n + 1) + "/" + maxAttempts + ")...\n");
              setTimeout(() => attempt(n + 1), 2000);
            } else {
              process.exit(1);
            }
          }
          get("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/" + platform + ".json", res => {
            let data = "";
            res.on("data", c => data += c);
            res.on("end", () => {
              let manifest;
              try {
                manifest = JSON.parse(data);
              } catch (e) {
                fail("Invalid Antigravity manifest JSON: " + e);
                return;
              }
              if (!/^[a-f0-9]{128}$/i.test(manifest.sha512 || "")) {
                fail("Manifest did not provide a valid SHA-512 digest");
                return;
              }
              get(manifest.url, res2 => {
                const file = fs.createWriteStream(compressed);
                res2.pipe(file);
                file.on("finish", () => {
                  file.close(() => {
                    const hash = crypto.createHash("sha512");
                    const input = fs.createReadStream(compressed);
                    input.on("error", e => fail(String(e)));
                    input.on("data", chunk => hash.update(chunk));
                    input.on("end", () => {
                      const actual = hash.digest("hex");
                      if (actual.toLowerCase() !== manifest.sha512.toLowerCase()) {
                        fs.rmSync(compressed, { force: true });
                        fail("Antigravity archive checksum mismatch");
                        return;
                      }
                      const output = fs.createWriteStream(outTar);
                      fs.createReadStream(compressed).pipe(zlib.createGunzip()).pipe(output);
                      output.on("finish", () => fs.rmSync(compressed, { force: true }));
                      output.on("error", e => fail(String(e)));
                    });
                  });
                });
                file.on("error", e => fail(String(e)));
              }, fail);
            });
          }, fail);
        }
        attempt(1);
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

    /**
     * Aider is a popular AI pair programming tool. It requires a Python environment
     * with pip. This install command assumes `pip` is available (see PYTHON runner group).
     */
    private fun aiderInstallCommand(): String = """
        if ! command -v pip >/dev/null 2>&1 && ! command -v pip3 >/dev/null 2>&1; then
          echo "Aider requires Python and pip. Please install the Python toolchain in Settings -> Environments." >&2
          exit 1
        fi
        pip install aider-chat
    """.trimIndent()

    val Aider = AgentProfile(
        id = "aider",
        name = "Aider",
        command = "aider",
        installCommand = aiderInstallCommand(),
    )

    val All = listOf(Codex, Claude, Gemini, Aider, Antigravity)
}
