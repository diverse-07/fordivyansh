#!/usr/bin/env python3
"""Validate, verify and package public NetraSaathi APK releases. Never reads API keys."""

import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import shutil
import subprocess
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TAG = re.compile(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def gateway_url(path):
    values = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if key.strip() == "museGatewayUrl" and separator:
            values.append(value.strip().rstrip("/"))
    if len(values) != 1:
        raise ValueError("gateway.properties must contain exactly one museGatewayUrl")
    url = urllib.parse.urlsplit(values[0])
    if (
        url.scheme != "https"
        or not url.hostname
        or url.username is not None
        or url.password is not None
        or url.query
        or url.fragment
        or url.path
        or url.port is not None
        or any(c.isspace() for c in values[0])
    ):
        raise ValueError(
            "The bundled Worker URL must be a public HTTPS origin without credentials"
        )
    if url.hostname == "localhost" or url.hostname.endswith((".localhost", ".local")):
        raise ValueError("Release APKs cannot use a local development gateway")
    try:
        address = ipaddress.ip_address(url.hostname)
    except ValueError:
        address = None
    if address is not None and not address.is_global:
        raise ValueError("Release APKs cannot use a private or loopback gateway")
    return values[0]


def release_metadata(ref, run_number, sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected a full Git commit SHA")
    code = 100000 + int(run_number)
    if int(run_number) < 1 or code > 2100000000:
        raise ValueError("Invalid release build number")
    if ref == "refs/heads/main":
        return {
            "version_code": str(code),
            "version_name": f"dev.{run_number}.{sha[:7]}",
            "release_tag": "latest",
            "release_title": "NetraSaathi — latest development build",
            "prerelease": "true",
        }
    tag = ref.removeprefix("refs/tags/")
    if not ref.startswith("refs/tags/") or not TAG.fullmatch(tag):
        raise ValueError(
            "Publish from main or a version tag such as v0.22.0 or v0.22.0-beta.1"
        )
    return {
        "version_code": str(code),
        "version_name": tag[1:],
        "release_tag": tag,
        "release_title": f"NetraSaathi {tag[1:]}",
        "prerelease": str("-" in tag).lower(),
    }


def verify_badging(text, code, name):
    package = re.search(r"^package: (.+)$", text, re.MULTILINE)
    if not package:
        raise ValueError("APK package metadata is missing")
    fields = dict(re.findall(r"([\w]+)='([^']*)'", package[1]))
    if (
        fields.get("name") != "com.netrasaathi"
        or fields.get("versionCode") != str(code)
        or fields.get("versionName") != name
        or re.search(r"^application-debuggable\b", text, re.MULTILINE)
    ):
        raise ValueError(
            "APK must be a non-debuggable NetraSaathi release with the expected version"
        )
    if "uses-permission: name='android.permission.INTERNET'" not in text:
        raise ValueError("APK is missing the network permission")


def verify_bundled_gateway(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        dex = [
            name
            for name in archive.namelist()
            if re.fullmatch(r"classes\d*\.dex", name)
        ]
        if not any(expected.encode() in archive.read(name) for name in dex):
            raise ValueError(
                "The public Worker URL was not found in the APK's compiled code"
            )


def file_sha256(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def stage_release(apk, output, metadata, url, sha, root=ROOT):
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, output / "NetraSaathi.apk")
    info = {
        **metadata,
        "commit": sha,
        "worker_url": url,
        "abi": "arm64-v8a",
        "minimum_android": "8.0",
    }
    (output / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    with zipfile.ZipFile(
        output / "NetraSaathi-notices.zip", "w", zipfile.ZIP_DEFLATED
    ) as archive:
        files = [root / "LICENSE", root / "NOTICE"]
        files += sorted((root / "licenses").glob("*"))
        files += sorted((root / "app/src/main/assets").glob("*.json"))
        for file in files:
            if file.is_file():
                archive.write(file, file.relative_to(root))
    checksum_files = ["NetraSaathi.apk", "NetraSaathi-notices.zip", "build-info.json"]
    (output / "SHA256SUMS").write_text(
        "".join(f"{file_sha256(output / name)}  {name}\n" for name in checksum_files)
    )
    (output / "release-notes.txt").write_text(
        f"Install **NetraSaathi.apk** on an ARM64 Android 8.0+ phone.\n\n"
        f"Version: `{metadata['version_name']}` · build `{metadata['version_code']}` · commit `{sha}`.\n\n"
        f"The app automatically connects to `{url}`. No gateway setup or API key is needed. "
        "Muse requires internet; initial setup downloads the local models. "
        "Local obstacle alerts work offline after setup.\n\n"
        "Release APKs use a dedicated, stable signing key. An existing debug installation "
        "may need to be uninstalled before installing a release.\n\n"
        "Experimental mobility aid. Detection and depth estimates do not establish path safety.\n\n"
        "Created by Pritom Das (@pritomshad). Apache-2.0; third-party terms remain applicable. "
        "See NetraSaathi-notices.zip for attribution and license texts. "
        "SHA256SUMS contains download checksums.\n"
    )


def android_tool(sdk, name):
    versions = sorted(
        (sdk / "build-tools").glob("*"),
        key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.name)),
        reverse=True,
    )
    return next(p / name for p in versions if (p / name).is_file())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("gateway")
    metadata = commands.add_parser("metadata")
    metadata.add_argument("--ref", required=True)
    metadata.add_argument("--run-number", required=True, type=int)
    metadata.add_argument("--sha", required=True)
    stage = commands.add_parser("stage")
    stage.add_argument("--apk", required=True, type=Path)
    stage.add_argument("--sdk", required=True, type=Path)
    stage.add_argument("--output", required=True, type=Path)
    stage.add_argument("--ref", required=True)
    stage.add_argument("--run-number", required=True, type=int)
    stage.add_argument("--sha", required=True)
    args = parser.parse_args()
    if args.command == "gateway":
        print(gateway_url(ROOT / "gateway.properties"))
    elif args.command == "metadata":
        for key, value in release_metadata(args.ref, args.run_number, args.sha).items():
            print(f"{key}={value}")
    else:
        url = gateway_url(ROOT / "gateway.properties")
        meta = release_metadata(args.ref, args.run_number, args.sha)
        subprocess.run(
            [
                str(android_tool(args.sdk, "apksigner")),
                "verify",
                "--verbose",
                "--print-certs",
                str(args.apk),
            ],
            check=True,
        )
        badging = subprocess.check_output(
            [str(android_tool(args.sdk, "aapt2")), "dump", "badging", str(args.apk)],
            text=True,
        )
        verify_badging(badging, meta["version_code"], meta["version_name"])
        verify_bundled_gateway(args.apk, url)
        stage_release(args.apk, args.output, meta, url, args.sha)
        print(
            f"Verified signed release, version {meta['version_name']}, bundled Worker: {url}"
        )


if __name__ == "__main__":
    main()
