from pathlib import Path
import math
import re
import json
import hashlib
from html import escape
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
EXPORT = ROOT / 'design/export/design-assets-v1.1'
DATE = '2026-10-04'
TOKENS = {}
for line in (ROOT / '02-品牌与体验设计方案.md').read_text().splitlines():
    m = re.match(r'\| `([^`]+)` \| (#[0-9A-F]{6}) \| (#[0-9A-F]{6})', line)
    if m:
        TOKENS[m[1]] = {'light': m[2], 'dark': m[3]}
FONT_ZH = '/System/Library/AssetsV2/com_apple_MobileAsset_Font8/86ba2c91f017a3749571a82f2c6d890ac7ffb2fb.asset/AssetData/PingFang.ttc'
FONT_EN = '/System/Library/Fonts/HelveticaNeue.ttc'
TYPE = {'title': 28, 'heading': 22, 'body': 17, 'caption': 15, 'meta': 13, 'clock': 64}
LIMITS = ['Local design candidate, not native UI validation or M0 evidence.', 'Large type is 1.6x design stress, not the OS maximum.', 'PNG uses locally rendered macOS font fallback; Android system-font and native accessibility verification pending.', 'No fonts are embedded or distributed; store wordmark license pending.']

def rgb(v):
    return tuple(int(v[i:i+2], 16) for i in (1, 3, 5))

def mix(a, b, alpha):
    return tuple(round(x * (1-alpha) + y * alpha) for x, y in zip(a, b))

@lru_cache(maxsize=256)
def font(size, zh=False):
    return ImageFont.truetype(FONT_ZH if zh else FONT_EN, size)

def commands(d):
    a = re.findall(r'[MLHVQCZmlhvqcz]|[-+]?(?:\d*\.\d+|\d+)', d)
    out = []
    i = 0
    p = (0, 0)
    start = p
    while i < len(a):
        op = a[i]
        i += 1
        n = {'M':2, 'L':2, 'H':1, 'V':1, 'Q':4, 'C':6, 'Z':0}[op]
        v = list(map(float, a[i:i+n]))
        i += n
        if op == 'H':
            op, v = 'L', [v[0], p[1]]
        elif op == 'V':
            op, v = 'L', [p[0], v[0]]
        elif op == 'Q':
            q, e = v[:2], v[2:]
            v = [p[0]+2*(q[0]-p[0])/3, p[1]+2*(q[1]-p[1])/3, e[0]+2*(q[0]-e[0])/3, e[1]+2*(q[1]-e[1])/3, *e]
            op = 'C'
        if v:
            p = tuple(v[-2:])
        if op == 'M':
            start = p
        if op == 'Z':
            p = start
        out.append((op, v))
    return out

def transform(d, x=0, y=0, scale=1):
    return ' '.join(op + ' ' + ' '.join(f'{v * scale + (x if i%2==0 else y):.5f}' for i, v in enumerate(vals)) for op, vals in commands(d))

def roundrect(x, y, w, h, r=0):
    r = min(r, w/2, h/2)
    return f'M{x+r} {y} H{x+w-r} Q{x+w} {y} {x+w} {y+r} V{y+h-r} Q{x+w} {y+h} {x+w-r} {y+h} H{x+r} Q{x} {y+h} {x} {y+h-r} V{y+r} Q{x} {y} {x+r} {y} Z'

def circle(x, y, r):
    k = r * 0.5522847498
    return f'M{x+r} {y} C{x+r} {y+k} {x+k} {y+r} {x} {y+r} C{x-k} {y+r} {x-r} {y+k} {x-r} {y} C{x-r} {y-k} {x-k} {y-r} {x} {y-r} C{x+k} {y-r} {x+r} {y-k} {x+r} {y} Z'

