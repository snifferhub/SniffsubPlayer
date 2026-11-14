"""Generate the AltStore-compatible source shipped with a synchronized release."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import plistlib
import re
from urllib.parse import quote
from zipfile import ZipFile


BUNDLE_ID = "com.snifferhub.SniffSubPlayer"
IPA_NAME = "SniffSubPlayer-iOS-unsigned.ipa"
DESCRIPTION = (
    "Browse video pages, detect media resources, and play with external SRT / VTT subtitles. "
    "Sign and install the IPA with your own sideloading tool.\n\n"
    "浏览视频网页、嗅探媒体资源，使用原生播放器和外挂 SRT / VTT 字幕。"
    "请使用自己的侧载工具签名安装。"
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", default="snifferhub/SniffSubPlayer-Releases")
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.tag != f"android-v{args.version}-build.{args.build}":
        raise ValueError("Release tag must match the package version and build")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ValueError("Expected an owner/repository name")

    with ZipFile(args.ipa) as ipa:
        plists = [name for name in ipa.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(plists) != 1:
            raise ValueError("Expected exactly one iOS application in the IPA")
        # Extensions need their own permission declarations; do not silently omit them.
        if any(".appex/Info.plist" in name for name in ipa.namelist()):
            raise ValueError("Update source permission handling before shipping app extensions")
        if any(name.endswith("/embedded.mobileprovision") for name in ipa.namelist()):
            raise ValueError("The source must distribute the unsigned IPA")
        info = plistlib.loads(ipa.read(plists[0]))

    expected = {
        "CFBundleIdentifier": BUNDLE_ID,
        "CFBundleShortVersionString": args.version,
        "CFBundleVersion": args.build,
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError(f"IPA {key} does not match release metadata: {info.get(key)!r} != {value!r}")
    min_os = info.get("MinimumOSVersion")
    if not isinstance(min_os, str) or not re.fullmatch(r"\d+(?:\.\d+){0,2}", min_os):
        raise ValueError("IPA is missing a valid MinimumOSVersion")
    if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
        raise ValueError("The source requires an iPhoneOS device IPA")

    base_url = f"https://github.com/{args.repository}/releases"
    asset_url = f"{base_url}/download/{quote(args.tag, safe='')}"
    icon_url = f"{asset_url}/icon.png"
    version = {
        "version": args.version,
        "buildVersion": args.build,
        "date": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "localizedDescription": args.notes.read_text(encoding="utf-8").strip(),
        "downloadURL": f"{asset_url}/{IPA_NAME}",
        "size": args.ipa.stat().st_size,
        "minOSVersion": min_os,
    }
    source = {
        "name": "SniffSubPlayer",
        "identifier": f"{BUNDLE_ID}.source",
        "sourceURL": f"{base_url}/latest/download/altstore-source.json",
        "description": DESCRIPTION,
        "website": base_url,
        "iconURL": icon_url,
        "featuredApps": [BUNDLE_ID],
        "apps": [{
            "name": "SniffSubPlayer",
            "bundleIdentifier": BUNDLE_ID,
            "developerName": "snifferhub",
            "localizedDescription": DESCRIPTION,
            "iconURL": icon_url,
            "category": "photo-video",
            "versions": [version],
            "appPermissions": {
                # The unsigned host currently has no custom entitlements.
                "entitlements": [],
                "privacy": {key: value for key, value in info.items() if "UsageDescription" in key},
            },
        }],
        "news": [],
    }
    args.output.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
