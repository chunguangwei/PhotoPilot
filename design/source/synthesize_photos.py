import json
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'foundations' / 'photos'
OUT.mkdir(parents=True, exist_ok=True)

def landscape(name, w, h, seed, lake):
    rng = random.Random(seed)
    im = Image.new('RGB', (w,h))
    d = ImageDraw.Draw(im)
    sky = ((115,151,164),(236,222,192)) if lake else ((128,166,176),(241,220,170))
    horizon = int(h*0.56)
    for y in range(h):
        t=min(1,y/horizon)
        c=tuple(int(a*(1-t)+b*t) for a,b in zip(*sky))
        d.line((0,y,w,y),fill=c)
    glow=Image.new('RGBA',(w,h))
    gd=ImageDraw.Draw(glow)
    sunx,suny=int(w*.73),int(h*.23)
    gd.ellipse((sunx-65,suny-65,sunx+65,suny+65),fill=(255,241,204,70))
    im=Image.alpha_composite(im.convert('RGBA'),glow.filter(ImageFilter.GaussianBlur(32))).convert('RGB')
    d=ImageDraw.Draw(im)
    d.ellipse((sunx-13,suny-13,sunx+13,suny+13),fill=(252,242,215))
    for layer in range(5):
        base=h*(.38+layer*.045)
        amplitude=h*(.13-layer*.012)
        phase=rng.uniform(0,7)
        pts=[]
        for x in range(-20,w+21,8):
            yy=base-amplitude*(.52+.3*math.sin(x/w*7+phase)+.17*math.sin(x/w*19+phase*.8)+.07*math.sin(x/w*43))
            pts.append((x,int(yy)))
        color=[(155,171,166),(123,148,145),(96,127,120),(72,108,97),(48,84,71)][layer]
        d.polygon(pts+[(w,horizon+20),(0,horizon+20)],fill=color)
        if layer==1:
            for i in range(0,len(pts)-3,3):
                x,y=pts[i]
                if y < base-amplitude*.64:
                    d.polygon([(x,y),(x+18,y+22),(x+40,y+12),pts[i+3]],fill=(203,209,192))
    if lake:
        reflection=im.crop((0,int(h*.1),w,horizon)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        reflection=reflection.resize((w,h-horizon)).filter(ImageFilter.GaussianBlur(2))
        reflection=ImageEnhance.Color(reflection).enhance(.65)
        reflection=ImageEnhance.Brightness(reflection).enhance(.83)
        im.paste(reflection,(0,horizon))
        d=ImageDraw.Draw(im)
        for y in range(horizon,h):
            t=(y-horizon)/(h-horizon)
            if y%2==0:
                for j in range(5):
                    x=rng.randrange(w)
                    length=int(8+55*t)*rng.uniform(.4,1.5)
                    color=tuple(max(0,min(255,v+rng.randint(-12,18))) for v in im.getpixel((x,y)))
                    d.line((x,y,min(w,x+length),y),fill=color)
        d.polygon([(0,h*.76),(w*.12,h*.83),(w*.25,h*.95),(w*.34,h),(0,h)],fill=(33,60,43))
        d.polygon([(w,h*.67),(w*.88,h*.76),(w*.85,h*.82),(w*.94,h*.93),(w,h)],fill=(39,65,43))
    else:
        d.polygon([(0,h*.57),(w*.19,h*.54),(w*.5,h*.7),(w*.7,h*.6),(w,h*.68),(w,h),(0,h)],fill=(112,127,68))
        d.polygon([(0,h*.72),(w*.3,h*.66),(w*.7,h*.8),(w,h*.69),(w,h),(0,h)],fill=(83,107,53))
        d.polygon([(w*.56,h*.65),(w*.6,h*.68),(w*.48,h*.82),(w*.69,h),(w*.47,h),(w*.38,h*.83)],fill=(180,163,117))
    for n in range(1700):
        x=rng.randrange(w)
        y=rng.randint(int(h*.64),h-1)
        if lake and x>w*.25 and x<w*.88:
            continue
        color=(rng.randint(45,104),rng.randint(73,117),rng.randint(32,68))
        size=max(1,int((y/h-.5)*9))
        d.line((x,y,x+rng.randint(-3,3),y-size),fill=color,width=1)
    for side in [0,1]:
        for n in range(44):
            x=rng.randint(-20,int(w*.12)) if side==0 else rng.randint(int(w*.91),w+20)
            y=int(h*(.5+.1*rng.random()))
            tall=rng.randint(int(h*.06),int(h*.16))
            color=(33+rng.randrange(18),64+rng.randrange(15),48+rng.randrange(16))
            d.line((x,y,x,y-tall),fill=(51,58,40),width=2)
            for t in range(6):
                yy=y-tall+t*tall/7
                spread=(t+1)*tall/19
                d.polygon([(x,yy-12),(x-spread,yy+14),(x+spread,yy+14)],fill=color)
    im.save(OUT/(name+'.jpg'),quality=89,optimize=True,subsampling=0)
    blur=ImageEnhance.Brightness(im.resize((160,160)).filter(ImageFilter.GaussianBlur(12))).enhance(.78)
    blur.save(OUT/(name+'-blur.jpg'),quality=84,optimize=True)

landscape('lake',1200,800,72,True)
landscape('mountain',1200,800,193,False)
landscape('lake-portrait',720,1080,95,True)
manifest={'method':'Deterministic Pillow synthesis; no AI screenshot crops, no private or network photos','generator':'design/source/synthesize_photos.py','license':'Original project-generated illustrative assets; no external photography or identifiable people','date':'2026-10-04','use':'Design review only; not tests/fixtures','files':[{'file':p.name,'bytes':p.stat().st_size} for p in sorted(OUT.glob('*.jpg'))]}
(ROOT/'photo-provenance.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(f'Synthesized {len(manifest["files"])} shared photo resources')
