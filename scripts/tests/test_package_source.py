import importlib.util
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "package_source", Path(__file__).resolve().parents[1] / "package-source.py")
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class PackageSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.repository("main")

    def git(self, repo, *arguments):
        return subprocess.check_output(["git", "-C", str(repo), *arguments], stderr=subprocess.DEVNULL)

    def repository(self, name):
        repo = self.root / name
        repo.mkdir()
        self.git(repo, "init", "-q")
        self.git(repo, "config", "user.name", "Source fixture")
        self.git(repo, "config", "user.email", "source@example.invalid")
        (repo / "source.txt").write_text(name, encoding="utf-8")
        self.commit(repo)
        return repo

    def commit(self, repo):
        self.git(repo, "add", ".")
        self.git(repo, "commit", "-qm", "fixture")

    def test_packages_tracked_sources_not_local_secrets(self):
        (self.repo / "local.jks").write_text("secret", encoding="utf-8")
        output = self.root / "source.tar.gz"
        source.package_source(self.repo, output)
        with tarfile.open(output) as archive:
            self.assertEqual(b"main", archive.extractfile("MagicDesk/source.txt").read())
            self.assertNotIn("MagicDesk/local.jks", archive.getnames())
            self.assertFalse(any("/.git/" in name for name in archive.getnames()))
            self.assertIn(self.git(self.repo, "rev-parse", "HEAD").strip(),
                          archive.extractfile("MagicDesk/SOURCE_REVISIONS.txt").read())
        second = self.root / "second.tar.gz"
        source.package_source(self.repo, second)
        self.assertEqual(output.read_bytes(), second.read_bytes())

    def test_packages_nested_submodules(self):
        leaf = self.repository("leaf")
        library = self.repository("library")
        self.git(library, "-c", "protocol.file.allow=always", "submodule", "add", str(leaf), "nested")
        self.commit(library)
        self.git(self.repo, "-c", "protocol.file.allow=always", "submodule", "add", str(library), "vendor")
        self.git(self.repo, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--recursive")
        self.commit(self.repo)
        output = self.root / "source.tar.gz"
        source.package_source(self.repo, output)
        with tarfile.open(output) as archive:
            self.assertEqual(b"leaf", archive.extractfile("MagicDesk/vendor/nested/source.txt").read())

    def test_rejects_dirty_sources(self):
        (self.repo / "source.txt").write_text("uncommitted", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "modified tracked sources"):
            source.package_source(self.repo, self.root / "source.tar.gz")

    def test_rejects_uninitialized_submodule(self):
        library = self.repository("library")
        self.git(self.repo, "-c", "protocol.file.allow=always", "submodule", "add", str(library), "vendor")
        self.commit(self.repo)
        self.git(self.repo, "submodule", "deinit", "-f", "vendor")
        with self.assertRaisesRegex(ValueError, "not initialized"):
            source.package_source(self.repo, self.root / "source.tar.gz")


if __name__ == "__main__":
    unittest.main()
