from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "design" / "motion"
BRAND = ROOT / "design" / "brand"
FONT_EN = "/System/Library/Fonts/HelveticaNeue.ttc"
FONT_ZH = "/System/Library/AssetsV2/com_apple_MobileAsset_Font8/86ba2c91f017a3749571a82f2c6d890ac7ffb2fb.asset/AssetData/PingFang.ttc"
BG = (244, 241, 235)
INK = (32, 35, 33)
GOLD = (184, 154, 104)


def bezier(points, steps=30):
    n = len(points) - 1
    return [tuple(sum(math.comb(n, k) * (1 - t) ** (n - k) * t ** k * points[k][j] for k in range(n + 1)) for j in range(2)) for t in [i / steps for i in range(steps + 1)]]


def frame_path():
    p = [(148, 60), (84, 60)]
    p += bezier([(84, 60), (60, 60), (60, 84)])
    p += [(60, 172)]
    p += bezier([(60, 172), (60, 196), (84, 196)])
    p += [(172, 196)]
    p += bezier([(172, 196), (196, 196), (196, 172)])
    p += [(196, 105)]
    return p


def partial_path(points, fraction):
    if fraction <= 0:
        return []
    total = sum(math.dist(a, b) for a, b in zip(points, points[1:]))
    remaining = total * min(1, fraction)
    result = [points[0]]
    for a, b in zip(points, points[1:]):
        length = math.dist(a, b)
        if length <= remaining:
            result.append(b)
            remaining -= length
        else:
            result.append(tuple(a[j] + (b[j] - a[j]) * remaining / length for j in range(2)))
            break
    return result


def stroke(draw, points, color, width):
    if len(points) < 2:
        return
    draw.line(points, fill=color, width=round(width), joint="curve")
    r = width / 2
    for x, y in points:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def mix(a, b, amount):
    amount = max(0, min(1, amount))
    return tuple(round(a[i] + (b[i] - a[i]) * amount) for i in range(3))


def mark(image, origin, scale, reveal=1, sun=1, horizon=1):
    draw = ImageDraw.Draw(image)
    transform = lambda p: (origin[0] + p[0] * scale, origin[1] + p[1] * scale)
    stroke(draw, [transform(p) for p in partial_path(frame_path(), reveal)], INK, 14 * scale)
    x, y = transform((182, 63))
    r = 12 * scale
    draw.ellipse((x - r, y - r, x + r, y + r), fill=mix(BG, GOLD, sun))
    h = bezier([(88, 160), (109, 140), (128, 145), (143, 160)])
    h += bezier([(143, 160), (155, 154), (167, 154), (176, 160)])
    stroke(draw, [transform(p) for p in h], mix(BG, INK, horizon), 10 * scale)


def label(image, text, y, size, color, font_path=FONT_EN):
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(font_path, size)
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(((image.width - box[2] + box[0]) / 2, y), text, fill=color, font=font)


def render(t, language="zh"):
    image = Image.new("RGB", (1536, 1152), BG)
    reveal = min(1, max(0, (t - 0.08) / 0.48))
    reveal = 1 - (1 - reveal) ** 3
    mark(image, (576, 264), 1.5, reveal, 1, (t - 0.28) / 0.28)
    alpha = (t - 0.38) / 0.24
    label(image, "PhotoPilot", 692, 63, mix(BG, INK, alpha))
    if language == "zh":
        label(image, "光屿", 785, 31, mix(BG, (102, 106, 100), alpha), FONT_ZH)
        message = "让回忆，留在眼前。"
    else:
        message = "Keep your memories in view."
    label(image, message, 1008, 25, mix(BG, (102, 106, 100), alpha), FONT_ZH if language == "zh" else FONT_EN)
    return image.resize((1024, 768), Image.Resampling.LANCZOS)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    BRAND.mkdir(parents=True, exist_ok=True)
    for language in ("zh", "en"):
        frames = [render(i / 25, language) for i in range(31)]
        frames[0].save(OUT / f"launch-static-{language}.png")
        render(1.2, language).save(OUT / f"launch-ready-{language}.png")
        durations = [40] * len(frames)
        durations[-1] = 1200
        frames[0].save(OUT / f"launch-reveal-{language}.gif", save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=False, disposal=2)
    icon = Image.new("RGB", (2048, 2048), BG)
    mark(icon, (0, -16), 8)
    icon.resize((1024, 1024), Image.Resampling.LANCZOS).save(BRAND / "app-icon-1024.png")
    board = Image.new("RGB", (2048, 840), BG)
    draw = ImageDraw.Draw(board)
    font = ImageFont.truetype(FONT_EN, 25)
    for i, (t, text) in enumerate(((0.12, "120 ms / Light"), (0.36, "360 ms / Frame"), (0.64, "640 ms / Identity"), (1.2, "Ready / Route to app"))):
        tile = render(t, "en").resize((480, 360), Image.Resampling.LANCZOS)
        board.paste(tile, (20 + i * 512, 145))
        draw.text((40 + i * 512, 550), text, fill=INK, font=font)
    draw.text((40, 55), "PhotoPilot / Launch motion study", fill=INK, font=ImageFont.truetype(FONT_EN, 38))
    draw.text((40, 680), "Concept timing only. Never delay ready content for branding.", fill=(102, 106, 100), font=font)
    board.save(OUT / "launch-storyboard.png")
    print("Generated: 2 launch GIFs, 2 static launch PNGs, storyboard, app icon")


if __name__ == "__main__":
    main()
