import tempfile
import unittest
import zipfile
from pathlib import Path

from app import safe_extract, validate_gradle_task


class GradleTaskAllowlistTests(unittest.TestCase):
    def test_known_tasks_are_accepted(self):
        for task in ("tasks", "assembleDebug", "testDebugUnitTest", "lintDebug", "check", "clean"):
            self.assertEqual(validate_gradle_task(task), task)

    def test_arbitrary_shell_and_gradle_tasks_are_rejected(self):
        for task in ("bash -c id", "publish", "installDebug", "../../etc/passwd", ""):
            with self.assertRaises(ValueError):
                validate_gradle_task(task)


class SafeArchiveTests(unittest.TestCase):
    def test_extracts_normal_project_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "project.zip"
            destination = root / "out"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("app/build.gradle.kts", "plugins {}")
            safe_extract(archive, destination)
            self.assertEqual((destination / "app/build.gradle.kts").read_text(), "plugins {}")

    def test_rejects_parent_directory_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "bad.zip"
            destination = root / "out"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../escape.txt", "not allowed")
            with self.assertRaises(ValueError):
                safe_extract(archive, destination)
            self.assertFalse((root / "escape.txt").exists())


if __name__ == "__main__":
    unittest.main()
