import json
import pathlib
import unittest

import fetch_native_libs


ROOT = pathlib.Path(__file__).resolve().parents[1]


class NativeApkClosureTest(unittest.TestCase):
    def test_every_runtime_wrapper_is_reproducibly_generated(self):
        required = {
            "libnpm_wrapper.so",
            "libnpx_wrapper.so",
            "libpip_wrapper.so",
            "libpip3_wrapper.so",
            "libjdk_java_wrapper.so",
            "libkotlinc_wrapper.so",
            "libagent_codex_wrapper.so",
            "libagent_codex_native_wrapper.so",
            "libagent_claude_wrapper.so",
            "libagent_antigravity_wrapper.so",
            "libagent_aider_wrapper.so",
        }
        self.assertTrue(required.issubset(fetch_native_libs.NATIVE_WRAPPERS))
        for name in required:
            self.assertTrue(fetch_native_libs.NATIVE_WRAPPERS[name].startswith("#!/system/bin/sh\n"))

    def test_core_diagnostic_commands_are_in_the_pinned_manifest(self):
        manifest = json.loads((ROOT / "tools/native_libs_manifest.json").read_text())
        bundled = {entry["bundled_filename"] for entry in manifest["libraries"]}
        self.assertTrue(
            {
                "libcurl_native_aarch64.so",
                "librg_native_aarch64.so",
                "libjq_native_aarch64.so",
                "libfd_native_aarch64.so",
                "libsqlite3_native_aarch64.so",
                "libtar_native_aarch64.so",
            }.issubset(bundled)
        )


if __name__ == "__main__":
    unittest.main()