class Scene:
    def __init__(self, w, h, theme='light', platform='ios', scale=2):
        self.w, self.h, self.theme, self.platform, self.scale = w, h, theme, platform, scale
        self.items = []
        self.issues = []

    def color(self, token):
        if token is None:
            return None
        return TOKENS[token][self.theme] if token in TOKENS else token

    def path(self, d, stroke=None, width=2, fill=None, opacity=1, name='geometry'):
        self.items.append(dict(kind='path', d=d, stroke=stroke, width=width, fill=fill, opacity=opacity, name=name))

    def rect(self, x, y, w, h, fill=None, r=0, stroke=None, width=1, opacity=1, name='surface'):
        self.path(roundrect(x,y,w,h,r), stroke,width,fill,opacity,name)

    def dot(self,x,y,r,fill=None,stroke=None,width=2,name='dot'):
        self.path(circle(x,y,r),stroke,width,fill,1,name)

    def text(self, x, y, value, style='body', color='text.primary', factor=1, maxw=None, name='label'):
        size = TYPE[style] * factor
        zh = any(ord(c)>0x2fff for c in value)
        f = font(round(size*self.scale),zh)
        if maxw and f.getlength(value)/self.scale > maxw + 0.5:
            self.issues.append({'type':'text_overflow','text':value,'width':f.getlength(value)/self.scale,'allowed':maxw})
        self.items.append(dict(kind='text', x=x,y=y,text=value,size=size,color=color,style=style,name=name))

    def wrap(self,x,y,value,maxw,style='body',color='text.primary',factor=1):
        size = TYPE[style]*factor
        zh = any(ord(c)>0x2fff for c in value)
        f = font(round(size*self.scale),zh)
        units = list(value) if zh else value.split(' ')
        lines, line = [], ''
        for unit in units:
            candidate = line + ('' if zh or not line else ' ') + unit
            if f.getlength(candidate)/self.scale > maxw and line:
                lines.append(line)
                line = unit
            else:
                line = candidate
        lines.append(line)
        for i, text in enumerate(lines):
            self.text(x,y+i*size*1.5,text,style,color,factor,maxw)
        return len(lines)*size*1.5

    def svg(self):
        defs = ';'.join(f'--{k.replace(".","-")}:{v[self.theme]}' for k,v in TOKENS.items())
        family = '-apple-system, BlinkMacSystemFont, PingFang SC, sans-serif' if self.platform=='ios' else 'Roboto, Noto Sans CJK SC, sans-serif'
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" style="{defs}"><title>PhotoPilot design candidate</title><desc>Editable paths and text. No native validation. Font files not embedded.</desc>']
        def col(c):
            return f'var(--{c.replace(".","-")})' if c in TOKENS else c or 'none'
        for i, item in enumerate(self.items):
            n = escape(item['name'],quote=True)
            if item['kind']=='path':
                out.append(f'<path id="layer-{i}" data-name="{n}" d="{item["d"]}" fill="{col(item["fill"])}" stroke="{col(item["stroke"])}" stroke-width="{item["width"]}" stroke-linecap="round" stroke-linejoin="round" opacity="{item["opacity"]}"/>')
            else:
                out.append(f'<text id="layer-{i}" data-name="{n}" data-type="type/{item["style"]}" x="{item["x"]}" y="{item["y"]}" dominant-baseline="text-before-edge" font-family="{family}" font-size="{item["size"]}" fill="{col(item["color"])}">{escape(item["text"])}</text>')
        return ''.join(out)+'</svg>'

    def image(self, scale=None):
        s = scale or self.scale
        im = Image.new('RGBA',(round(self.w*s),round(self.h*s)),(0,0,0,0))
        for item in self.items:
            translucent = item.get('opacity',1)!=1
            layer = Image.new('RGBA',im.size) if translucent else im
            draw = ImageDraw.Draw(layer)
            if item['kind']=='text':
                value=item['text']
                f=font(round(item['size']*s),any(ord(c)>0x2fff for c in value))
                draw.text((item['x']*s,item['y']*s),value,font=f,fill=self.color(item['color']),anchor='lt')
            else:
                parts=[]
                points=[]
                current=(0,0)
                closed=False
                for op, vals in commands(item['d']):
                    if op=='M':
                        if points:
                            parts.append((points,closed))
                        points=[tuple(vals)]
                        closed=False
                        current=tuple(vals)
                    elif op=='L':
                        current=tuple(vals)
                        points.append(current)
                    elif op=='C':
                        p0=current
                        p1,p2,p3=vals[:2],vals[2:4],vals[4:]
                        for j in range(1,25):
                            t=j/24
                            points.append(tuple((1-t)**3*p0[k]+3*(1-t)**2*t*p1[k]+3*(1-t)*t*t*p2[k]+t**3*p3[k] for k in range(2)))
                        current=tuple(p3)
                    else:
                        points.append(points[0])
                        closed=True
                if points:
                    parts.append((points,closed))
                for pts, closed in parts:
                    pts=[(x*s,y*s) for x,y in pts]
                    if item['fill']:
                        draw.polygon(pts,fill=self.color(item['fill']))
                    if item['stroke']:
                        c=self.color(item['stroke'])
                        width=item['width']*s
                        draw.line(pts,fill=c,width=max(1,round(width)),joint='curve')
                        r=width/2
                        for x,y in pts:
                            draw.ellipse((x-r,y-r,x+r,y+r),fill=c)
            if translucent:
                layer.putalpha(layer.getchannel('A').point(lambda a:round(a*item['opacity'])))
                im.alpha_composite(layer)
        if s!=1:
            im=im.resize((self.w,self.h),Image.Resampling.LANCZOS)
        return im

    def save(self, base):
        base.parent.mkdir(parents=True,exist_ok=True)
        base.with_suffix('.svg').write_text(self.svg())
        self.image().save(base.with_suffix('.png'))
        return {'svg':str(base.with_suffix('.svg').relative_to(ROOT)),'png':str(base.with_suffix('.png').relative_to(ROOT))}

