#!/usr/bin/env python3
"""Generates app-update.json — the manifest consumed by the in-app
self-updater of IP-TV Player.

The manifest is attached to every GitHub Release; the app fetches
    https://github.com/<owner>/<repo>/releases/latest/download/app-update.json
compares `versionCode` with its own build number and offers to install.

Usage (as done by .github/workflows/android.yml):
    python3 scripts/make_update_manifest.py \
        --repo zigorminsk-debug/IP-TV-player \
        --tag v1.0.2 \
        --version-name 1.0.2 \
        --release-number 2 \
        --build-number 42 \
        --apk dist/IP-TV-Player_1.0.2_build42_release.apk \
        --apk-url https://github.com/.../IP-TV-Player_1.0.2_build42_release.apk \
        --notes "What's new ..." \
        --out dist/app-update.json

Versioning: versionName is MAJOR.MINOR.<release number> (the patch component is
the sequential release number), versionCode is the CI build number — see
docs/04-RELEASE.md.
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--tag", required=True, help="release tag, e.g. v1.0.0")
    parser.add_argument("--version-name", required=True, help="e.g. 1.0.2")
    parser.add_argument("--build-number", required=True, type=int, help="versionCode")
    parser.add_argument(
        "--release-number",
        type=int,
        default=0,
        help="sequential release number (defaults to the patch component)",
    )
    parser.add_argument("--apk", required=True, help="path to the release APK")
    parser.add_argument("--apk-url", required=True, help="stable download URL of the APK")
    parser.add_argument("--notes", default="", help="release notes text")
    parser.add_argument("--min-sdk", type=int, default=23)
    parser.add_argument("--out", required=True, help="output path (app-update.json)")
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        print(f"error: APK not found: {args.apk}", file=sys.stderr)
        return 1
    if args.build_number <= 0:
        print("error: build number must be positive", file=sys.stderr)
        return 1

    release_number = args.release_number
    if release_number <= 0:
        # Fall back to the patch component: versionName = MAJOR.MINOR.<release>.
        tail = args.version_name.split(".")[-1].split("-")[0]
        release_number = int(tail) if tail.isdigit() else 0

    manifest = {
        # versionCode MUST only ever increase — it is the update criterion.
        "versionCode": args.build_number,
        "versionName": args.version_name,
        "releaseNumber": release_number,
        "buildNumber": args.build_number,
        "apkUrl": args.apk_url,
        "apkSize": os.path.getsize(args.apk),
        "apkSha256": sha256_of(args.apk),
        "releaseNotes": args.notes.strip(),
        "releaseDate": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "minSdk": args.min_sdk,
        "repo": args.repo,
        "tag": args.tag,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(
        f"written {args.out} (version {args.version_name}, release #{release_number}, "
        f"versionCode={args.build_number})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
