"""Resolve semantic release versions and monotonically increasing package builds."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


LEGACY_TAG_PATTERN = re.compile(r"android-v(\d+\.\d+\.\d+)-build\.(\d+)")
VERSION_TAG_PATTERN = re.compile(r"v(\d+\.\d+\.\d+)")
BUILD_MARKER = re.compile(r"<!-- sniffsub-release build=(\d+) -->")


def release_metadata(release: dict) -> tuple[str, int] | None:
    tag = release.get("tag_name", "")
    if match := LEGACY_TAG_PATTERN.fullmatch(tag):
        return match[1], int(match[2])
    if match := VERSION_TAG_PATTERN.fullmatch(tag):
        if build := BUILD_MARKER.search(release.get("body") or ""):
            return match[1], int(build[1])
    return None


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def release_range(releases: list, head: str, current_tag: str) -> str:
    """Use the latest published package build, including the old version series."""
    head_sha = git("rev-parse", "--verify", f"{head}^{{commit}}")
    candidates = []
    for release in releases:
        tag = release.get("tag_name", "")
        metadata = release_metadata(release)
        if not metadata or tag == current_tag or release.get("draft") or release.get("prerelease"):
            continue
        if not any(
            asset.get("name") == "SniffSubPlayer-Android.apk" and asset.get("state") == "uploaded"
            for asset in release.get("assets", [])
        ):
            continue
        candidates.append((metadata[1], tag))
    if not candidates:
        # A first release includes the source history, filtered by cliff.toml.
        return head_sha

    _, tag = max(candidates)
    try:
        base_sha = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    except subprocess.CalledProcessError as error:
        raise ValueError(f"Published release tag {tag} is missing from source history") from error
    ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", base_sha, head_sha], check=False)
    if ancestry.returncode == 1:
        # Amending a published commit keeps the common history usable for notes.
        common = subprocess.run(
            ["git", "merge-base", base_sha, head_sha], capture_output=True, text=True, check=False
        )
        if common.returncode == 0:
            return f"{common.stdout.strip()}..{head_sha}"
        print(f"::notice::Published release {tag} is on the former Android history; using current branch history")
        return head_sha
    if ancestry.returncode != 0:
        raise ValueError(f"Could not check ancestry for published release {tag}")
    return f"{base_sha}..{head_sha}"

def prepare_release(releases: list, head: str, run_number: int) -> dict[str, str]:
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'\.gradleProperty\("releaseVersion"\)\.orElse\("(\d+\.\d+\.\d+)"\)', source)
    if match is None:
        raise ValueError("Missing semantic releaseVersion baseline in app/build.gradle.kts")
    baseline = tuple(map(int, match[1].split(".")))
    # Workflow counters can restart when files change. Reserve past source tags too,
    # so a failed upload or rerun cannot reuse a signed APK's version code.
    tags = git("tag", "--list").splitlines()
    builds = [int(match[2]) for tag in tags if (match := LEGACY_TAG_PATTERN.fullmatch(tag))]
    builds.extend(metadata[1] for release in releases if (metadata := release_metadata(release)))
    highest_build = max(builds, default=0)
    build = max(run_number, highest_build + 1)
    if not 1 <= build <= 2_147_483_647 - 10_000:
        raise ValueError("Android release build number is out of range")
    # Legacy 1.1.0-build tags only reserve internal build numbers. The new public
    # version series intentionally starts at 1.0.0 and advances its patch number.
    version_tags = tags + [release.get("tag_name", "") for release in releases]
    versions = [tuple(map(int, match[1].split("."))) for tag in version_tags
                if (match := VERSION_TAG_PATTERN.fullmatch(tag))]
    latest = max(versions, default=None)
    resolved = (latest[0], latest[1], latest[2] + 1) if latest and latest >= baseline else baseline
    version = ".".join(map(str, resolved))
    tag = f"v{version}"
    return {
        "version": version,
        "build": str(build),
        "tag": tag,
        "build_tag": f"android-v{version}-build.{build}",
        "range": release_range(releases, head, tag),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--releases", type=Path, required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--run-number", type=int, required=True)
    args = parser.parse_args()
    # gh api --paginate --slurp returns one release array per page.
    pages = json.loads(args.releases.read_text(encoding="utf-8"))
    releases = [release for page in pages for release in page]
    outputs = prepare_release(releases, args.head, args.run_number)
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for name, value in outputs.items():
            output.write(f"{name}={value}\n")


if __name__ == "__main__":
    main()
