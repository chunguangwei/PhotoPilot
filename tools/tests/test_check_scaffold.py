import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_scaffold import android_resources, apple_resources, compare_keys, manifest_issues, stage_issues


class ScaffoldChecksTests(unittest.TestCase):
    def test_key_mismatch_is_reported(self):
        self.assertEqual(compare_keys({"a": "A"}, {"b": "B"}), ["a", "b"])

    def test_keys_ignore_translation_content(self):
        self.assertEqual(compare_keys({"a": "甲"}, {"a": "A"}), [])

    def test_duplicate_android_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_text('<resources><string name="a">A</string><string name="a">B</string></resources>')
            with self.assertRaises(ValueError):
                android_resources(path)

    def test_duplicate_apple_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Localizable.strings"
            path.write_text('"a" = "A";\n"a" = "B";\n')
            with self.assertRaises(ValueError):
                apple_resources(path)

    def test_manifest_rejects_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.CAMERA"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许声明权限：android.permission.CAMERA", manifest_issues(path))

    def test_network_permission_rejected_even_in_m1(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许网络权限：android.permission.INTERNET", manifest_issues(path, stage="m1_development"))

    def test_nas_stage_allows_internet_and_network_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertEqual(manifest_issues(path, stage="m2_nas_development"), [])

    def test_nas_stage_still_rejects_wifi_state_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许网络权限：android.permission.CHANGE_WIFI_STATE", manifest_issues(path, stage="m2_nas_development"))

    def test_nas_stage_requires_network_enabled(self):
        config = {"stage": "m2_nas_development", "businessImplementationAuthorized": True, "networkEnabled": False, "thirdPartyServicesEnabled": False}
        self.assertIn("NAS 开发阶段必须启用局域网网络能力", stage_issues(config))

    def test_nas_stage_rejects_third_party_services(self):
        config = {"stage": "m2_nas_development", "businessImplementationAuthorized": True, "networkEnabled": True, "thirdPartyServicesEnabled": True}
        self.assertIn("NAS 阶段不允许启用第三方服务", stage_issues(config))

    def test_nas_stage_accepts_authorized_config(self):
        config = {"stage": "m2_nas_development", "businessImplementationAuthorized": True, "networkEnabled": True, "thirdPartyServicesEnabled": False}
        self.assertEqual(stage_issues(config), [])

    def test_services_stage_accepts_third_party_config(self):
        config = {"stage": "m2_services_development", "businessImplementationAuthorized": True, "networkEnabled": True, "thirdPartyServicesEnabled": True}
        self.assertEqual(stage_issues(config), [])

    def test_services_stage_requires_third_party_flag(self):
        config = {"stage": "m2_services_development", "businessImplementationAuthorized": True, "networkEnabled": True, "thirdPartyServicesEnabled": False}
        self.assertIn("天气服务阶段必须启用网络与第三方服务标记", stage_issues(config))

    def test_photo_read_permissions_allowed_in_m1(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/><uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/><uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertEqual(manifest_issues(path, stage="m1_development"), [])

    def test_photo_read_permissions_rejected_in_scaffold_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许声明权限：android.permission.READ_MEDIA_IMAGES", manifest_issues(path, stage="scaffold_only"))

    def test_external_storage_requires_max_sdk_32(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("READ_EXTERNAL_STORAGE 必须声明 maxSdkVersion≤32", manifest_issues(path, stage="m1_development"))

    def test_minimal_manifest_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertEqual(manifest_issues(path), [])

    def test_m1_stage_requires_authorization(self):
        config = {"stage": "m1_development", "businessImplementationAuthorized": True, "networkEnabled": False, "thirdPartyServicesEnabled": False}
        self.assertEqual(stage_issues(config), [])

    def test_scaffold_stage_rejects_authorization(self):
        config = {"stage": "scaffold_only", "businessImplementationAuthorized": True, "networkEnabled": False, "thirdPartyServicesEnabled": False}
        self.assertIn("脚手架阶段不得声明业务实现已授权", stage_issues(config))

    def test_unknown_stage_is_rejected(self):
        config = {"stage": "m2_development", "businessImplementationAuthorized": True, "networkEnabled": False, "thirdPartyServicesEnabled": False}
        self.assertEqual(stage_issues(config), ["未知阶段：m2_development"])

    def test_network_still_forbidden_in_m1(self):
        config = {"stage": "m1_development", "businessImplementationAuthorized": True, "networkEnabled": True, "thirdPartyServicesEnabled": False}
        self.assertIn("M1 为本地切片，不允许启用网络或第三方服务", stage_issues(config))

    def test_location_permissions_allowed_in_services_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/><uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertEqual(manifest_issues(path, stage="m2_services_development"), [])

    def test_location_permissions_rejected_in_earlier_stages(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许声明权限：android.permission.ACCESS_FINE_LOCATION", manifest_issues(path, stage="scaffold_only"))
            self.assertIn("当前阶段不允许声明权限：android.permission.ACCESS_FINE_LOCATION", manifest_issues(path, stage="m1_development"))
            self.assertIn("当前阶段不允许声明权限：android.permission.ACCESS_FINE_LOCATION", manifest_issues(path, stage="m2_nas_development"))

    def test_update_permissions_allowed_in_services_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/><uses-permission android:name="android.permission.WAKE_LOCK"/><uses-permission android:name="android.permission.POST_NOTIFICATIONS"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertEqual(manifest_issues(path, stage="m2_services_development"), [])

    def test_update_permissions_rejected_in_earlier_stages(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>')
            self.assertIn("当前阶段不允许声明权限：android.permission.REQUEST_INSTALL_PACKAGES", manifest_issues(path, stage="scaffold_only"))
            self.assertIn("当前阶段不允许声明权限：android.permission.REQUEST_INSTALL_PACKAGES", manifest_issues(path, stage="m1_development"))
            self.assertIn("当前阶段不允许声明权限：android.permission.REQUEST_INSTALL_PACKAGES", manifest_issues(path, stage="m2_nas_development"))


if __name__ == "__main__":
    unittest.main()
