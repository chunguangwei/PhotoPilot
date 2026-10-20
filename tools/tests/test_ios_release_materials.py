import plistlib
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class IOSReleaseMaterialsTests(unittest.TestCase):
    def test_encryption_exemption_is_not_assumed_before_classification(self):
        info = plistlib.loads((ROOT / "ios/PhotoPilot/Resources/Info.plist").read_bytes())
        self.assertNotIn("ITSAppUsesNonExemptEncryption", info)

    def test_launch_elapsed_timer_has_required_reason(self):
        source = (ROOT / "ios/PhotoPilot/UI/Launch/LaunchViewController.swift").read_text()
        self.assertIn("systemUptime", source)
        manifest = plistlib.loads((ROOT / "ios/PhotoPilot/Resources/PrivacyInfo.xcprivacy").read_bytes())
        reasons = {
            entry["NSPrivacyAccessedAPIType"]: entry["NSPrivacyAccessedAPITypeReasons"]
            for entry in manifest["NSPrivacyAccessedAPITypes"]
        }
        self.assertIn("35F9.1", reasons.get("NSPrivacyAccessedAPICategorySystemBootTime", []))

    def test_review_sample_archive_is_not_ignored(self):
        archive = ROOT / "store/appstore/review/ReviewPhotos.zip"
        self.assertTrue(archive.is_file())
        result = subprocess.run(
            ["git", "check-ignore", "--no-index", str(archive)],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
