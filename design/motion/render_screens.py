from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
from render_motion import FONT_EN, FONT_ZH, mark, render

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "design" / "screens"
BG = "#F4F1EB"
INK = "#202321"
MUTED = "#62675F"
LINE = "#DDDCD4"
GOLD = "#B89A68"
PANEL = "#FCFAF6"


def font(size, lang):
    return ImageFont.truetype(FONT_ZH if lang == "zh" else FONT_EN, size)


def txt(im, xy, value, size=24, color=INK, lang="en"):
    ImageDraw.Draw(im).text(xy, value, font=font(size, lang), fill=color)


def rr(im, box, fill, radius=18, outline=None):
    ImageDraw.Draw(im).rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=1)


def text(im, xy, zh, en, lang, size=24, color=INK):
    txt(im, xy, zh if lang == "zh" else en, size, color, lang)


def button(im, box, zh, en, lang, dark=True):
    rr(im, box, INK if dark else "#E8E5DD", 18)
    label = zh if lang == "zh" else en
    f = font(23, lang)
    d = ImageDraw.Draw(im)
    b = d.textbbox((0, 0), label, font=f)
    d.text(((box[0] + box[2] - b[2]) / 2, (box[1] + box[3] - b[3]) / 2 - 1), label, font=f, fill=BG if dark else INK)


def photo(im, asset, box, radius=18, centering=(0.5, 0.5)):
    size = (box[2] - box[0], box[3] - box[1])
    image = ImageOps.fit(asset, size, method=Image.Resampling.LANCZOS, centering=centering)
    mask = Image.new("L", size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0], size[1]), radius=radius, fill=255)
    im.paste(image, box[:2], mask)


def icon(im, x, y, name, color=INK):
    d = ImageDraw.Draw(im)
    if name == "play":
        d.polygon([(x, y), (x, y + 22), (x + 18, y + 11)], fill=color)
    elif name == "pause":
        d.rounded_rectangle((x, y, x + 5, y + 23), radius=2, fill=color)
        d.rounded_rectangle((x + 12, y, x + 17, y + 23), radius=2, fill=color)
    elif name in ("previous", "next"):
        if name == "previous":
            d.polygon([(x + 17, y), (x + 17, y + 22), (x + 2, y + 11)], fill=color)
            d.line((x, y, x, y + 22), fill=color, width=3)
        else:
            d.polygon([(x, y), (x, y + 22), (x + 15, y + 11)], fill=color)
            d.line((x + 18, y, x + 18, y + 22), fill=color, width=3)
    elif name == "more":
        for k in range(3):
            d.ellipse((x + k * 8, y + 8, x + k * 8 + 4, y + 12), fill=color)
    elif name == "check":
        d.line([(x, y + 9), (x + 7, y + 16), (x + 20, y)], fill=color, width=3)
    elif name == "settings":
        for k, offset in enumerate((7, 16, 10)):
            yy = y + 3 + k * 8
            d.line((x, yy, x + 23, yy), fill=color, width=2)
            d.ellipse((x + offset - 3, yy - 3, x + offset + 3, yy + 3), fill=BG, outline=color, width=2)
    else:
        d.rounded_rectangle((x, y, x + 23, y + 23), radius=5, outline=color, width=2)
        if name == "library":
            d.line((x + 11, y, x + 11, y + 23), fill=color, width=2)
            d.line((x, y + 11, x + 23, y + 11), fill=color, width=2)
        if name == "sources":
            d.line((x + 4, y + 8, x + 19, y + 8), fill=color, width=2)
            d.line((x + 4, y + 15, x + 15, y + 15), fill=color, width=2)


def sidebar(im, selected, lang):
    d = ImageDraw.Draw(im)
    d.line((250, 0, 250, im.height), fill=LINE, width=1)
    tile = Image.new("RGB", (256, 256), BG)
    mark(tile, (0, 0), 1)
    im.paste(tile.resize((60, 60), Image.Resampling.LANCZOS), (22, 34))
    txt(im, (82, 52), "PhotoPilot", 27)
    text(im, (42, 124), "你的日常画廊", "Your everyday gallery", lang, 16, MUTED)
    labels = [("相框", "Frames", "frames"), ("照片库", "Library", "library"), ("来源", "Sources", "sources"), ("设置", "Settings", "settings")]
    for i, (zh, en, key) in enumerate(labels):
        y = 210 + i * 76
        if selected == key:
            rr(im, (24, y - 8, 227, y + 50), "#E5E2D8", 16)
        icon(im, 43, y + 8, key)
        text(im, (88, y + 2), zh, en, lang, 25)
    text(im, (40, 814), "所有照片，留在本机。", "Private by design.", lang, 16, MUTED)


