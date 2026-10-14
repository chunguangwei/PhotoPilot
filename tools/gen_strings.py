import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts" / "strings" / "strings.json"
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
GENERATED_MARK = "由 contracts/strings 生成，勿手改"

ANDROID_EN = "android/app/src/main/res/values/strings.xml"
ANDROID_ZH = "android/app/src/main/res/values-zh-rCN/strings.xml"
IOS_STRINGS = {
    "en": "ios/PhotoPilot/Resources/en.lproj/Localizable.strings",
    "zh": "ios/PhotoPilot/Resources/zh-Hans.lproj/Localizable.strings",
}
IOS_STRINGSDICT = {
    "en": "ios/PhotoPilot/Resources/en.lproj/Localizable.stringsdict",
    "zh": "ios/PhotoPilot/Resources/zh-Hans.lproj/Localizable.stringsdict",
}
IOS_INFOPLIST = {
    "en": "ios/PhotoPilot/Resources/en.lproj/InfoPlist.strings",
    "zh": "ios/PhotoPilot/Resources/zh-Hans.lproj/InfoPlist.strings",
}
INFOPLIST_KEYS = {
    "NSPhotoLibraryUsageDescription": "permission.photos.usage",
    "NSLocalNetworkUsageDescription": "permission.local_network.usage.ios",
    "NSLocationWhenInUseUsageDescription": "permission.location.usage",
}


def xml_escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def strings_escape(text):
    return text.replace("\\", "\\\\").replace('"', '\\"')


IOS_SPEC = {"int": "d", "decimal": "f", "string": "@"}
ANDROID_SPEC = {"int": "d", "decimal": "f", "string": "s"}


def ordered_placeholders(entry):
    if entry.get("plural"):
        reference = entry["en"]["other"] + " " + entry["en"]["one"]
    else:
        reference = entry["en"]
    return list(dict.fromkeys(PLACEHOLDER.findall(reference)))


def convert_placeholders(text, entry, spec):
    order = ordered_placeholders(entry)
    types = entry.get("placeholders", {})
    if not types:
        return text

    def repl(match):
        name = match.group(1)
        return f"%{order.index(name) + 1}${spec[types[name]]}"

    converted = PLACEHOLDER.sub(repl, text)
    return re.sub(r"%(?!\d+\$[a-zA-Z@])", "%%", converted)


def load_entries(path=SOURCE):
    data = json.loads(path.read_text(encoding="utf-8"))
    entries = data["entries"]
    seen = set()
    for entry in entries:
        key = entry["key"]
        if key in seen:
            raise ValueError(f"重复词条 key：{key}")
        seen.add(key)
        zh_ph = set(PLACEHOLDER.findall(entry["zh"]))
        if entry.get("plural"):
            forms = entry["en"]
            if set(forms) != {"one", "other"}:
                raise ValueError(f"复数条目缺少 one/other：{key}")
            en_ph = set(PLACEHOLDER.findall(forms["one"])) | set(PLACEHOLDER.findall(forms["other"]))
        else:
            en_ph = set(PLACEHOLDER.findall(entry["en"]))
        if zh_ph != en_ph:
            raise ValueError(f"中英占位符集合不一致：{key}")
        declared = set(entry.get("placeholders", {}))
        if declared != zh_ph:
            raise ValueError(f"占位符类型标注与实际占位符不一致：{key}")
        for name, kind in entry.get("placeholders", {}).items():
            if kind not in ("int", "decimal", "string"):
                raise ValueError(f"未知占位符类型：{key}:{name}:{kind}")
        if entry.get("plural"):
            variable = entry["plural_variable"]
            if entry["placeholders"].get(variable) != "int":
                raise ValueError(f"复数分支键必须是 int 占位符：{key}:{variable}")
    return entries


def platform_entries(entries, platform):
    return [e for e in entries if e["platform"] in ("all", platform)]


