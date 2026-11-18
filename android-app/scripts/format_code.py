#!/usr/bin/env python3
"""Reproducible developer formatting; no formatter is bundled in the Android app."""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
from xml.dom import minidom

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.64"
SHA256 = "5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2"


def formatted_xml(path):
    doc = minidom.parseString(path.read_bytes())

    def prune(node):
        children = list(node.childNodes)
        if any(child.nodeType == child.ELEMENT_NODE for child in children):
            for child in children:
                if child.nodeType == child.TEXT_NODE and not child.data.strip():
                    node.removeChild(child)
        for child in list(node.childNodes):
            if child.nodeType == child.ELEMENT_NODE:
                prune(child)

    prune(doc)
    return doc.toprettyxml(indent="    ", encoding="utf-8").decode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Check formatting without editing")
    parser.add_argument("--kotlin-only", action="store_true", help="Skip HTML and Worker formatting")
    options = parser.parse_args()
    cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "librevision"
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f"ktfmt-{VERSION}.jar"
    if not jar.exists():
        url = (
            f"https://repo.maven.apache.org/maven2/com/facebook/ktfmt/{VERSION}/"
            f"ktfmt-{VERSION}-with-dependencies.jar"
        )
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError("Formatter download checksum mismatch")
        jar.write_bytes(data)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Cached formatter checksum mismatch")
    files = sorted((ROOT / "app/src").rglob("*.kt")) + sorted(ROOT.glob("*.gradle.kts"))
    files += [ROOT / "app/build.gradle.kts"]
    flags = ["--dry-run", "--set-exit-if-changed"] if options.check else []
    subprocess.run(
        ["java", "-jar", str(jar), "--kotlinlang-style", "--quiet", *flags, *map(str, files)],
        check=True,
    )
    for path in sorted((ROOT / "app/src").rglob("*.xml")):
        formatted = formatted_xml(path)
        if options.check and path.read_text() != formatted:
            raise RuntimeError(f"XML formatting required: {path.relative_to(ROOT)}")
        if not options.check:
            path.write_text(formatted)
    if not options.kotlin_only:
        subprocess.run(
            [
                "npx", "--yes", "prettier@3.6.2",
                "--check" if options.check else "--write",
                "worker/src/**/*.mjs", "worker/test/**/*.mjs", "worker/scripts/**/*.mjs",
                "worker/package.json", "worker/wrangler.jsonc", "design/*.html",
            ],
            cwd=ROOT,
            check=True,
        )


if __name__ == "__main__":
    main()