def home(asset, lang):
    im = Image.new("RGB", (1200, 900), BG)
    sidebar(im, "frames", lang)
    text(im, (294, 52), "相框", "Your frames", lang, 42)
    button(im, (967, 49, 1156, 104), "创建相框", "Create frame", lang)
    text(im, (295, 118), "让回忆，留在眼前。", "Keep your memories in view.", lang, 22, MUTED)
    photo(im, asset, (294, 183, 1156, 637), 23)
    shade = Image.new("RGBA", (862, 454))
    sd = ImageDraw.Draw(shade)
    for y in range(280, 454):
        sd.line((0, y, 862, y), fill=(10, 15, 13, int((y - 280) * 0.8)))
    layer = im.crop((294, 183, 1156, 637)).convert("RGBA")
    layer.alpha_composite(shade)
    photo(im, layer.convert("RGB"), (294, 183, 1156, 637), 23)
    text(im, (327, 529), "清晨湖畔", "Quiet mornings", lang, 32, "#FFFFFF")
    text(im, (328, 578), "128 张可离线播放", "128 photos available offline", lang, 20, "#F4F1EB")
    rr(im, (1050, 538, 1120, 608), "#E4E2D8", 35, "#FFFFFF")
    icon(im, 1078, 562, "play")
    text(im, (294, 683), "最近导入", "Latest import", lang, 27)
    text(im, (1002, 688), "查看照片库", "View library", lang, 20, MUTED)
    for i in range(3):
        photo(im, asset, (294 + i * 177, 745, 450 + i * 177, 851), 12, (i / 2, 0.5))
    text(im, (861, 769), "由你决定展示什么", "You choose what appears", lang, 18, MUTED)
    text(im, (861, 804), "导入后仍可单独选片", "Import first. Curate next.", lang, 17, MUTED)
    return im