def render_android(entries, locale):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<resources>",
        f"    <!-- {GENERATED_MARK} -->",
    ]
    for entry in entries:
        if entry.get("plural"):
            lines.append(f'    <plurals name="{entry["key"]}">')
            if locale == "en":
                quantities = (("one", entry["en"]["one"]), ("other", entry["en"]["other"]))
            else:
                quantities = (("other", entry["zh"]),)
            for quantity, text in quantities:
                lines.append(f'        <item quantity="{quantity}">{xml_escape(convert_placeholders(text, entry, ANDROID_SPEC))}</item>')
            lines.append("    </plurals>")
        else:
            text = entry["en"] if locale == "en" else entry["zh"]
            lines.append(f'    <string name="{entry["key"]}">{xml_escape(convert_placeholders(text, entry, ANDROID_SPEC))}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def render_ios_strings(entries, locale):
    lines = [f"/* {GENERATED_MARK} */"]
    for entry in entries:
        if entry.get("plural"):
            continue
        text = entry["en"] if locale == "en" else entry["zh"]
        lines.append(f'"{strings_escape(entry["key"])}" = "{strings_escape(convert_placeholders(text, entry, IOS_SPEC))}";')
    return "\n".join(lines) + "\n"


def render_ios_stringsdict(entries, locale):
    value_types = {"int": "d", "decimal": "f", "string": "@"}
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0">',
        "<dict>",
        f"    <!-- {GENERATED_MARK} -->",
    ]
    for entry in entries:
        if not entry.get("plural"):
            continue
        variable = entry["plural_variable"]
        lines.append(f"    <key>{entry['key']}</key>")
        lines.append("    <dict>")
        lines.append("        <key>NSStringLocalizedFormatKey</key>")
        lines.append(f"        <string>%#@{variable}@</string>")
        lines.append(f"        <key>{variable}</key>")
        lines.append("        <dict>")
        lines.append("            <key>NSStringFormatSpecTypeKey</key>")
        lines.append("            <string>NSStringPluralRuleType</string>")
        lines.append("            <key>NSStringFormatValueTypeKey</key>")
        lines.append(f"            <string>{value_types[entry['placeholders'][variable]]}</string>")
        if locale == "en":
            quantities = (("one", entry["en"]["one"]), ("other", entry["en"]["other"]))
        else:
            quantities = (("other", entry["zh"]),)
        for quantity, text in quantities:
            lines.append(f"            <key>{quantity}</key>")
            lines.append(f'            <string>{xml_escape(convert_placeholders(text, entry, IOS_SPEC))}</string>')
        lines.append("        </dict>")
        lines.append("    </dict>")
    lines.append("</dict>")
    lines.append("</plist>")
    return "\n".join(lines) + "\n"


def render_ios_infoplist(entries, locale):
    by_key = {entry["key"]: entry for entry in entries}
    lines = [f"/* {GENERATED_MARK} */"]
    for plist_key in sorted(INFOPLIST_KEYS):
        string_key = INFOPLIST_KEYS[plist_key]
        if string_key not in by_key:
            raise ValueError(f"InfoPlist 映射的词条不存在：{plist_key} -> {string_key}")
        entry = by_key[string_key]
        if entry.get("plural"):
            raise ValueError(f"InfoPlist 词条不得为复数条目：{string_key}")
        text = entry["en"] if locale == "en" else entry["zh"]
        lines.append(f'"{plist_key}" = "{strings_escape(text)}";')
    return "\n".join(lines) + "\n"


def render_all(entries=None):
    entries = load_entries() if entries is None else entries
    android_en = platform_entries(entries, "android")
    ios = platform_entries(entries, "ios")
    return {
        ANDROID_EN: render_android(android_en, "en"),
        ANDROID_ZH: render_android(android_en, "zh"),
        IOS_STRINGSDICT["en"]: render_ios_stringsdict(ios, "en"),
        IOS_STRINGSDICT["zh"]: render_ios_stringsdict(ios, "zh"),
        IOS_STRINGS["en"]: render_ios_strings(ios, "en"),
        IOS_STRINGS["zh"]: render_ios_strings(ios, "zh"),
        IOS_INFOPLIST["en"]: render_ios_infoplist(ios, "en"),
        IOS_INFOPLIST["zh"]: render_ios_infoplist(ios, "zh"),
    }


def main():
    entries = load_entries()
    outputs = render_all(entries)
    for relative, content in outputs.items():
        path = ROOT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    plural_count = sum(1 for e in entries if e.get("plural"))
    print(f"已生成 {len(outputs)} 份产物；词条 {len(entries)} 条，其中复数 {plural_count} 条。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
