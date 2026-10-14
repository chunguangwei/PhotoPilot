from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from render_motion import BG, INK, GOLD, FONT_EN, FONT_ZH, mark

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "design" / "brand"


def text(draw, xy, value, size, color=INK, chinese=False):
    font = ImageFont.truetype(FONT_ZH if chinese else FONT_EN, size)
    draw.text(xy, value, fill=color, font=font)


def main():
    board = Image.new("RGB", (1800, 1200), BG)
    d = ImageDraw.Draw(board)
    d.line((72, 116, 1728, 116), fill=(209, 207, 197), width=2)
    text(d, (72, 49), "PhotoPilot", 32)
    text(d, (1364, 56), "IDENTITY / 01", 20, (102, 106, 100))
    mark(board, (92, 204), 1.75)
    text(d, (148, 690), "PhotoPilot", 76)
    text(d, (151, 802), "光屿", 35, chinese=True)
    text(d, (151, 940), "让回忆，留在眼前。", 25, (102, 106, 100), True)
    text(d, (151, 991), "Keep your memories in view.", 24, (102, 106, 100))
    d.line((813, 179, 813, 1068), fill=(209, 207, 197), width=2)
    text(d, (886, 185), "01  /  MARK", 22, (102, 106, 100))
    for x, fill, dark in ((896, BG, False), (1160, INK, True), (1424, (227, 223, 213), False)):
        d.rounded_rectangle((x, 250, x + 224, 474), radius=38, fill=fill)
        tile = Image.new("RGB", (256, 256), BG)
        mark(tile, (0, 0), 1)
        if dark:
            import PIL.ImageOps
            mask = tile.convert("L").point(lambda p: 255 if p < 215 else 0)
            foreground = Image.new("RGB", (256, 256), BG)
            tile = Image.composite(foreground, Image.new("RGB", (256, 256), INK), mask)
        elif fill != BG:
            data = tile.load()
            for py in range(256):
                for px in range(256):
                    if data[px, py] == BG:
                        data[px, py] = fill
        board.paste(tile.resize((224, 224), Image.Resampling.LANCZOS), (x, 250))
    text(d, (886, 540), "02  /  MATERIAL", 22, (102, 106, 100))
    colors = [(BG, "IVORY", "F4F1EB"), (INK, "INK", "202321"), (GOLD, "LIGHT", "B89A68")]
    for i, (color, label, value) in enumerate(colors):
        x = 896 + i * 264
        d.rounded_rectangle((x, 610, x + 224, 745), radius=16, fill=color, outline=(209, 207, 197), width=1)
        text(d, (x, 772), label, 20)
        text(d, (x, 808), value, 18, (102, 106, 100))
    text(d, (886, 911), "03  /  PRINCIPLE", 22, (102, 106, 100))
    text(d, (889, 967), "照片为主，界面退后。", 37, INK, True)
    text(d, (890, 1031), "Photos first. Everything else, quieter.", 24, (102, 106, 100))
    board.save(OUT / "brand-system.png")
    print("品牌母版展示板已生成")


if __name__ == "__main__":
    main()
