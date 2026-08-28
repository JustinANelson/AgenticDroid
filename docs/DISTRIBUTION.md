# Distribution checklist

This checklist applies to every APK, App Bundle, companion-server package, or other binary release.
It is an engineering checklist, not legal advice.

## Current status

Public source distribution is enabled under Apache-2.0. Binary Android distribution is **blocked**
until maintainers complete and automate the third-party license/source steps below for the exact
native closure being shipped.

The release build reconstructs hundreds of native files from pinned Termux packages, including
components under copyleft licenses. The current fetch scripts preserve artifact hashes but do not
yet collect every package's `share/doc/<package>/copyright*` files or publish corresponding source.
The two tracked `libandroid-spawn.so` overrides also need their exact build provenance recorded.

## Before a binary release

1. Freeze the commit, version code/name, supported ABI, Android SDK/NDK, Gradle wrapper, and every
   entry in the three native manifests.
2. Rebuild in a clean environment. Do not reuse an unverified local `jniLibs` directory.
3. Produce an SBOM covering Gradle, npm, JitPack, generated native, and tracked binary inputs.
4. Extract and review the license/copyright files from every source `.deb`, including generic
   licenses supplied through Termux's `termux-licenses` package.
5. Determine the license and source-offer obligations of the combined artifact. Publish all
   required notices, license texts, modifications, build scripts, and complete corresponding
   source next to the binary for the required retention period.
6. Record reproducible provenance for both tracked `libandroid-spawn.so` files or rebuild them from
   reviewed source. Confirm their hashes match the released APK.
7. Include `LICENSE`, `THIRD_PARTY_NOTICES.md`, the generated dependency notices, and source links
   with the release. Do not rely on metadata stripped from dependency archives by APK packaging.
8. Run unit tests, lint, debug/release assembly, Android instrumented tests, secret scanning,
   dependency review, and a malware scan of generated artifacts.
9. Verify release signing, APK signature schemes, package/application IDs, exported components,
   requested permissions, cleartext-traffic behavior, backup exclusion, and 16 KB alignment on a
   clean supported arm64 device.
10. Exercise bootstrap, GitHub auth, Git clone/fetch/push, SSH fingerprint rejection, LAN auth and
    path containment, archive rejection, terminal lifecycle, project wipe, upgrade, and uninstall.
11. Publish checksums, release notes, known limitations, supported Android versions/ABIs, privacy
    information, and the security-reporting route.

Do not describe an unsigned artifact, a CI artifact, or a locally assembled APK as an official
release.
