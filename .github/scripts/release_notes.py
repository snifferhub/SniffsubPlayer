"""Resolve the source range of the last Android release actually published publicly."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


TAG_PATTERN = re.compile(r"android-v(\d+\.\d+\.\d+)-build\.(\d+)")
MAINTENANCE_NOTES = "本次更新\n\n- 本次发布包含构建和维护调整。\n"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def release_range(releases: list, head: str, current_tag: str) -> str:
    """Match the app's version ordering; drafts/prereleases/current reruns aren't baselines."""
    head_sha = git("rev-parse", "--verify", f"{head}^{{commit}}")
    candidates = []
    for release in releases:
        tag = release.get("tag_name", "")
        match = TAG_PATTERN.fullmatch(tag)
        if not match or tag == current_tag or release.get("draft") or release.get("prerelease"):
            continue
        if not any(
            asset.get("name") == "SniffSubPlayer-Android.apk" and asset.get("state") == "uploaded"
            for asset in release.get("assets", [])
        ):
            continue
        version = tuple(int(part) for part in match[1].split("."))
        candidates.append(((*version, int(match[2])), tag))
    if not candidates:
        # A first release includes the Android history, filtered by cliff.toml.
        return head_sha

    _, tag = max(candidates)
    try:
        base_sha = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    except subprocess.CalledProcessError as error:
        raise ValueError(f"Published release tag {tag} is missing from source history") from error
    ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", base_sha, head_sha], check=False)
    if ancestry.returncode == 1:
        # The initial KMP main imports Android without merging its Git ancestry.
        print(f"::notice::Published release {tag} is on the former Android history; using current branch history")
        return head_sha
    if ancestry.returncode != 0:
        raise ValueError(f"Could not check ancestry for published release {tag}")
    return f"{base_sha}..{head_sha}"

def prepare_release(releases: list, head: str, run_number: int) -> dict[str, str]:
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'^\s*versionName = "(\d+\.\d+\.\d+)"', source, re.MULTILINE)
    if match is None:
        raise ValueError("Missing semantic versionName in app/build.gradle.kts")
    version = match[1]
    # Workflow counters can restart when files change. Reserve past source tags too,
    # so a failed upload or rerun cannot reuse a signed APK's version code.
    tags = [release.get("tag_name", "") for release in releases]
    tags.extend(git("tag", "--list", "android-v*-build.*").splitlines())
    parsed = [match for tag in tags if (match := TAG_PATTERN.fullmatch(tag))]
    highest_build = max((int(match[2]) for match in parsed), default=0)
    build = max(run_number, highest_build + 1)
    if not 1 <= build <= 2_147_483_647 - 10_000:
        raise ValueError("Android release build number is out of range")
    current_version = tuple(map(int, version.split(".")))
    if any(tuple(map(int, match[1].split("."))) > current_version for match in parsed):
        raise ValueError("versionName must not regress behind an existing Android release")
    tag = f"android-v{version}-build.{build}"
    return {
        "version": version,
        "build": str(build),
        "tag": tag,
        "range": release_range(releases, head, tag),
    }


def finalize_notes(path: Path, summary_path: Path | None = None) -> None:
    notes = re.sub(r"\n{3,}", "\n\n", path.read_text(encoding="utf-8").strip())
    if not any(line.startswith("- ") for line in notes.splitlines()):
        notes = MAINTENANCE_NOTES.strip()
    path.write_text(notes + "\n", encoding="utf-8")
    if summary_path:
        with summary_path.open("a", encoding="utf-8") as summary:
            summary.write("## 自动生成的 Android 更新说明\n\n" + notes + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    resolve = commands.add_parser("range")
    resolve.add_argument("--releases", type=Path, required=True)
    resolve.add_argument("--head", required=True)
    resolve.add_argument("--current-tag", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("--releases", type=Path, required=True)
    prepare.add_argument("--head", required=True)
    prepare.add_argument("--run-number", type=int, required=True)
    finalize = commands.add_parser("finalize")
    finalize.add_argument("--notes", type=Path, required=True)
    args = parser.parse_args()

    if args.command in ("range", "prepare"):
        # gh api --paginate --slurp returns one release array per page.
        pages = json.loads(args.releases.read_text(encoding="utf-8"))
        if not isinstance(pages, list) or not all(isinstance(page, list) for page in pages):
            raise ValueError("Expected paginated GitHub Releases JSON arrays")
        releases = [release for page in pages for release in page]
        outputs = (
            prepare_release(releases, args.head, args.run_number)
            if args.command == "prepare"
            else {"range": release_range(releases, args.head, args.current_tag)}
        )
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            for name, value in outputs.items():
                output.write(f"{name}={value}\n")
    else:
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        finalize_notes(args.notes, Path(summary) if summary else None)


if __name__ == "__main__":
    main()
