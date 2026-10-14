import argparse
import hashlib
import io
import json
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
EXPORT = Path("design/export/brand-v1.2")
IOS = Path("ios/PhotoPilot/Resources/Assets.xcassets")
ANDROID = Path("android/app/src/main/res")
COMMANDS = (
    ("M", 164, 56), ("H", 84), ("Q", 56, 56, 56, 84),
    ("V", 172), ("Q", 56, 200, 84, 200), ("H", 172),
    ("Q", 200, 200, 200, 172), ("V", 112),
)
PATH = " ".join(" ".join(map(str, command)) for command in COMMANDS)
STROKE = 14
DOT = (200, 56, 16)
COLORS = {"canvas": "#F4F1EB", "ink": "#202321", "gold": "#B89A68", "dark_gold": "#CFB380"}
OPTICAL_BOUNDS = (49, 33, 216, 207)
SHIFT = (128 - (OPTICAL_BOUNDS[0] + OPTICAL_BOUNDS[2]) / 2,
         128 - (OPTICAL_BOUNDS[1] + OPTICAL_BOUNDS[3]) / 2)
ADAPTIVE_SCALE = 72 / 256
ADAPTIVE_OFFSET = tuple(18 + value * ADAPTIVE_SCALE for value in SHIFT)
NS = "{http://schemas.android.com/apk/res/android}"
INFO = {"author": "xcode", "version": 1}
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192, "nodpi": 1024}


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def png_bytes(image):
    output = io.BytesIO()
    image.save(output, "PNG", optimize=False)
    return output.getvalue()


def centerline():
    points = []
    for command, *values in COMMANDS:
        if command == "M":
            point = tuple(values)
            points.append(point)
        elif command == "H":
            point = (values[0], point[1])
            points.append(point)
        elif command == "V":
            point = (point[0], values[0])
            points.append(point)
        elif command == "Q":
            start = point
            control, end = values[:2], values[2:]
            for index in range(1, 129):
                t = index / 128
                point = tuple((1 - t) ** 2 * start[axis] + 2 * (1 - t) * t * control[axis] + t ** 2 * end[axis] for axis in (0, 1))
                points.append(point)
        else:
            raise ValueError(command)
    return points


def circle_points(cx, cy, radius, count=256):
    return [(cx + radius * math.cos(i * math.tau / count), cy + radius * math.sin(i * math.tau / count)) for i in range(count)]


def frame_outline():
    points = centerline()
    left, right = [], []
    for i, point in enumerate(points):
        previous = points[max(0, i - 1)]
        following = points[min(len(points) - 1, i + 1)]
        dx, dy = following[0] - previous[0], following[1] - previous[1]
        length = math.hypot(dx, dy)
        nx, ny = -dy / length * STROKE / 2, dx / length * STROKE / 2
        left.append((point[0] + nx, point[1] + ny))
        right.append((point[0] - nx, point[1] - ny))
    return left + right[::-1]


def symbol_shapes():
    points = centerline()
    return [frame_outline(), circle_points(*points[0], STROKE / 2), circle_points(*points[-1], STROKE / 2), circle_points(*DOT)]


def raster(size, dark=False, monochrome=False, icon=False, adaptive=False):
    work = max(1024, size * 4)
    image = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    ink = COLORS["canvas" if dark else "ink"]
    gold = ink if monochrome else COLORS["dark_gold" if dark else "gold"]
    viewport = 108 if adaptive else 256
    scale = ADAPTIVE_SCALE if adaptive else 1
    offset = ADAPTIVE_OFFSET if adaptive else SHIFT if icon else (0, 0)
    for index, shape in enumerate(symbol_shapes()):
        transformed = [((x * scale + offset[0]) * work / viewport, (y * scale + offset[1]) * work / viewport) for x, y in shape]
        draw.polygon(transformed, fill=gold if index == 3 else ink)
    image = image.resize((size, size), Image.Resampling.LANCZOS)
    if icon:
        background = Image.new("RGBA", image.size, COLORS["canvas"])
        background.alpha_composite(image)
        return background.convert("RGB")
    return image