def vector_pdf(scene,path):
    stream=[f'1 0 0 -1 0 {scene.h} cm','1 J 1 j']
    for p in scene.items:
        assert p['kind']=='path' and p['opacity']==1
        for key,op in [('fill','rg'),('stroke','RG')]:
            if p[key]:
                stream.append(' '.join(f'{v/255:.6f}' for v in rgb(scene.color(p[key])))+' '+op)
        stream.append(f'{p["width"]} w')
        for op,vals in commands(p['d']):
            stream.append(' '.join(f'{v:.5f}' for v in vals)+' '+{'M':'m','L':'l','C':'c','Z':'h'}[op])
        stream.append('B' if p['fill'] and p['stroke'] else 'f' if p['fill'] else 'S')
    data='\n'.join(stream).encode('ascii')
    objs=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {scene.w} {scene.h}] /Resources << >> /Contents 4 0 R >>'.encode(),b'<< /Length '+str(len(data)).encode()+b' >>\nstream\n'+data+b'\nendstream']
    out=bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets=[0]
    for i,obj in enumerate(objs,1):
        offsets.append(len(out))
        out.extend(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
    xref=len(out)
    out.extend(f'xref\n0 {len(objs)+1}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]:
        out.extend(f'{offset:010} 00000 n \n'.encode())
    out.extend(f'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(out)

def drawable(scene,path,template=False):
    out=[f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{scene.w}dp" android:height="{scene.h}dp" android:viewportWidth="{scene.w}" android:viewportHeight="{scene.h}">']
    for p in scene.items:
        assert p['kind']=='path'
        fill=scene.color(p['fill']) or '#00000000'
        stroke=scene.color(p['stroke']) or '#00000000'
        if template and p['stroke']:
            stroke='?android:attr/textColorPrimary'
        out.append(f'<path android:pathData="{p["d"]}" android:fillColor="{fill}" android:strokeColor="{stroke}" android:strokeWidth="{p["width"]}" android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(''.join(out)+'</vector>')

def dump(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
