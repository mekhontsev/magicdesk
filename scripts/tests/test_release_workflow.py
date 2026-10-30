import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_version", ROOT / "scripts/verify-version.py")
versions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(versions)


class VersionChannelTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Version fixture")
        self.git("config", "user.email", "version@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.write_version("2.1", 219)
        self.commit()
        self.git("tag", "v2.1")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def git(self, *arguments):
        return subprocess.check_output(["git", "-C", str(self.repo), *arguments],
                                       text=True, stderr=subprocess.PIPE).strip()

    def write_version(self, name, code):
        (self.repo / "gradle.properties").write_text(
            f"magicDeskVersionName={name}\nmagicDeskVersionCode={code}\n", encoding="utf-8")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def prepare_release(self):
        self.write_version("2.1.1", 220)
        self.commit()
        self.git("tag", "-a", "v2.1.1", "-m", "release")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def test_main_can_match_latest_release(self):
        self.assertIn("main: 2.1 (219)", versions.verify(self.repo, "main"))

    def test_develop_must_advance_after_release(self):
        with self.assertRaisesRegex(ValueError, "must be newer"):
            versions.verify(self.repo, "develop")

    def test_next_version_can_integrate_into_main(self):
        self.write_version("2.1.1", 220)
        for channel in ("develop", "main"):
            self.assertIn("2.1.1 (220)", versions.verify(self.repo, channel))

    def test_same_version_cannot_change_code(self):
        self.write_version("2.1", 220)
        with self.assertRaisesRegex(ValueError, "retain its versionCode"):
            versions.verify(self.repo, "main")

    def test_next_version_requires_higher_code(self):
        for code in (218, 219):
            self.write_version("2.1.1", code)
            with self.assertRaisesRegex(ValueError, "must be greater"):
                versions.verify(self.repo, "develop")

    def test_rejects_version_rollback(self):
        self.write_version("2.0.9", 220)
        with self.assertRaisesRegex(ValueError, "must be at least"):
            versions.verify(self.repo, "main")

    def test_rolling_and_prerelease_tags_are_not_release_baselines(self):
        self.git("tag", "development")
        self.git("tag", "v9.0.0-rc1")
        self.write_version("2.1.1", 220)
        self.assertIn("baseline v2.1", versions.verify(self.repo, "develop"))

    def test_numeric_version_order(self):
        self.write_version("2.9", 230)
        self.commit()
        self.git("tag", "v2.9")
        self.write_version("2.10.0", 231)
        self.assertIn("baseline v2.9", versions.verify(self.repo, "develop"))

    def test_revision_release_and_next_cycle(self):
        self.write_version("2.1.0.1", 220)
        self.commit()
        self.git("tag", "v2.1.0.1")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.assertIn("baseline v2.1", versions.verify(self.repo, "release", "v2.1.0.1"))
        self.assertIn("main: 2.1.0.1 (220)", versions.verify(self.repo, "main"))
        self.git("switch", "-qc", "develop")
        result = self.run_next_version("2.1.0.2", "221")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("baseline v2.1.0.1 (220)", versions.verify(self.repo, "develop"))

    def test_revision_versions_compare_numerically(self):
        self.write_version("2.1.0.9", 220)
        self.commit()
        self.git("tag", "v2.1.0.9")
        for name in ("2.1.0.10", "2.1.1"):
            with self.subTest(name=name):
                self.write_version(name, 221)
                self.assertIn("baseline v2.1.0.9", versions.verify(self.repo, "develop"))
        self.write_version("2.1.0.8", 221)
        with self.assertRaisesRegex(ValueError, "must be newer"):
            versions.verify(self.repo, "develop")

    def test_omitted_zero_components_do_not_advance_version(self):
        for name in ("2.1.0", "2.1.0.0"):
            with self.subTest(name=name):
                self.write_version(name, 219)
                self.assertIn("baseline v2.1", versions.verify(self.repo, "main"))
                with self.assertRaisesRegex(ValueError, "must be newer"):
                    versions.verify(self.repo, "develop")

    def test_release_tag_on_main(self):
        self.prepare_release()
        self.assertIn("baseline v2.1", versions.verify(self.repo, "release", "v2.1.1"))
        self.assertIn("baseline v2.1.1", versions.verify(self.repo, "main"))

    def test_release_rejects_unmerged_commit(self):
        self.write_version("2.1.1", 220)
        self.commit()
        self.git("tag", "v2.1.1")
        with self.assertRaisesRegex(ValueError, "must belong to main"):
            versions.verify(self.repo, "release", "v2.1.1")

    def test_release_rejects_wrong_version_tag(self):
        self.prepare_release()
        with self.assertRaisesRegex(ValueError, "Release tag must be v2.1.1"):
            versions.verify(self.repo, "release", "v2.2")

    def test_release_rejects_wrong_tag_commit(self):
        self.prepare_release()
        (self.repo / "later.txt").write_text("later", encoding="utf-8")
        self.commit()
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        with self.assertRaisesRegex(ValueError, "must point to the checked-out commit"):
            versions.verify(self.repo, "release", "v2.1.1")

    def test_manual_release_can_build_existing_main_version(self):
        self.assertIn("release: 2.1 (219)", versions.verify(self.repo, "release"))

    def test_legacy_release_code(self):
        (self.repo / "gradle.properties").unlink()
        (self.repo / "app").mkdir()
        (self.repo / "app/build.gradle").write_text("versionCode 220\n", encoding="utf-8")
        self.commit()
        self.git("tag", "v2.1.1")
        self.write_version("2.1.2", 221)
        self.assertIn("v2.1.1 (220)", versions.verify(self.repo, "develop"))

    def test_missing_release_history_fails(self):
        self.git("tag", "-d", "v2.1")
        with self.assertRaisesRegex(ValueError, "complete tag history"):
            versions.verify(self.repo, "main")

    def test_invalid_properties(self):
        for name, code in (("bad", 220), ("2.1.0.1.1", 220), ("2.1.0.", 220),
                           ("2.1.1", 0), ("2.1.1", 2147483648)):
            with self.subTest(name=name, code=code):
                self.write_version(name, code)
                with self.assertRaises(ValueError):
                    versions.verify(self.repo, "develop")

    def test_unknown_channel_and_misplaced_tag(self):
        with self.assertRaisesRegex(ValueError, "Unknown channel"):
            versions.verify(self.repo, "other")
        with self.assertRaisesRegex(ValueError, "requires the release channel"):
            versions.verify(self.repo, "main", "v2.1")

    def run_next_version(self, name="2.1.1", code="220"):
        scripts = self.repo / "scripts"
        scripts.mkdir(exist_ok=True)
        shutil.copyfile(ROOT / "scripts/start-next-version.sh", scripts / "start-next-version.sh")
        return subprocess.run(["sh", str(scripts / "start-next-version.sh"), name, code],
                              text=True, capture_output=True, check=False)

    def test_next_cycle_only_changes_develop(self):
        original = (self.repo / "gradle.properties").read_bytes()
        result = self.run_next_version()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("on develop", result.stderr)
        self.assertEqual(original, (self.repo / "gradle.properties").read_bytes())
        self.git("switch", "-qc", "develop")
        result = self.run_next_version()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("develop: 2.1.1 (220)", versions.verify(self.repo, "develop"))

    def test_next_cycle_rejects_detached_head(self):
        self.git("checkout", "-q", "--detach")
        result = self.run_next_version()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("detached HEAD", result.stderr)


class WorkflowBoundaryTest(unittest.TestCase):
    def test_development_publication_uses_only_develop(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        publish = workflow.split("  publish-development:\n", 1)[1]
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/develop'", publish)
        self.assertIn("magicdesk-develop-$GITHUB_RUN_NUMBER", publish)
        self.assertIn("tag=development", publish)
        self.assertNotIn("paths-ignore:", workflow)
        self.assertIn("VERSION_CHANNEL: ${{ github.base_ref || github.ref_name }}", workflow)
        self.assertNotIn("pull_request_target:", workflow)

    def test_release_promotes_only_verified_main_artifacts(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertLess(workflow.index("Verify release origin and version"),
                        workflow.index("Download verified main artifacts"))
        self.assertIn("fetch-depth: 0", workflow)
        self.assertIn("scripts/verify-version.py --channel release", workflow)
        self.assertLess(workflow.index("Require successful main CI"),
                        workflow.index("Download verified main artifacts"))
        self.assertIn('-f branch=main -f head_sha="$GITHUB_SHA" -f event=push', workflow)
        self.assertIn('-f status=success', workflow)
        self.assertIn('--name "magicdesk-main-$CI_RUN_NUMBER"', workflow)
        self.assertIn('download/BUILD.txt)" = "$GITHUB_SHA"', workflow)
        self.assertIn('download/BUILD.txt)" = "$CI_RUN_ID"', workflow)
        self.assertIn('sha256sum --check SHA256SUMS', workflow)
        self.assertIn('test "$app_version" = "$version"', workflow)
        self.assertIn('test "$app_code" = "$version_code"', workflow)
        self.assertIn('apksigner" verify --verbose', workflow)
        self.assertLess(workflow.index("Verify signature and APK boundaries"),
                        workflow.index("Publish GitHub release"))
        self.assertNotIn('gradlew', workflow)
        self.assertNotIn('secrets.MAGICDESK_RELEASE_', workflow)
        self.assertIn('cancel-in-progress: false', workflow)

    def test_main_build_has_release_version_and_develop_has_suffix(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        signed = workflow.split("      - name: Lint and build signed APKs\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn('version_args=()', signed)
        self.assertIn('if [[ "$GITHUB_REF" == refs/heads/develop ]]; then', signed)
        self.assertIn('version_args=(-PMAGICDESK_VERSION_SUFFIX=', signed)
        self.assertIn('"${version_args[@]}"', signed)


if __name__ == "__main__":
    unittest.main()