def svg(dark=False, monochrome=False, icon=False):
    ink = COLORS["canvas" if dark else "ink"]
    gold = ink if monochrome else COLORS["dark_gold" if dark else "gold"]
    content = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256">']
    if icon:
        content += [f'  <rect width="256" height="256" fill="{COLORS["canvas"]}"/>', f'  <g transform="translate({SHIFT[0]:g} {SHIFT[1]:g})">']
    content += [f'  <path d="{PATH}" fill="none" stroke="{ink}" stroke-width="{STROKE}" stroke-linecap="round" stroke-linejoin="round"/>', f'  <circle cx="{DOT[0]}" cy="{DOT[1]}" r="{DOT[2]}" fill="{gold}"/>']
    if icon:
        content.append("  </g>")
    return ("\n".join(content + ["</svg>"]) + "\n").encode()


def vector(adaptive=False, monochrome=False, dark=False):
    viewport = 108 if adaptive else 256
    ink = "#000000" if monochrome else COLORS["canvas" if dark else "ink"]
    gold = ink if monochrome else COLORS["dark_gold" if dark else "gold"]
    cx, cy, radius = DOT
    dot_path = f"M{cx - radius} {cy} A{radius} {radius} 0 1 0 {cx + radius} {cy} A{radius} {radius} 0 1 0 {cx - radius} {cy} Z"
    intrinsic = 108 if adaptive else 128
    lines = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{intrinsic}dp" android:height="{intrinsic}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">']
    if adaptive:
        lines.append(f'  <group android:scaleX="{ADAPTIVE_SCALE}" android:scaleY="{ADAPTIVE_SCALE}" android:translateX="{ADAPTIVE_OFFSET[0]}" android:translateY="{ADAPTIVE_OFFSET[1]}">')
    lines += [f'  <path android:pathData="{PATH}" android:fillColor="#00000000" android:strokeColor="{ink}" android:strokeWidth="{STROKE}" android:strokeLineCap="round" android:strokeLineJoin="round"/>', f'  <path android:pathData="{dot_path}" android:fillColor="{gold}"/>']
    if adaptive:
        lines.append("  </group>")
    return ("\n".join(lines + ["</vector>"]) + "\n").encode()


def adaptive_xml(monochrome=False):
    lines = ['<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">', '  <background android:drawable="@drawable/pp_launcher_background"/>', '  <foreground android:drawable="@drawable/pp_launcher_foreground"/>']
    if monochrome:
        lines.append('  <monochrome android:drawable="@drawable/pp_launcher_monochrome"/>')
    return ("\n".join(lines + ["</adaptive-icon>"]) + "\n").encode()


def appicon_slots():
    slots = []
    for idiom, points, scales in (("iphone", (20, 29, 40, 60), (2, 3)), ("ipad", (20, 29, 40, 76), (1, 2)), ("ipad", (83.5,), (2,)), ("ios-marketing", (1024,), (1,))):
        for point in points:
            for scale in scales:
                slots.append({"idiom": idiom, "size": f"{point:g}x{point:g}", "scale": f"{scale}x", "filename": f"app-icon-{int(point * scale)}.png"})
    return slots


def launch_static(dark=False):
    background = COLORS["ink" if dark else "canvas"]
    image = Image.new("RGB", (720, 960), background)
    mark = raster(160, dark=dark)
    image.paste(mark, (280, 400), mark)
    return image