def library(asset, lang):
    im = Image.new("RGB", (1200, 900), BG)
    sidebar(im, "library", lang)
    text(im, (294, 50), "选择展示内容", "Choose what to display", lang, 36)
    text(im, (295, 116), "家庭 NAS / 夏日旅行", "Family NAS / Summer", lang, 21, MUTED)
    rr(im, (294, 168, 580, 220), "#E8E5DD", 15)
    rr(im, (438, 173, 575, 215), PANEL, 12)
    text(im, (336, 179), "目录", "Folders", lang, 21)
    text(im, (480, 179), "照片", "Photos", lang, 21)
    text(im, (897, 182), "仅选择当前照片", "Current photos only", lang, 19, MUTED)
    for i in range(6):
        x = 294 + (i % 3) * 295
        y = 253 + (i // 3) * 223
        photo(im, asset, (x, y, x + 272, y + 193), 14, ((i % 3) / 2, (i // 3) * 0.3 + 0.35))
        if i in (0, 1, 3, 5):
            rr(im, (x + 229, y + 12, x + 260, y + 43), INK, 16)
            icon(im, x + 235, y + 19, "check", "#FFFFFF")
        else:
            ImageDraw.Draw(im).ellipse((x + 229, y + 12, x + 260, y + 43), fill="#E9E7DF", outline="#FFFFFF", width=2)
    rr(im, (294, 741, 1156, 851), "#E5E3DA", 22, "#FFFFFF")
    text(im, (321, 756), "已选 4 张", "4 photos selected", lang, 25)
    text(im, (322, 800), "4 张可播 · 新增照片不会自动展示", "4 ready · New photos are not included", lang, 19, MUTED)
    button(im, (936, 766, 1128, 825), "加入相框", "Add to frame", lang)
    return im


def player(asset, lang):
    portrait = ImageOps.fit(asset, (560, 900), Image.Resampling.LANCZOS, centering=(0.57, 0.5))
    im = ImageOps.fit(portrait, (1200, 900), Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(34))
    im = Image.blend(im, Image.new("RGB", im.size, INK), 0.16)
    im.paste(portrait, (320, 0))
    overlay = Image.new("RGBA", im.size)
    d = ImageDraw.Draw(overlay)
    for y in range(645, 900):
        d.line((0, y, 1200, y), fill=(8, 13, 10, int((y - 645) * 0.45)))
    im = Image.alpha_composite(im.convert("RGBA"), overlay).convert("RGB")
    txt(im, (46, 706), "09:41", 77, "#FFFFFF")
    text(im, (51, 807), "晴 · 22°C", "Sunny · 22°C", lang, 26, "#FFFFFF")
    rr(im, (443, 798, 759, 862), "#DADBD1", 32, "#F4F1EB")
    for i, key in enumerate(("previous", "pause", "next", "more")):
        icon(im, 476 + i * 73, 819, key)
    return im


def settings(asset, lang):
    im = Image.new("RGB", (900, 1200), BG)
    text(im, (50, 36), "‹ 返回", "‹ Back", lang, 23, MUTED)
    text(im, (50, 92), "播放设置", "Playback", lang, 43)
    photo(im, asset, (50, 177, 850, 416), 20)
    text(im, (52, 451), "照片停留时间", "Photo duration", lang, 27)
    text(im, (52, 493), "完整展示每张照片的时间", "Time each photo stays fully visible", lang, 20, MUTED)
    rr(im, (50, 540, 850, 603), "#E5E2D8", 17)
    labels = ["5s", "10s", "15s", "30s", "60s", "自定义" if lang == "zh" else "Custom"]
    for i, value in enumerate(labels):
        x = 56 + i * 132
        if i == 1:
            rr(im, (x, 546, x + 128, 597), PANEL, 12)
        txt(im, (x + (25 if i == 5 else 44), 558), value, 23, INK, lang)
    text(im, (52, 648), "转场", "Transition", lang, 27)
    text(im, (656, 654), "淡入淡出", "Crossfade", lang, 24, MUTED)
    text(im, (52, 713), "转场时长", "Transition duration", lang, 27)
    txt(im, (769, 719), "0.8s", 24, MUTED)
    ImageDraw.Draw(im).line((54, 783, 846, 783), fill=LINE, width=5)
    ImageDraw.Draw(im).line((54, 783, 265, 783), fill=GOLD, width=5)
    ImageDraw.Draw(im).ellipse((253, 771, 277, 795), fill=INK)
    text(im, (52, 835), "画面适配", "Photo fit", lang, 27)
    text(im, (609, 841), "完整展示 · 模糊补边", "Fit + blurred edges", lang, 22, MUTED)
    for y, zh, en, active in ((910, "时钟", "Clock", True), (982, "天气", "Weather", False)):
        text(im, (52, y), zh, en, lang, 27)
        rr(im, (772, y - 1, 848, y + 40), INK if active else "#D4D5CE", 21)
        x = 813 if active else 777
        ImageDraw.Draw(im).ellipse((x, y + 4, x + 31, y + 35), fill="#FFFFFF")
    button(im, (50, 1080, 850, 1146), "保存", "Save", lang)
    return im


def device(screen):
    w, h = screen.size
    out = Image.new("RGB", (w + 112, h + 112), "#EAE7E0")
    shadow = Image.new("RGBA", out.size)
    ImageDraw.Draw(shadow).rounded_rectangle((39, 46, w + 81, h + 88), radius=45, fill=(0, 0, 0, 45))
    shadow = shadow.filter(ImageFilter.GaussianBlur(12))
    out = Image.alpha_composite(out.convert("RGBA"), shadow).convert("RGB")
    rr(out, (39, 39, w + 73, h + 73), "#252927", 38, "#73766F")
    photo(out, screen, (56, 56, w + 56, h + 56), 24)
    return out


def main():
    source = Image.open(OUT / "01-home-landscape.png").convert("RGB")
    w, h = source.size
    asset = source.crop((round(w * 0.215), round(h * 0.185), round(w * 0.925), round(h * 0.605)))
    asset.save(OUT / "sample-lake-generated.jpg", quality=95)
    for lang in ("zh", "en"):
        for name, factory in (("01-home", home), ("02-library", library), ("03-player", player), ("04-settings", settings)):
            screen = factory(asset, lang)
            device(screen).save(OUT / f"{name}-{lang}.png")
        launch_frames = [render(i / 25, lang) for i in range(22)]
        target = ImageOps.fit(home(asset, lang), (1024, 768), Image.Resampling.LANCZOS)
        start = launch_frames[-1]
        launch_frames += [Image.blend(start, target, i / 8) for i in range(1, 9)]
        launch_frames[0].save(ROOT / "design" / "motion" / f"launch-to-app-{lang}.gif", save_all=True, append_images=launch_frames[1:], duration=[40] * (len(launch_frames) - 1) + [2200], loop=0, disposal=2)
    print("已生成 8 张双语精排页面及 2 个进入应用的动效演示")


if __name__ == "__main__":
    main()
