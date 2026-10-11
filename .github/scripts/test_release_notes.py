"""Regression tests use disposable Git repositories, never the source checkout's Git state."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from release_notes import finalize_notes, git, MAINTENANCE_NOTES, release_range


SCRIPT = Path(__file__).with_name("release_notes.py").resolve()
CONFIG = Path(__file__).resolve().parents[1] / "cliff.toml"
CLIFF = shutil.which("git-cliff")
BASE_TAG = "android-v1.1.0-build.10"
CURRENT_TAG = "android-v1.1.0-build.12"


def published(tag: str, **fields) -> dict:
    return {
        "tag_name": tag,
        "draft": False,
        "prerelease": False,
        "assets": [{"name": "SniffSubPlayer-Android.apk", "state": "uploaded"}],
        **fields,
    }


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="android-release-notes-test-")
        self.addCleanup(self.temp.cleanup)
        self.previous_cwd = Path.cwd()
        self.addCleanup(os.chdir, self.previous_cwd)
        isolated_git = patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull})
        isolated_git.start()
        self.addCleanup(isolated_git.stop)
        os.chdir(self.temp.name)
        git("init", "-q")
        git("config", "user.name", "Release Notes Test")
        git("config", "user.email", "test@example.invalid")
        self.count = 0
        self.base = self.commit("feat: 旧功能")
        git("tag", BASE_TAG)

    def commit(self, title: str, path: str = "app/src/main/player.kt") -> str:
        self.count += 1
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(str(self.count), encoding="utf-8")
        git("add", path)
        git("commit", "-q", "-m", title)
        return git("rev-parse", "HEAD")

    def generate(self, commit_range: str) -> str:
        subprocess.run(
            [CLIFF, "--config", str(CONFIG), "--output", "notes.md", commit_range],
            check=True, capture_output=True, text=True,
        )
        finalize_notes(Path("notes.md"))
        return Path("notes.md").read_text(encoding="utf-8")

    def test_skipped_commits_and_unpublished_tags_remain_in_the_next_release(self):
        self.commit("feat: 新增窗口亮度手势 [skip ci]")
        git("tag", "android-v1.1.0-build.11")
        head = self.commit("fix: 修复音量跳变")
        commit_range = release_range([published(BASE_TAG)], "HEAD", CURRENT_TAG)
        self.assertEqual(f"{self.base}..{head}", commit_range)

    def test_release_order_matches_app_versions_not_api_list_order(self):
        self.commit("feat: 新版功能")
        git("tag", "android-v1.2.0-build.11")
        latest_base = git("rev-parse", "HEAD")
        head = self.commit("fix: 最新修复")
        releases = [published(BASE_TAG), published("android-v1.2.0-build.11")]
        self.assertEqual(
            f"{latest_base}..{head}", release_range(releases, "HEAD", CURRENT_TAG),
        )

    def test_rerunning_a_published_build_excludes_its_own_release(self):
        head = self.commit("fix: 当前修复")
        git("tag", CURRENT_TAG)
        self.assertEqual(
            f"{self.base}..{head}",
            release_range([published(CURRENT_TAG), published(BASE_TAG)], "HEAD", CURRENT_TAG),
        )

    def test_first_release_includes_history_and_ignores_ineligible_releases(self):
        head = git("rev-parse", "HEAD")
        self.assertEqual(head, release_range([], "HEAD", CURRENT_TAG))
        releases = [
            published("android-v1.2.0-build.11", draft=True),
            published("android-v1.2.0-build.11", prerelease=True),
            published("android-v1.2.0-build.11", assets=[]),
            published("unrelated-release"),
        ]
        self.assertEqual(head, release_range(releases, "HEAD", CURRENT_TAG))

    def test_missing_source_tag_stops_generation(self):
        with self.assertRaisesRegex(ValueError, "missing from source history"):
            release_range([published("android-v1.1.0-build.11")], "HEAD", CURRENT_TAG)

    def test_a_release_outside_the_current_history_stops_generation(self):
        tree = git("rev-parse", "HEAD^{tree}")
        unrelated = git("commit-tree", tree, "-m", "Unrelated source history")
        git("tag", "android-v1.1.0-build.11", unrelated)
        with self.assertRaisesRegex(ValueError, "not an ancestor"):
            release_range([published("android-v1.1.0-build.11")], "HEAD", CURRENT_TAG)

    def test_cli_reads_all_paginated_releases(self):
        head = self.commit("fix: 新修复")
        Path("releases.json").write_text(json.dumps([[], [published(BASE_TAG)]]))
        subprocess.run(
            ["python3", str(SCRIPT), "range", "--releases", "releases.json",
             "--head", head, "--current-tag", CURRENT_TAG],
            check=True,
            env={**os.environ, "GITHUB_OUTPUT": str(Path("output").resolve())},
        )
        self.assertEqual(f"range={self.base}..{head}\n", Path("output").read_text())

    def test_empty_notes_use_a_maintenance_note_and_write_the_summary(self):
        Path("notes.md").write_text("本次更新\n\n")
        finalize_notes(Path("notes.md"), Path("summary.md"))
        self.assertEqual(MAINTENANCE_NOTES, Path("notes.md").read_text())
        self.assertIn(MAINTENANCE_NOTES.strip(), Path("summary.md").read_text())

    def test_finalization_preserves_commit_wording_and_normalizes_category_spacing(self):
        Path("notes.md").write_text("本次更新\n\n\n问题修复\n\n- Fix playback gestures\n")
        finalize_notes(Path("notes.md"))
        self.assertEqual(
            "本次更新\n\n问题修复\n\n- Fix playback gestures\n", Path("notes.md").read_text(),
        )

    @unittest.skipUnless(CLIFF, "git-cliff is required for generator integration tests")
    def test_generated_notes_include_all_unpublished_changes_without_old_or_private_content(self):
        self.commit("feat(player): 新增亮度手势 [skip ci]")
        git("tag", "android-v1.1.0-build.11")
        self.commit("fix(player): 修复音量跳变\n\nInternal body must not be published")
        self.commit("feat(player)!: 调整手势兼容性")
        self.commit("修正字幕选择流程")
        self.commit("fix: 只修改文档", "README.md")
        self.commit("test: 增加测试", "app/src/test/player.kt")
        self.commit("build: 更新依赖 https://github.com/private/source", "gradle/libs.versions.toml")
        notes = self.generate(release_range([published(BASE_TAG)], "HEAD", CURRENT_TAG))
        for expected in ("新增亮度手势", "修复音量跳变", "【兼容性变更】", "修正字幕选择流程", "更新依赖"):
            self.assertIn(expected, notes)
        for excluded in ("旧功能", "skip ci", "Internal body", "只修改文档", "增加测试", "https://", "test@example.invalid", "feat(player)"):
            self.assertNotIn(excluded, notes)
        self.assertEqual(1, notes.count("本次更新"))
        self.assertIn("\n新增功能\n", notes)
        self.assertIn("\n问题修复\n", notes)

    @unittest.skipUnless(CLIFF, "git-cliff is required for generator integration tests")
    def test_maintenance_only_changes_have_a_nonempty_release_body(self):
        self.commit("ci: 调整发布流程", ".github/workflows/release.yml")
        self.commit("docs: 修改说明", "README.md")
        notes = self.generate(release_range([published(BASE_TAG)], "HEAD", CURRENT_TAG))
        self.assertEqual(MAINTENANCE_NOTES, notes)

    @unittest.skipUnless(CLIFF, "git-cliff is required for generator integration tests")
    def test_first_release_includes_the_root_commit(self):
        notes = self.generate(release_range([], "HEAD", CURRENT_TAG))
        self.assertIn("旧功能", notes)


if __name__ == "__main__":
    unittest.main()