def preview():
    image = Image.new("RGB", (1600, 1200), COLORS["canvas"])
    draw = ImageDraw.Draw(image)

    def label(x, y, text, size=18, color=None):
        draw.text((x, y), text, font=ImageFont.load_default(size=size), fill=color or COLORS["ink"])

    label(64, 44, "PhotoPilot", 42)
    label(64, 106, "BRAND 1.2 / ORIGINAL VECTOR / FRAME + LIGHT", 16)
    label(1030, 66, "Design preview - not an app screenshot", 16)
    draw.line((64, 151, 1536, 151), fill=COLORS["gold"], width=2)
    draw.rectangle((832, 190, 1536, 490), fill=COLORS["ink"])
    label(88, 209, "01 / LIGHT", 16)
    label(856, 209, "02 / DARK", 16, COLORS["canvas"])
    for x, dark in ((305, False), (1073, True)):
        mark = raster(230, dark=dark)
        image.paste(mark, (x, 250), mark)
    label(64, 534, "03 / SIZE STUDY", 16)
    x = 76
    for size in (24, 32, 48, 64, 96, 128):
        mark = raster(size)
        image.paste(mark, (x, 602 + (128 - size) // 2), mark)
        label(x, 756, str(size), 16)
        x += size + 37
    label(868, 534, "04 / APP ICON + ADAPTIVE MASK", 16)
    icon = raster(160, icon=True)
    image.paste(icon, (884, 588))
    draw.rectangle((884, 588, 1043, 747), outline=COLORS["gold"])
    adaptive = raster(240, adaptive=True)
    tile = Image.new("RGBA", (240, 240), COLORS["canvas"])
    tile.alpha_composite(adaptive)
    crop = tile.crop((40, 40, 200, 200))
    mask = Image.new("L", (160, 160))
    ImageDraw.Draw(mask).ellipse((0, 0, 159, 159), fill=255)
    draw.rectangle((1144, 572, 1335, 763), fill=COLORS["ink"])
    image.paste(crop, (1160, 588), mask)
    label(884, 777, "iOS / legacy", 16)
    label(1154, 777, "Android / circle", 16)
    label(64, 841, "05 / LAUNCH STATIC - SYMBOL ONLY", 16)
    for x, dark in ((64, False), (432, True)):
        tile = launch_static(dark).resize((216, 288), Image.Resampling.LANCZOS)
        image.paste(tile, (x, 879))
        draw.rectangle((x, 879, x + 215, 1166), outline=COLORS["gold"])
    label(868, 865, "WARM WHITE / INK / CHAMPAGNE", 16)
    for index, key in enumerate(("canvas", "ink", "gold", "dark_gold")):
        x = 868 + index * 165
        draw.rectangle((x, 925, x + 131, 989), fill=COLORS[key], outline=COLORS["gold"])
        label(x, 1010, COLORS[key], 16)
    label(868, 1090, "No island curve. No glow. No wordmark in launch assets.", 17)
    label(868, 1122, "Vector geometry is shared by every platform export.", 17)
    return image


def generate():
    files = {}
    for name, kwargs in (("logo-primary", {}), ("logo-dark", {"dark": True}), ("logo-monochrome", {"monochrome": True}), ("app-icon-master", {"icon": True})):
        files[EXPORT / f"{name}.svg"] = svg(**kwargs)
        files[EXPORT / f"{name}.png"] = png_bytes(raster(1024, **kwargs))
    files[EXPORT / "brand-preview.png"] = png_bytes(preview())
    for dark in (False, True):
        files[EXPORT / f"launch-static-{'dark' if dark else 'light'}.png"] = png_bytes(launch_static(dark))
    slots = appicon_slots()
    files[IOS / "AppIcon.appiconset/Contents.json"] = json_bytes({"images": slots, "info": INFO})
    for slot in slots:
        size = int(float(slot["size"].split("x")[0]) * int(slot["scale"][0]))
        files[IOS / "AppIcon.appiconset" / slot["filename"]] = png_bytes(raster(size, icon=True))
    launch = []
    for dark in (False, True):
        for scale in (1, 2, 3):
            name = f"launch-{'dark' if dark else 'light'}@{scale}x.png"
            slot = {"idiom": "universal", "filename": name, "scale": f"{scale}x"}
            if dark:
                slot["appearances"] = [{"appearance": "luminosity", "value": "dark"}]
            launch.append(slot)
            files[IOS / "LaunchMark.imageset" / name] = png_bytes(raster(128 * scale, dark=dark))
    files[IOS / "LaunchMark.imageset/Contents.json"] = json_bytes({"images": launch, "info": INFO})
    for density, size in DENSITIES.items():
        files[ANDROID / f"mipmap-{density}/ic_launcher.png"] = png_bytes(raster(size, icon=True))
    for folder, dark in (("drawable", False), ("drawable-night", True)):
        files[ANDROID / folder / "pp_brand_mark.xml"] = vector(dark=dark)
    files[ANDROID / "drawable/pp_launcher_foreground.xml"] = vector(adaptive=True)
    files[ANDROID / "drawable/pp_launcher_monochrome.xml"] = vector(adaptive=True, monochrome=True)
    files[ANDROID / "drawable/pp_launcher_background.xml"] = (f'<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n  <solid android:color="{COLORS["canvas"]}"/>\n</shape>\n').encode()
    for version in (26, 33):
        files[ANDROID / f"mipmap-anydpi-v{version}/ic_launcher.xml"] = adaptive_xml(version >= 33)
    inventory = {
        "version": "1.2", "display_name": "PhotoPilot",
        "source": "tools/generate_brand_assets.py",
        "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "origin": "Original user-confirmed frame-and-light vector; no external artwork, fonts or generation API.",
        "license": "Project root LICENSE applies; non-commercial use only.",
        "rendering": "Pillow polygonal stroke outline derived from the same M/H/Q/V geometry; 128 samples per quadratic, 256 per circle; minimum 1024px supersampling and Lanczos downsampling. Preview text uses Pillow's bundled default font.",
        "geometry": {"viewBox": [0, 0, 256, 256], "path": PATH, "stroke": STROKE, "cap": "round", "join": "round", "circle": DOT, "colors": COLORS, "actual_visible_bounds": [49, 40, 216, 207], "user_optical_bounds": OPTICAL_BOUNDS, "icon_translation": SHIFT, "bounds_note": "The supplied optical top 33 differs from the exact circle top 40. Geometry is unchanged; optical bounds drive icon centering only. Transparent marks retain the original viewport."},
        "adaptive": {"viewport_dp": 108, "nominal_mask_dp": 72, "safe_circle_diameter_dp": 66, "scale": ADAPTIVE_SCALE, "translation": ADAPTIVE_OFFSET, "normalized_alignment": "(adaptive_coordinate - 18) / 72 == icon_coordinate / 256", "monochrome": "Opaque black geometry, transparent exterior, tinted by Android 13+ launcher."},
        "handoff": {"ios_appicon_name": "AppIcon", "ios_launch_image_name": "LaunchMark", "launch_point_size": [128, 128], "integration_status": "Assets only; project.yml, Info.plist and pp_launch_screen.xml are not edited.", "preview_status": "Design-only static composites, not runtime screenshots or acceptance evidence."},
        "assets": {str(path): {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)} for path, data in sorted(files.items())},
    }
    files[EXPORT / "asset-inventory.json"] = json_bytes(inventory)
    return files


def validate(files):
    png_count = 0
    for path, data in files.items():
        if not data:
            raise ValueError(f"Empty asset: {path}")
        if path.suffix in (".svg", ".xml"):
            root = ET.fromstring(data)
            if path.suffix == ".svg":
                assert root.attrib["viewBox"] == "0 0 256 256"
                assert root.find(".//{http://www.w3.org/2000/svg}path").attrib["d"] == PATH
            if root.tag == "vector":
                assert root.find(".//path").attrib[NS + "pathData"] == PATH
                assert root.find(".//path").attrib[NS + "strokeWidth"] == str(STROKE)
                assert root.find(".//path").attrib[NS + "strokeLineCap"] == "round"
                assert root.find(".//path").attrib[NS + "strokeLineJoin"] == "round"
                if root.attrib[NS + "viewportWidth"] == "108":
                    group = root.find("group")
                    assert float(group.attrib[NS + "scaleX"]) == ADAPTIVE_SCALE
                    assert float(group.attrib[NS + "translateX"]) == ADAPTIVE_OFFSET[0]
                    assert float(group.attrib[NS + "translateY"]) == ADAPTIVE_OFFSET[1]
            if root.tag == "adaptive-icon":
                for element in root:
                    ref = element.attrib[NS + "drawable"].removeprefix("@drawable/")
                    assert ANDROID / "drawable" / f"{ref}.xml" in files
                assert (root.find("monochrome") is not None) == ("mipmap-anydpi-v33" in path.parts)
        elif path.suffix == ".json":
            metadata = json.loads(data)
            for slot in metadata.get("images", []):
                image = Image.open(io.BytesIO(files[path.parent / slot["filename"]]))
                scale = int(slot["scale"][0])
                point_size = float(slot.get("size", "128x128").split("x")[0])
                assert image.size == (int(point_size * scale),) * 2
        elif path.suffix == ".png":
            with Image.open(io.BytesIO(data)) as image:
                image.load()
                assert min(image.size) > 0
                assert any(low != high for low, high in image.convert("RGB").getextrema())
                opaque = "AppIcon.appiconset" in path.parts or path.name in ("ic_launcher.png", "app-icon-master.png")
                if opaque:
                    assert image.mode == "RGB" and "transparency" not in image.info
                    assert image.getpixel((0, 0)) == (244, 241, 235)
                elif "LaunchMark.imageset" in path.parts or path.stem.startswith("logo-"):
                    assert image.mode == "RGBA" and image.getchannel("A").getextrema() == (0, 255)
                    assert image.getpixel((0, 0))[3] == 0
                if path.parent == EXPORT:
                    expected = (1600, 1200) if path.name == "brand-preview.png" else (720, 960) if path.stem.startswith("launch-static") else (1024, 1024)
                    assert image.size == expected
                if path.name == "ic_launcher.png":
                    assert image.size == (DENSITIES[path.parent.name.removeprefix("mipmap-")],) * 2
            png_count += 1
    points = [point for shape in symbol_shapes() for point in shape]
    radius = max(math.hypot(x * ADAPTIVE_SCALE + ADAPTIVE_OFFSET[0] - 54, y * ADAPTIVE_SCALE + ADAPTIVE_OFFSET[1] - 54) for x, y in points)
    assert radius < 33
    for x, y in points:
        for axis, value in enumerate((x, y)):
            assert math.isclose((value * ADAPTIVE_SCALE + ADAPTIVE_OFFSET[axis] - 18) / 72, (value + SHIFT[axis]) / 256, abs_tol=1e-12)
    assert len(appicon_slots()) == 18
    assert files[EXPORT / "app-icon-master.png"] == files[IOS / "AppIcon.appiconset/app-icon-1024.png"] == files[ANDROID / "mipmap-nodpi/ic_launcher.png"]
    light = Image.open(io.BytesIO(files[EXPORT / "logo-primary.png"]))
    dark = Image.open(io.BytesIO(files[EXPORT / "logo-dark.png"]))
    assert ImageChops.difference(light.getchannel("A"), dark.getchannel("A")).getbbox() is None
    return {"files": len(files), "png_files": png_count, "ios_icon_slots": 18, "adaptive_max_radius_dp": round(radius, 6), "safe_radius_dp": 33, "icon_shift": SHIFT}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Regenerate in memory and compare all expected files without writing.")
    parser.add_argument("--verify-repeat", action="store_true", help="Render twice and require byte-identical output.")
    args = parser.parse_args()
    files = generate()
    result = validate(files)
    if args.verify_repeat:
        if generate() != files:
            raise ValueError("Generation is not byte deterministic")
        result["repeat_byte_identical"] = True
    if args.check:
        mismatches = [str(path) for path, data in files.items() if not (ROOT / path).is_file() or (ROOT / path).read_bytes() != data]
        if mismatches:
            print("Missing or stale assets:\n" + "\n".join(mismatches), file=sys.stderr)
            return 1
        result["disk_matches_generator"] = True
    else:
        for path, data in files.items():
            target = ROOT / path
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists() or target.read_bytes() != data:
                target.write_bytes(data)
        result["generated"] = True
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
