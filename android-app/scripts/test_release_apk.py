"""Release guardrails: public gateway, versioning, APK identity and attribution packaging."""

import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_apk import (
    gateway_url,
    release_metadata,
    stage_release,
    verify_badging,
    verify_bundled_gateway,
)

SHA = "a" * 40
URL = "https://librevision-navigation-gateway.2021331039.workers.dev"


class ReleaseTest(unittest.TestCase):
    def test_gateway_is_required_public_and_has_no_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "gateway.properties"
            config.write_text(f"# public configuration\nmuseGatewayUrl={URL}/\n")
            self.assertEqual(URL, gateway_url(config))
            for value in [
                "",
                "http://127.0.0.1:8790",
                "https://localhost",
                "https://192.168.0.1",
                "https://127.0.0.1",
                "https://gateway.local",
                "https://user:password@example.com",
                "https://example.com/path",
                "https://example.com?key=secret",
                "https://example.com#secret",
            ]:
                config.write_text(f"museGatewayUrl={value}\n")
                with self.subTest(value=value), self.assertRaises(ValueError):
                    gateway_url(config)
            config.write_text(f"museGatewayUrl={URL}\nmuseGatewayUrl={URL}\n")
            with self.assertRaises(ValueError):
                gateway_url(config)

    def test_release_versions_and_update_codes(self):
        main = release_metadata("refs/heads/main", 4, SHA)
        self.assertEqual("latest", main["release_tag"])
        self.assertEqual("true", main["prerelease"])
        stable = release_metadata("refs/tags/v0.22.0", 5, SHA)
        self.assertEqual("0.22.0", stable["version_name"])
        self.assertEqual("false", stable["prerelease"])
        self.assertGreater(int(stable["version_code"]), int(main["version_code"]))
        self.assertEqual(
            "true", release_metadata("refs/tags/v0.22.0-beta.1", 6, SHA)["prerelease"]
        )

    def test_untrusted_refs_and_invalid_metadata_are_rejected(self):
        for ref in [
            "refs/pull/1/merge",
            "refs/heads/feature",
            "refs/tags/vbad",
            "refs/tags/v0.2.0;echo",
        ]:
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                release_metadata(ref, 1, SHA)
        for number in [0, -1, 2100000000]:
            with self.subTest(number=number), self.assertRaises(ValueError):
                release_metadata("refs/heads/main", number, SHA)
        with self.assertRaises(ValueError):
            release_metadata("refs/heads/main", 1, "not-a-commit")

    def test_debug_wrong_version_and_missing_network_permission_are_rejected(self):
        good = "package: name='com.librevision' versionCode='100001' versionName='0.22.0'\nuses-permission: name='android.permission.INTERNET'\n"
        verify_badging(good, 100001, "0.22.0")
        for text in [
            good + "application-debuggable\n",
            good.replace("100001", "21"),
            good.replace("com.librevision", "another.app"),
            good.split("\n")[0],
        ]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                verify_badging(text, 100001, "0.22.0")

    def test_gateway_must_be_in_compiled_apk_not_just_an_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"example compiled strings")
                archive.writestr("assets/gateway.properties", URL)
            with self.assertRaises(ValueError):
                verify_bundled_gateway(apk, URL)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes2.dex", URL)
            verify_bundled_gateway(apk, URL)

    def test_release_contains_checksums_and_credits_without_models_or_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in [
                "LICENSE",
                "NOTICE",
                "licenses/credit.txt",
                "app/src/main/assets/model.json",
                "app/src/main/assets/model.onnx",
            ]:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("test")
            apk = root / "app.apk"
            apk.write_bytes(b"synthetic APK")
            output = root / "release"
            meta = release_metadata("refs/heads/main", 1, SHA)
            stage_release(apk, output, meta, URL, SHA, root)
            with zipfile.ZipFile(output / "LibreVision-notices.zip") as archive:
                self.assertIn("NOTICE", archive.namelist())
                self.assertIn("LICENSE", archive.namelist())
                self.assertNotIn("app/src/main/assets/model.onnx", archive.namelist())
            self.assertEqual(
                URL, json.loads((output / "build-info.json").read_text())["worker_url"]
            )
            self.assertEqual(3, len((output / "SHA256SUMS").read_text().splitlines()))
            self.assertIn(
                "No gateway setup or API key is needed",
                (output / "release-notes.txt").read_text(),
            )


if __name__ == "__main__":
    unittest.main()
