import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def android_resources(path):
    tree = ET.parse(path)
    result = {}
    for element in tree.getroot().findall("string"):
        key = element.attrib["name"]
        if key in result:
            raise ValueError(f"重复词条：{path}:{key}")
        result[key] = "".join(element.itertext())
    return result


def apple_resources(path):
    text = path.read_text(encoding="utf-8")
    pairs = re.findall(r'^\s*"([^"\n]+)"\s*=\s*"((?:\\.|[^"\\])*)"\s*;\s*$', text, re.MULTILINE)
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"重复词条：{path}:{key}")
        result[key] = value
    if not pairs:
        raise ValueError(f"未找到词条：{path}")
    return result


def compare_keys(first, second):
    return sorted(set(first) ^ set(second))


PHOTO_READ_PERMISSIONS = {
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.READ_EXTERNAL_STORAGE",
}
NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
}
NAS_NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}
LOCATION_PERMISSIONS = {
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
}
UPDATE_PERMISSIONS = {
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.POST_NOTIFICATIONS",
}
IOS_PHOTO_USAGE_KEYS = {"NSPhotoLibraryUsageDescription"}
IOS_NAS_USAGE_KEYS = {"NSPhotoLibraryUsageDescription", "NSLocalNetworkUsageDescription"}
IOS_WEATHER_USAGE_KEYS = {"NSLocationWhenInUseUsageDescription"}
NAS_STAGES = {"m2_nas_development"}
SERVICES_STAGES = {"m2_services_development"}


def manifest_issues(path, stage="scaffold_only"):
    root = ET.parse(path).getroot()
    errors = []
    for element in root:
        if not element.tag.startswith("uses-permission"):
            continue
        name = element.get(ANDROID + "name", "")
        if name in NETWORK_PERMISSIONS:
            if not (stage in NAS_STAGES | SERVICES_STAGES and name in NAS_NETWORK_PERMISSIONS):
                errors.append(f"当前阶段不允许网络权限：{name}")
        elif stage in ("m1_development", "m2_nas_development", "m2_services_development") and name in PHOTO_READ_PERMISSIONS:
            if name == "android.permission.READ_EXTERNAL_STORAGE":
                max_sdk = element.get(ANDROID + "maxSdkVersion")
                if max_sdk is None or int(max_sdk) > 32:
                    errors.append("READ_EXTERNAL_STORAGE 必须声明 maxSdkVersion≤32")
        elif stage in SERVICES_STAGES and name in LOCATION_PERMISSIONS:
            pass
        elif stage in SERVICES_STAGES and name in UPDATE_PERMISSIONS:
            pass
        else:
            errors.append(f"当前阶段不允许声明权限：{name}")
    app = root.find("application")
    if app is None:
        return errors + ["缺少 application"]
    if app.get(ANDROID + "usesCleartextTraffic") != "false":
        errors.append("脚手架必须关闭明文流量")
    if app.get(ANDROID + "allowBackup") != "false":
        errors.append("脚手架必须关闭备份")
    for activity in app.findall("activity"):
        if activity.get(ANDROID + "screenOrientation"):
            errors.append("不得锁定屏幕方向")
    return errors


def stage_issues(config):
    errors = []
    stage = config["stage"]
    authorized = config["businessImplementationAuthorized"]
    if stage == "scaffold_only":
        if authorized:
            errors.append("脚手架阶段不得声明业务实现已授权")
    elif stage == "m1_development":
        if not authorized:
            errors.append("M1 开发阶段必须声明业务实现已授权")
        if config["networkEnabled"] or config["thirdPartyServicesEnabled"]:
            errors.append("M1 为本地切片，不允许启用网络或第三方服务")
    elif stage in NAS_STAGES:
        if not authorized:
            errors.append("NAS 开发阶段必须声明业务实现已授权")
        if not config["networkEnabled"]:
            errors.append("NAS 开发阶段必须启用局域网网络能力")
        if config["thirdPartyServicesEnabled"]:
            errors.append("NAS 阶段不允许启用第三方服务")
    elif stage in SERVICES_STAGES:
        if not authorized:
            errors.append("服务开发阶段必须声明业务实现已授权")
        if not config["networkEnabled"] or not config["thirdPartyServicesEnabled"]:
            errors.append("天气服务阶段必须启用网络与第三方服务标记")
    else:
        errors.append(f"未知阶段：{stage}")
    return errors


def check(root=ROOT):
    errors = []
    config = json.loads((root / "contracts/scaffold.json").read_text(encoding="utf-8"))
    errors.extend(stage_issues(config))
    if config["minimumPlatforms"] != {"iPadOS": "15.0", "androidApi": 29}:
        errors.append("最低平台偏离需求")
    for relative in ("ios/project.yml", "ios/PhotoPilot.xcodeproj/project.pbxproj", "android/settings.gradle.kts", "android/app/build.gradle.kts", "android/gradlew", "android/gradle/wrapper/gradle-wrapper.jar"):
        if not (root / relative).is_file():
            errors.append(f"缺少工程文件：{relative}")
    manifest = root / "android/app/src/main/AndroidManifest.xml"
    errors.extend(manifest_issues(manifest, stage=config["stage"]))
    res = root / "android/app/src/main/res"
    english = android_resources(res / "values/strings.xml")
    chinese = android_resources(res / "values-zh-rCN/strings.xml")
    if compare_keys(english, chinese):
        errors.append("Android 中英词条键不一致")
    apple_english = list((root / "ios").glob("**/en.lproj/Localizable.strings"))
    apple_chinese = list((root / "ios").glob("**/zh-Hans.lproj/Localizable.strings"))
    if len(apple_english) != 1 or len(apple_chinese) != 1:
        errors.append("iOS 应存在唯一中英词条资源")
    elif compare_keys(apple_resources(apple_english[0]), apple_resources(apple_chinese[0])):
        errors.append("iOS 中英词条键不一致")
    for path in (root / "ios/PhotoPilot").glob("**/*.plist"):
        with path.open("rb") as stream:
            data = plistlib.load(stream)
        for key in data:
            if not (key.startswith("NS") and key.endswith("UsageDescription")):
                continue
            weather_allowed = config["stage"] in SERVICES_STAGES and key in IOS_WEATHER_USAGE_KEYS
            if not ((config["stage"] == "m1_development" and key in IOS_PHOTO_USAGE_KEYS) or ((config["stage"] in NAS_STAGES or config["stage"] in SERVICES_STAGES) and key in IOS_NAS_USAGE_KEYS) or weather_allowed):
                errors.append(f"当前阶段不允许申请敏感权限：{path.relative_to(root)}:{key}")
        if data.get("NSAppTransportSecurity", {}).get("NSAllowsArbitraryLoads"):
            errors.append("不得全局放宽 ATS")
    return errors


def main():
    try:
        errors = check()
    except (OSError, ValueError, KeyError, ET.ParseError, plistlib.InvalidFileException) as error:
        print(f"脚手架检查失败：{error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("脚手架静态检查通过；不代表业务验收、真机或 M0 通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
