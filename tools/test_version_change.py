import unittest

from version_change import classify, parse_build_file


def build_file(name: str, code: int) -> str:
    return f'''android {{
        defaultConfig {{
            versionCode = {code}
            versionName = "{name}"
        }}
    }}'''


class VersionChangeTest(unittest.TestCase):
    def test_minor_change_publishes(self) -> None:
        previous = parse_build_file(build_file("1.0", 1))
        current = parse_build_file(build_file("1.1.0", 2))
        self.assertTrue(classify(previous, current))

    def test_major_change_publishes(self) -> None:
        previous = parse_build_file(build_file("1.9.2", 12))
        current = parse_build_file(build_file("2.0.0", 13))
        self.assertTrue(classify(previous, current))

    def test_patch_change_does_not_publish(self) -> None:
        previous = parse_build_file(build_file("1.2.0", 4))
        current = parse_build_file(build_file("1.2.1", 5))
        self.assertFalse(classify(previous, current))

    def test_major_minor_change_requires_higher_version_code(self) -> None:
        previous = parse_build_file(build_file("1.2.0", 5))
        current = parse_build_file(build_file("1.3.0", 5))
        with self.assertRaisesRegex(ValueError, "versionCode must increase"):
            classify(previous, current)

    def test_patch_change_requires_higher_version_code(self) -> None:
        previous = parse_build_file(build_file("1.2.0", 5))
        current = parse_build_file(build_file("1.2.1", 5))
        with self.assertRaisesRegex(ValueError, "versionCode must increase"):
            classify(previous, current)

    def test_version_cannot_decrease(self) -> None:
        previous = parse_build_file(build_file("2.0.0", 8))
        current = parse_build_file(build_file("1.9.0", 9))
        with self.assertRaisesRegex(ValueError, "versionName decreased"):
            classify(previous, current)


if __name__ == "__main__":
    unittest.main()
