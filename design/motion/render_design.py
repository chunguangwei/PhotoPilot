import argparse
import copy
import hashlib
import json
import math
import os
import re
import shutil
from collections import Counter
from functools import lru_cache
from pathlib import Path
from xml.sax.saxutils import escape
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT/'design/source'
EXPORT = ROOT/'design/export/design-assets-v1.1'
TOKENS = json.loads((SOURCE/'tokens.json').read_text())
CATALOG = json.loads((SOURCE/'screens.json').read_text())
ENTRIES = {e['key']:e for e in json.loads((ROOT/'contracts/strings/strings.json').read_text())['entries']}
ENTRIES.update({e['key']:e for e in json.loads((SOURCE/'copy-additions.json').read_text())['entries']})
FONT_ZH = '/System/Library/AssetsV2/com_apple_MobileAsset_Font8/86ba2c91f017a3749571a82f2c6d890ac7ffb2fb.asset/AssetData/PingFang.ttc'
FONT_EN = '/System/Library/Fonts/HelveticaNeue.ttc'
MANIFEST = []
ISSUES = []

@lru_cache(maxsize=128)
def font(size, chinese=False):
    return ImageFont.truetype(FONT_ZH if chinese else FONT_EN,round(size))

def resolve(key,platform):
    return key.get(platform) if isinstance(key,dict) else key

def localize(key, locale, params=None, platform='ipad'):
    key=resolve(key,platform)
    if key is None:
        return '',None
    params=params or {}
    e=ENTRIES[key]
    value=e[locale]
    if isinstance(value,dict):
        var=e.get('plural_variable','count')
        value=value['one' if params.get(var,2)==1 else 'other']
    return value.format(**params),key

def lines_for(value,size,width):
    f=font(size,any(ord(c)>255 for c in value))
    tokens=re.findall(r'[\u2e80-\uffff]|[^\s\u2e80-\uffff]+|[ \t]+|\n',value)
    lines=[]
    line=''
    for token in tokens:
        if token=='\n':
            lines.append(line.rstrip())
            line=''
            continue
        if f.getlength(line+token)<=width:
            line+=token
        else:
            if line.strip():
                lines.append(line.rstrip())
                line=''
            token=token.lstrip()
            if f.getlength(token)>width:
                for char in token:
                    if f.getlength(line+char)>width:
                        lines.append(line)
                        line=''
                    line+=char
            else:
                line=token
    if line.strip():
        lines.append(line.rstrip())
    return lines or [''],f

def size_for(style,scale=1):
    return round(TOKENS['typography']['type/'+style]['size']*scale)

class Canvas:
    def __init__(self,w,h,theme='light',scale=1,platform='ipad',locale='zh',variant='base'):
        self.w,self.h,self.theme,self.scale,self.platform,self.locale,self.variant=w,h,theme,scale,platform,locale,variant
        self.im=Image.new('RGB',(w,h),self.color('surface.canvas'))
        self.d=ImageDraw.Draw(self.im)
        self.svg=[]
        self.keys=set()
        self.tokens=set()
        self.texts=[]
        self.touches=[]
        self.assets=set()
        self.rect(0,0,w,h,'surface.canvas')
    def color(self,token):
        if token in TOKENS['colors']:
            if self.variant=='high-contrast' and token=='separator':
                token='text.secondary'
            return TOKENS['colors'][token][self.theme]
        if token=='surface.rest':
            return '#000000'
        if token=='text.onPhoto':
            return TOKENS['colors']['surface.canvas']['light']
        if token=='surface.photoControl':
            return TOKENS['colors']['surface.canvas']['dark']
        if token=='text.onAction':
            return TOKENS['colors']['surface.canvas'][self.theme]
        if token=='focus.ring':
            return self.color('accent.action')
        raise KeyError(token)
    def rect(self,x,y,w,h,token,r=0,stroke=None,sw=1,opacity=1):
        self.tokens.add(token)
        color=self.color(token)
        bounds=(round(x),round(y),round(x+w),round(y+h))
        if opacity<1:
            layer=Image.new('RGBA',self.im.size)
            ld=ImageDraw.Draw(layer)
            ld.rounded_rectangle(bounds,radius=r,fill=(*tuple(bytes.fromhex(color[1:])),round(opacity*255)))
            self.im=Image.alpha_composite(self.im.convert('RGBA'),layer).convert('RGB')
            self.d=ImageDraw.Draw(self.im)
        else:
            self.d.rounded_rectangle(bounds,radius=r,fill=color)
        extra=''
        if stroke:
            self.tokens.add(stroke)
            self.d.rounded_rectangle(bounds,radius=r,outline=self.color(stroke),width=sw)
            extra=f' stroke="{self.color(stroke)}" stroke-width="{sw}" data-stroke-token="{stroke}"'
        self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{color}" fill-opacity="{opacity}" data-token="{token}"{extra}/>')
    def ellipse(self,x,y,w,h,token):
        self.tokens.add(token)
        self.d.ellipse((x,y,x+w,y+h),fill=self.color(token))
        self.svg.append(f'<ellipse cx="{x+w/2}" cy="{y+h/2}" rx="{w/2}" ry="{h/2}" fill="{self.color(token)}" data-token="{token}"/>')
    def path(self,points,token='text.primary',width=2,closed=False):
        self.tokens.add(token)
        pts=[(round(x,2),round(y,2)) for x,y in points]
        if closed:
            self.d.polygon(pts,fill=self.color(token))
        else:
            self.d.line(pts,fill=self.color(token),width=max(1,round(width)),joint='curve')
        ds='M'+' L'.join(f'{x},{y}' for x,y in pts)+(' Z' if closed else '')
        self.svg.append(f'<path d="{ds}" fill="{self.color(token) if closed else "none"}" stroke="{self.color(token)}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round" data-token="{token}"/>')
    def icon(self,name,x,y,s=24,token='text.primary'):
        points={'frames':[(2,3),(22,3),(22,21),(2,21),(2,3)],'library':[(2,5),(10,5),(10,12),(2,12),(2,5)],'sources':[(3,5),(21,5),(21,19),(3,19),(3,5)],'settings':[(4,5),(20,5)],'folder':[(2,7),(2,4),(10,4),(13,7),(22,7),(22,21),(2,21),(2,7)],'server':[(3,3),(21,3),(21,21),(3,21),(3,3)],'photos':[(2,3),(22,3),(22,21),(2,21),(2,3)],'cloud':[(3,18),(1,13),(5,9),(9,9),(11,4),(17,4),(20,10),(23,13),(21,18),(3,18)],'back':[(15,3),(6,12),(15,21)],'check':[(4,12),(10,18),(21,5)],'next':[(5,4),(17,12),(5,20)],'previous':[(18,4),(6,12),(18,20)],'play':[(6,4),(19,12),(6,20),(6,4)],'pause':[(8,4),(8,20)],'more':[(4,12),(4,12.5)]}.get(name,[(3,3),(21,21),(3,21),(21,3)])
        self.path([(x+a*s/24,y+b*s/24) for a,b in points],token,2)
        if name=='pause':
            self.path([(x+16*s/24,y+4*s/24),(x+16*s/24,y+20*s/24)],token,2)
        if name=='more':
            for a in [4,11,18]:
                self.ellipse(x+a,y+11,3,3,token)
        if name=='settings':
            for yy in [12,19]:
                self.path([(x+4,y+yy),(x+20,y+yy)],token)
            self.ellipse(x+7,y+2,6,6,token)
            self.ellipse(x+14,y+9,6,6,token)
            self.ellipse(x+5,y+16,6,6,token)
        if name=='library':
            for xx,yy in [(14,5),(2,16),(14,16)]:
                self.rect(x+xx,y+yy,7,6,token,1)
    def logo(self,x,y,s=128,dotOnly=False):
        k=s/256
        self.ellipse(x+170*k,y+51*k,24*k,24*k,'accent.brand')
        if dotOnly:
            return
        def curve(p0,p1,p2,p3,n=16):
            return [((1-t)**3*p0[0]+3*(1-t)**2*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0],(1-t)**3*p0[1]+3*(1-t)**2*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(n+1)]]
        pts=[(148,60),(84,60)]+curve((84,60),(68,60),(60,68),(60,84))+[(60,172)]+curve((60,172),(60,188),(68,196),(84,196))+[(172,196)]+curve((172,196),(188,196),(196,188),(196,172))+[(196,105)]
        self.path([(x+a*k,y+b*k) for a,b in pts],'text.primary',14*k)
        pts=curve((88,160),(109,140),(128,145),(143,160))+curve((143,160),(155,154),(167,154),(176,160))
        self.path([(x+a*k,y+b*k) for a,b in pts],'text.primary',10*k)
    def text(self,key,x,y,width,style='body',token='text.primary',params=None,center=False,scale=None):
        value,key=localize(key,self.locale,params,self.platform)
        return self.label(value,x,y,width,style,token,key,center,scale)
    def label(self,value,x,y,width,style='meta',token='text.secondary',key=None,center=False,scale=None):
        size=size_for(style,self.scale if scale is None else scale)
        lines,f=lines_for(value,size,width)
        lh=round(size*TOKENS['typography']['type/'+style]['lineHeight'])
        self.tokens.update([token,'type/'+style])
        if key:
            self.keys.add(key)
        family='PingFang SC' if any(ord(c)>255 for c in value) else 'Helvetica Neue'
        for i,line in enumerate(lines):
            xx=x+(width-f.getlength(line))/2 if center else x
            yy=y+i*lh
            self.d.text((round(xx),round(yy+size*.14)),line,font=f,fill=self.color(token),anchor='lt')
            attrs=f' data-string-key="{key}"' if key else ' data-annotation="true"'
            self.svg.append(f'<text x="{xx:.2f}" y="{yy+size:.2f}" font-family="{family}, sans-serif" font-size="{size}" fill="{self.color(token)}" data-token="{token}" data-type-token="type/{style}"{attrs}>{escape(line)}</text>')
            box=[round(xx,2),round(yy,2),round(f.getlength(line),2),lh]
            if line:
                self.texts.append({'key':key,'box':box,'line':line})
                if xx < -0.1 or xx+f.getlength(line)>self.w+.1 or yy<0 or yy+lh>self.h+.1:
                    ISSUES.append({'kind':'text-outside-canvas','key':key,'box':box,'canvas':[self.w,self.h]})
        return len(lines)*lh
    def picture(self,name,x,y,w,h,mode='fit_blur'):
        path=SOURCE/'foundations/photos'/f'{name}.jpg'
        im=photo(name)
        if mode=='fit_blur':
            self.picture(name+'-blur',x,y,w,h,'fill_crop')
        elif mode=='fit_color':
            edge=im.resize((max(1,im.width//8),max(1,im.height//8)))
            pixels=[edge.getpixel((x,y)) for x in range(edge.width) for y in [0,edge.height-1]]+[edge.getpixel((x,y)) for y in range(edge.height) for x in [0,edge.width-1]]
            color=tuple(round(sum(p[j] for p in pixels)/len(pixels)) for j in range(3))
            self.d.rectangle((x,y,x+w,y+h),fill=color)
            self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="#{color[0]:02x}{color[1]:02x}{color[2]:02x}" data-token="media.edgeColor" data-derived-from="{name}"/>')
            self.tokens.add('media.edgeColor')
        if mode in ['fit_blur','fit_color','contain']:
            ww,hh=ImageOps.contain(im,(round(w),round(h))).size
            xx,yy=round(x+(w-ww)/2),round(y+(h-hh)/2)
            tile=im.resize((ww,hh),Image.Resampling.LANCZOS)
            self.im.paste(tile,(xx,yy))
            self.svg.append(f'<image x="{xx}" y="{yy}" width="{ww}" height="{hh}" href="@@ASSET:{name}.jpg@@" preserveAspectRatio="xMidYMid meet" data-media-mode="aspect-fit"/>')
        else:
            tile=ImageOps.fit(im,(round(w),round(h)),method=Image.Resampling.LANCZOS)
            self.im.paste(tile,(round(x),round(y)))
            self.svg.append(f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="0 0 {w} {h}" overflow="hidden"><image x="0" y="0" width="{w}" height="{h}" href="@@ASSET:{name}.jpg@@" preserveAspectRatio="xMidYMid slice"/></svg>')
        self.assets.add(name+'.jpg')
    def save(self,category,stem,metadata):
        for base in [SOURCE,EXPORT]:
            directory=base/category
            directory.mkdir(parents=True,exist_ok=True)
            svg=''.join(self.svg)
            for asset in self.assets:
                href=os.path.relpath(base/'foundations/photos'/asset,directory)
                svg=svg.replace('@@ASSET:'+asset+'@@',href)
            annotation=escape(json.dumps(metadata.get('annotations',{}),ensure_ascii=False))
            viewportHeight=metadata.get('height',self.h)
            content=f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{self.w}" height="{viewportHeight}" viewBox="0 0 {self.w} {viewportHeight}" data-design-version="1.1"><title>{escape(stem)}</title>{svg}<g id="Annotations" display="none"><desc>{annotation}</desc><rect x="32" y="24" width="{self.w-64}" height="{viewportHeight-48}" fill="none" stroke="{self.color("accent.action")}" data-token="accent.action"/></g></svg>'
            (directory/(stem+'.svg')).write_text(content)
            if metadata.get('scope')=='conditional-full' or metadata.get('page') in ['S06b','S06c']:
                (directory/(stem+'--annotated.svg')).write_text(content.replace(f'height="{viewportHeight}" viewBox="0 0 {self.w} {viewportHeight}"',f'height="{self.h}" viewBox="0 0 {self.w} {self.h}"',1))
        self.im.crop((0,0,self.w,metadata.get('height',self.h))).save(EXPORT/category/(stem+'.png'),compress_level=8)
        if metadata.get('scope')=='conditional-full' or metadata.get('page') in ['S06b','S06c']:
            self.im.save(EXPORT/category/(stem+'--annotated.png'),compress_level=8)
        overlaps=[]
        for i,a in enumerate(self.texts):
            x,y,w,h=a['box']
            for b in self.texts[i+1:]:
                bx,by,bw,bh=b['box']
                if min(x+w,bx+bw)-max(x,bx)>2 and min(y+h,by+bh)-max(y,by)>2:
                    overlaps.append([a['key'],b['key'],a['box'],b['box']])
        metadata.update({'paths':{'sourceSvg':str((SOURCE/category/(stem+'.svg')).relative_to(ROOT)),'svg':str((EXPORT/category/(stem+'.svg')).relative_to(ROOT)),'png':str((EXPORT/category/(stem+'.png')).relative_to(ROOT))},'keys':sorted(self.keys),'tokens':sorted(self.tokens),'textCount':len(self.texts),'textOverlaps':overlaps,'touchTargets':self.touches,'assets':sorted(self.assets),'nativeLimitations':CATALOG['nativeAcceptance'],'pngFont':'macOS local approximation: Helvetica Neue / PingFang; NOT native UIKit or Android rendering','renderedHeight':self.h})
        if metadata.get('scope')=='conditional-full' or metadata.get('page') in ['S06b','S06c']:
            metadata['paths']['annotatedSvg']=str((EXPORT/category/(stem+'--annotated.svg')).relative_to(ROOT))
            metadata['paths']['annotatedPng']=str((EXPORT/category/(stem+'--annotated.png')).relative_to(ROOT))
        metadata['renderedHeight']=metadata.get('height',self.h)
        MANIFEST.append(metadata)
        for overlap in overlaps:
            ISSUES.append({'kind':'text-overlap','board':stem,'details':overlap})

@lru_cache(maxsize=12)
def photo(name):
    return Image.open(SOURCE/'foundations/photos'/f'{name}.jpg').convert('RGB')

class Screen:
    def __init__(self,page,platform,locale,w,h,variant='base',state=None,conditional=False):
        self.original=page
        self.page=copy.deepcopy(page)
        self.platform,self.locale,self.w,self.h,self.variant=platform,locale,w,h,variant
        self.state=state or {}
        if self.state.get('title'):
            self.page['title']=self.state['title']
        self.conditional=conditional or page.get('conditionalOnly',False) and variant!='base'
        self.reserved=page.get('conditionalOnly',False) and not self.conditional
        if self.reserved:
            self.page=copy.deepcopy(next(p for p in CATALOG['pages'] if p['id']=='S15'))
            self.page['blocks']=self.page['blocks'][:3]
            self.page['fixedActions']=[]
        self.scale=1.6 if variant=='large-type' else 1
        self.theme='dark' if variant=='dark' or self.page['layout'] in ['player','rest'] else 'light'
        self.high=variant=='high-contrast' or self.state.get('highContrast')
        self.nav=self.page.get('nav')
        self.left=216 if self.nav and w>=840 else (88 if self.nav and w>=600 else 0)
        self.pad=16 if w<600 else 32
        self.cw=min(720,w-self.left-2*self.pad)
        self.x=self.left+(w-self.left-self.cw)/2
        self.footer=self.page.get('fixedActions',[])
        self.footerH=0
        if self.footer:
            self.footerH=max(76,24+sum(self.button_height(b,self.cw) for b in self.footer)+8*(len(self.footer)-1))
            if self.cw>=600 and self.scale==1:
                self.footerH=80
        self.selectionH=0
        if self.page.get('fixedSelectionSummary'):
            self.selectionH=self.th('library.selection_summary',self.cw,'heading',{'selected':24,'playable':20})+24
            self.footerH+=self.selectionH
        self.bottom=h-(96 if self.nav and w<600 else 24)-self.footerH
        self.pages=[]
        self.cursor=0
        self.blockRanges=[]
        self.new_page()
    def value(self,key,params=None):
        return localize(key,self.locale,params,self.platform)[0]
    def th(self,key,width,style='body',params=None):
        value=self.value(key,params)
        size=size_for(style,self.scale)
        return len(lines_for(value,size,width)[0])*round(size*TOKENS['typography']['type/'+style]['lineHeight'])
    def button_height(self,b,width):
        return max(48,self.th(b['key'],width-40,'body',b.get('params'))+24)
    def new_page(self):
        self.c=Canvas(self.w,self.h+184,self.theme,self.scale,self.platform,self.locale,'high-contrast' if self.high else self.variant)
        self.pages.append(self.c)
        self.draw_nav()
        self.y=36
        if self.page.get('title') and self.page['layout'] not in ['welcome','rest','ready']:
            self.y+=self.c.text(self.page['title'],self.x,self.y,self.cw,'title',params={'count':24})+24
        self.startY=self.y
    def draw_nav(self):
        c=self.c
        navs=['frames','library','sources','settings']
        if not self.nav:
            return
        if self.w>=840:
            c.rect(0,0,216,self.h,'surface.panel')
            c.logo(20,24,48)
            c.text('brand.name',28,86,160,'heading',scale=1)
            c.text('nav.subtitle',28,126,160,'caption',scale=1)
            for i,name in enumerate(navs):
                y=216+i*76
                if self.nav==name:
                    c.rect(16,y-8,184,64,'surface.canvas',12)
                    c.rect(16,y+3,3,40,'accent.action',1)
                c.icon(name,30,y+10)
                c.text('nav.'+name,68,y+8,128,'body',scale=1)
                c.touches.append({'key':'nav.'+name,'box':[16,y-8,184,64]})
            c.text('nav.privacy_footer',24,self.h-84,166,'caption',scale=1)
        elif self.w>=600:
            c.rect(0,0,88,self.h,'surface.panel')
            c.logo(14,24,60)
            for i,name in enumerate(navs):
                y=170+i*100
                if self.nav==name:
                    c.rect(8,y-8,72,80,'surface.canvas',12)
                c.icon(name,32,y)
                c.text('nav.'+name,4,y+34,80,'caption',center=True,scale=1)
                c.touches.append({'key':'nav.'+name,'box':[8,y-8,72,80]})
        else:
            y=self.h-88
            c.rect(0,y,self.w,88,'surface.panel')
            for i,name in enumerate(navs):
                x=i*self.w/4
                c.icon(name,x+self.w/8-12,y+10,token='accent.action' if self.nav==name else 'text.primary')
                c.text('nav.'+name,x+4,y+42,self.w/4-8,'caption',center=True,scale=1)
                c.touches.append({'key':'nav.'+name,'box':[x,y,self.w/4,80]})
    def ensure(self,height):
        if self.y+height>self.bottom and self.y>self.startY:
            self.new_page()
        if self.y+height>self.bottom:
            raise ValueError(f'Block too tall {self.original["id"]} {self.variant} {height} at {self.y}/{self.bottom}')
    def txt(self,key,style='body',token='text.primary',params=None):
        height=self.th(key,self.cw,style,params)
        self.ensure(height+16)
        self.c.text(key,self.x,self.y,self.cw,style,token,params)
        self.y+=height+16
    def btn(self,b,width=None,x=None,y=None):
        width=width or self.cw
        height=self.button_height(b,width)
        external=y is not None
        if not external:
            self.ensure(height+12)
        x=self.x if x is None else x
        y=self.y if y is None else y
        disabled=b.get('disabled',False) or self.state.get('invalid') and b['key']=='common.save'
        token='surface.canvas' if disabled else ('status.error' if b.get('tone')=='danger' else ('text.primary' if b.get('tone','primary')=='primary' else 'surface.panel'))
        self.c.rect(x,y,width,height,token,12 if self.platform=='ipad' else 8,'separator',2 if self.high else 1)
        self.c.text(b['key'],x+20,y+12,width-40,'body','text.secondary' if disabled else ('text.onAction' if b.get('tone','primary') in ['primary','danger'] else 'text.primary'),b.get('params'),True)
        self.c.touches.append({'key':resolve(b['key'],self.platform),'box':[x,y,width,height],'enabled':not disabled,'action':b.get('action')})
        if self.high and b.get('tone','primary')=='primary':
            self.c.path([(x-3,y-3),(x+width+3,y-3),(x+width+3,y+height+3),(x-3,y+height+3),(x-3,y-3)],'focus.ring',2)
        if not external:
            self.y+=height+12
    def draw(self,b):
        b=copy.deepcopy(b)
        key=resolve(b.get('key'),self.platform)
        if 'key' in b:
            b['key']=key
        if key in self.state.get('replaceButtons',{}) and b['kind']=='button':
            b['key']=self.state['replaceButtons'][key]
            key=b['key']
        if key in self.state.get('enableButtons',[]):
            b['disabled']=False
        if b['kind']=='text':
            params=b.get('params',{})
            if key=='library.selection_summary':
                params.update(selected=self.state.get('selected',24),playable=0 if self.state.get('zeroPlayable') else self.state.get('playable',20))
            if key=='settings.reduce_motion_note' and self.variant!='reduce-motion' and self.original['id']=='S15':
                return
            if key=='settings.reduce_transparency_note' and self.variant!='reduce-transparency' and self.original['id']=='S15':
                return
            self.txt(key,b.get('style','body'),params=params)
        elif b['kind']=='notice':
            height=self.th(key,self.cw-64,'body',b.get('params'))+32
            self.ensure(height+16)
            self.c.rect(self.x,self.y,self.cw,height,'surface.panel',16,'status.'+b.get('tone','warning'),2)
            self.c.ellipse(self.x+16,self.y+22,7,7,'status.'+b.get('tone','warning'))
            self.c.text(key,self.x+40,self.y+16,self.cw-64,'body','text.primary',b.get('params'))
            self.y+=height+16
        elif b['kind']=='button':
            self.btn(b)
        elif b['kind']=='image':
            height=min(b.get('height',260),self.bottom-self.startY-24)
            self.ensure(height+20)
            if self.state.get('replaceImage'):
                self.c.rect(self.x,self.y,self.cw,height,'surface.panel',12)
                self.c.logo(self.x+self.cw/2-64,self.y+height/2-64,128)
            else:
                mode='fill_crop' if self.state.get('crop') else ('fit_color' if self.state.get('solid') else b.get('mode','fit_blur'))
                self.c.picture(b.get('name','lake'),self.x,self.y,self.cw,height,mode)
                if self.original['id'] in ['S03','S13']:
                    glassKey='common.demo_badge' if self.original['id']=='S03' else 'playback.preview'
                    glassW=min(180,self.cw-32)
                    glassH=max(48,self.th(glassKey,glassW-24,'caption')+24)
                    self.c.rect(self.x+16,self.y+16,glassW,glassH,'surface.photoControl',16,opacity=1 if self.variant in ['reduce-transparency','high-contrast'] else .94)
                    self.c.text(glassKey,self.x+28,self.y+28,glassW-24,'caption','text.onPhoto')
            self.y+=height+20
        elif b['kind']=='logo':
            self.ensure(160)
            self.c.logo(self.x+self.cw/2-64,self.y,128)
            self.y+=160
        elif b['kind']=='switch':
            height=max(56,self.th(key,self.cw-108)+16)
            self.ensure(height+8)
            on=self.state.get('checks',{}).get(key,b.get('on',False))
            disabled=b.get('disabled',False)
            self.c.text(key,self.x,self.y+8,self.cw-108)
            sx=self.x+self.cw-60
            sy=self.y+8
            self.c.rect(sx,sy,56,32,'accent.action' if on and not disabled else 'separator',16)
            self.c.ellipse(sx+(28 if on else 4),sy+4,24,24,'surface.panel')
            self.c.touches.append({'key':key,'box':[sx-4,sy-8,64,48],'enabled':not disabled,'on':on})
            self.y+=height
            if b.get('help'):
                help=b['help']
                if key=='rule.dynamic_toggle' and on:
                    help='rule.dynamic_warning'
                self.txt(help,'caption','status.warning' if on and key=='rule.dynamic_toggle' else 'text.secondary')
            self.y+=8
        elif b['kind']=='input':
            error=self.state.get('fieldErrors',{}).get(key,b.get('error'))
            disabled=b.get('disabled',False)
            if self.state.get('restEnabled') and key in ['settings.rest_start','settings.rest_end']:
                disabled=False
            if self.state.get('transitionOff') and key=='playback.transition_duration':
                disabled=True
            value='abc' if error and self.state.get('invalid') else b.get('value','')
            if key=='frames.name_placeholder':
                value=self.value('frames.name_default')
            lh=self.th(key,self.cw,'caption')
            vh=self.th('design.value',self.cw-32,'body',{'value':value or self.value('common.search')})
            eh=self.th(error,self.cw,'caption')+8 if error else 0
            height=lh+12+max(52,vh+24)+eh+20
            self.ensure(height)
            self.c.text(key,self.x,self.y,self.cw,'caption','text.secondary')
            yy=self.y+lh+12
            self.c.rect(self.x,yy,self.cw,max(52,vh+24),'surface.panel',8,'status.error' if error else 'separator',2 if error or self.high else 1)
            self.c.text('design.value',self.x+16,yy+12,self.cw-32,'body','text.secondary' if disabled else 'text.primary',{'value':value or self.value('common.search')})
            self.c.touches.append({'key':key,'box':[self.x,yy,self.cw,max(52,vh+24)],'enabled':not disabled,'input':'secure' if key=='source.nas.password' else 'text'})
            self.y=yy+max(52,vh+24)+8
            if error:
                self.c.text(error,self.x,self.y,self.cw,'caption','status.error')
                self.y+=eh
            self.y+=12
        elif b['kind']=='choice':
            self.txt(key,'caption','text.secondary')
            selected=b.get('selected',0)
            if key=='playback.transition' and (self.state.get('transitionOff') or self.variant=='reduce-motion'):
                selected=0
            if key=='playback.fit_mode':
                selected=2 if self.state.get('crop') else (1 if self.state.get('solid') else 0)
            if key=='settings.language' and 'languageSelection' in self.state:
                selected=self.state['languageSelection']
            disabled=b.get('disabled',False) or self.state.get('transitionOff') and key=='playback.transition_duration'
            options=b['options']
            col=2 if self.cw>=430 else 1
            if self.scale>1:
                col=1
            ww=(self.cw-12*(col-1))/col
            for start in range(0,len(options),col):
                values=options[start:start+col]
                hs=[max(48,self.th(o if isinstance(o,str) else o['key'],ww-54,'body',None if isinstance(o,str) else o.get('params'))+24) for o in values]
                hh=max(hs)
                self.ensure(hh+12)
                for j,o in enumerate(values):
                    k=o if isinstance(o,str) else o['key']
                    params=None if isinstance(o,str) else o.get('params')
                    xx=self.x+j*(ww+12)
                    chosen=start+j==selected and not disabled
                    self.c.rect(xx,self.y,ww,hh,'surface.canvas' if disabled else 'surface.panel',10,'accent.action' if chosen else 'separator',2 if chosen else 1)
                    self.c.ellipse(xx+14,self.y+17,12,12,'accent.action' if chosen else 'separator')
                    if chosen:
                        self.c.ellipse(xx+17,self.y+20,6,6,'surface.panel')
                    self.c.text(k,xx+38,self.y+12,ww-52,'body','text.secondary' if disabled else 'text.primary',params)
                    self.c.touches.append({'key':k,'box':[xx,self.y,ww,hh],'selected':chosen,'enabled':not disabled})
                self.y+=hh+12
        elif b['kind']=='slider':
            self.txt(key,'caption','text.secondary')
            self.ensure(104)
            disabled=self.state.get('transitionOff') and key=='playback.transition_duration'
            v=b['value']
            self.c.rect(self.x,self.y+24,self.cw,4,'separator',2)
            if not disabled:
                progress=(v-b['min'])/(b['max']-b['min'])
                self.c.rect(self.x,self.y+24,self.cw*progress,4,'accent.action',2)
                self.c.ellipse(self.x+self.cw*progress-12,self.y+14,24,24,'accent.action')
            self.c.text(b.get('format','design.value'),self.x,self.y+48,self.cw,'body','text.secondary' if disabled else 'text.primary',{'value':v})
            self.c.touches.append({'key':key,'box':[self.x,self.y,self.cw,48],'enabled':not disabled,'range':[b['min'],b['max']],'step':b['step']})
            self.y+=104
        elif b['kind']=='progress':
            self.ensure(32)
            self.c.rect(self.x,self.y,self.cw,6,'separator',3)
            self.c.rect(self.x,self.y,self.cw*b['value'],6,'accent.action',3)
            self.y+=32
        elif b['kind']=='skeleton':
            self.ensure(160)
            for i in range(3):
                self.c.rect(self.x,self.y+i*48,self.cw*(1-i*.16),24,'separator',8)
            self.y+=160
        elif b['kind']=='source':
            help=resolve(b['help'],self.platform)
            height=max(92,self.th(key,self.cw-104,'heading')+self.th(help,self.cw-104,'body')+40)
            self.ensure(height+20)
            self.c.rect(self.x,self.y,self.cw,height,'surface.panel',16,'separator')
            self.c.icon(b.get('icon','folder'),self.x+20,self.y+24)
            hh=self.c.text(key,self.x+68,self.y+16,self.cw-100,'heading')
            self.c.text(help,self.x+68,self.y+hh+24,self.cw-100,'body','text.secondary')
            action=resolve(b.get('action'),self.platform)
            if action:
                self.c.touches.append({'key':key,'box':[self.x,self.y,self.cw,height],'action':action})
            self.y+=height+20
        elif b['kind']=='folder':
            denied=self.state.get('folderDenied')==key
            height=max(88,self.th(key,self.cw-104,'heading')+32)
            self.ensure(height+16)
            self.c.rect(self.x,self.y,self.cw,height,'surface.panel',12,'separator')
            self.c.icon('folder',self.x+16,self.y+26)
            self.c.text(key,self.x+60,self.y+16,self.cw-112,'heading')
            self.c.rect(self.x+self.cw-44,self.y+26,24,24,'separator' if denied else ('accent.action' if b.get('selected') else 'surface.canvas'),6)
            if b.get('selected') and not denied:
                self.c.icon('check',self.x+self.cw-44,self.y+26,24,'text.onAction')
            self.y+=height+16
            if denied:
                self.txt('error.src_permission_denied.body','caption','status.error')
        elif b['kind']=='gallery':
            count=b.get('count',6)
            if self.state.get('selected'):
                count+=2
            self.gallery(self.state.get('galleryState',b.get('state','selection')),count)
        elif b['kind']=='order':
            items=b['items'][:]
            if self.state.get('excludeItem'):
                items.pop(1)
            if self.state.get('newItem'):
                items.append('mountain')
            for i,name in enumerate(items):
                self.ensure(196)
                self.c.picture(name,self.x,self.y,144,104,'fill_crop')
                self.c.text('design.value',self.x+164,self.y,self.cw-164,'heading',params={'value':f'{i+1:02}'})
                self.c.text('order.new_badge' if self.state.get('newItem') and i==len(items)-1 else 'a11y.drag_handle',self.x+164,self.y+44,self.cw-164,'caption')
                self.y+=120
                if self.cw>=520:
                    ww=(self.cw-12)/2
                    height=max(self.button_height({'key':'order.move_to_start'},ww),self.button_height({'key':'order.move_to_end'},ww))
                    self.ensure(height+16)
                    self.btn({'key':'order.move_to_start','tone':'secondary'},ww,self.x,self.y)
                    self.btn({'key':'order.move_to_end','tone':'secondary'},ww,self.x+ww+12,self.y)
                    self.y+=height+24
                else:
                    self.btn({'key':'order.move_to_start','tone':'secondary'})
                    self.btn({'key':'order.move_to_end','tone':'secondary'})
        else:
            raise ValueError(b['kind'])
    def gallery(self,state,count):
        if state=='no-results':
            self.draw({'kind':'logo'})
            self.txt('library.search_no_result','heading')
            return
        cols=max(2,math.floor((self.cw+16)/(166 if self.scale==1 else 240)))
        ww=(self.cw-(cols-1)*16)/cols
        cellH=ww*.66+max(56,round(50*self.scale))
        for start in range(0,count,cols):
            self.ensure(cellH+20)
            for i in range(start,min(count,start+cols)):
                xx=self.x+(i-start)*(ww+16)
                selected=state in ['selection','all-selected'] and (i%3!=2 or state=='all-selected')
                if state in ['pending','damaged'] and i%2==0:
                    self.c.rect(xx,self.y,ww,ww*.66,'surface.panel',12,'separator')
                    self.c.logo(xx+ww/2-32,self.y+ww*.33-32,64)
                    k='library.pending_badge' if state=='pending' else 'library.status_failed'
                else:
                    self.c.picture(['lake','mountain','lake-portrait'][i%3],xx,self.y,ww,ww*.66,'fill_crop')
                    k='a11y.photo_selected' if selected else 'library.status_ready'
                if selected:
                    self.c.rect(xx+ww-40,self.y+8,32,32,'accent.action',16)
                    self.c.icon('check',xx+ww-36,self.y+12,24,'text.onAction')
                self.c.text(k,xx,self.y+ww*.66+8,ww,'caption')
                self.c.touches.append({'key':'a11y.photo_cell','box':[xx,self.y,ww,cellH],'selected':selected})
            self.y+=cellH+20
        if state=='damaged':
            self.txt('error.media_decode_failed.body','caption','status.error')
            self.btn({'key':'common.retry','tone':'secondary'})
    def player(self):
        c=self.c
        safe=self.state.get('safeScreen')
        if not safe:
            c.picture('lake-portrait',0,0,self.w,self.h,'fit_blur')
        controls=self.state.get('controls',True)
        if controls:
            c.rect(24,24,self.w-48,76,'surface.photoControl',24,opacity=1 if self.variant=='reduce-transparency' else .94)
            c.text('common.done' if self.platform=='ipad' else 'player.exit',44,42,250,'body','text.onPhoto')
            c.touches.append({'key':'player.exit','box':[24,24,280,76],'action':'S03'})
        if self.state.get('more'):
            self.y=124
            self.x=self.w-400
            self.cw=368
            for k,action in [('frames.choose_content','S10'),('frames.playback_settings','S13'),('player.remove_current','S16-frame-only'),('player.exit','S03')]:
                self.btn({'key':k,'action':action,'tone':'secondary'})
        clockY=self.h-304 if controls else self.h-180
        c.rect(24,clockY-12,min(self.w-48,340),136,'surface.photoControl',16,opacity=1 if self.variant=='reduce-transparency' else .94)
        c.text('design.value',44,clockY,280,'clock','text.onPhoto',{'value':'10:20'},scale=1)
        c.text('design.value',44,clockY+88,280,'caption','text.onPhoto',{'value':'10月4日 · 星期日' if self.locale=='zh' else 'Sunday, October 4'},scale=1)
        if controls:
            bw=min(self.w-48,560)
            xx=(self.w-bw)/2
            yy=self.h-140
            c.rect(xx,yy,bw,96,'surface.photoControl',32,opacity=1 if self.variant in ['reduce-transparency','high-contrast'] else .94)
            labels=['player.previous','player.play' if self.state.get('paused') else 'player.pause','player.next','player.more']
            icons=['previous','play' if self.state.get('paused') else 'pause','next','more']
            for i,(k,icon) in enumerate(zip(labels,icons)):
                bx=xx+i*bw/4
                disabled=self.state.get('single') and i in [0,2]
                c.icon(icon,bx+bw/8-12,yy+14,token='text.secondary' if disabled else 'text.onPhoto')
                c.text(k,bx+4,yy+52,bw/4-8,'caption','text.secondary' if disabled else 'text.onPhoto',center=True,scale=1)
                c.touches.append({'key':k,'box':[bx,yy,bw/4,96],'enabled':not disabled})
        if self.state.get('playerNotice'):
            key=self.state['playerNotice']
            h=self.th(key,self.w-96)+24
            c.rect(24,116,self.w-48,h,'surface.photoControl',16)
            c.text(key,48,128,self.w-96,'body','text.onPhoto')
        if self.variant=='reduce-motion':
            c.rect(24,116,self.w-48,80,'surface.photoControl',16)
            c.text('settings.reduce_motion_note',48,128,self.w-96,'body','text.onPhoto')
        if safe:
            self.y=228
            self.x=32
            self.cw=self.w-64
            self.btn({'key':'player.empty_action_choose','action':'S10'})
            self.btn({'key':'player.empty_action_downloads','action':'S08','tone':'secondary'})
    def run(self):
        layout=self.page['layout']
        if layout=='launch':
            self.c.logo(self.w/2-128,self.h/2-128,256,True)
        elif layout=='ready':
            self.c.logo(self.w/2-128,self.h/2-128,256)
            self.y=self.h/2+136
            self.txt('brand.name','title')
            blocks=self.state.get('blocks',self.page['blocks'])
            for b in blocks:
                self.draw(b)
            if self.variant=='reduce-motion':
                self.txt('settings.reduce_motion_note')
        elif layout=='player':
            self.player()
        elif layout=='rest':
            self.c.rect(0,0,self.w,self.h,'surface.rest')
            self.y=160
            self.txt('player.resting_title','title')
            for b in self.state.get('blocks',self.page['blocks']):
                self.draw(b)
        else:
            if layout=='welcome':
                self.c.logo(self.x,20,72)
                self.y=116
                self.txt('welcome.title','title')
            blocks=self.state.get('blocks',self.page['blocks'])
            if self.conditional:
                blocks=blocks+self.page.get('conditionalAppend',[]) if not self.state.get('blocks') else blocks
            if self.variant=='reduce-motion' and self.original['id']=='S13':
                self.state['transitionOff']=True
                blocks=[{'kind':'notice','key':'settings.reduce_motion_note'}]+blocks
            for b in self.state.get('prepend',[])+blocks:
                self.draw(b)
        self.finish()
    def finish(self):
        category='screens' if self.variant=='base' and not self.state and not self.conditional else 'states'
        stateid=self.state.get('id','default')
        scope='conditional-full' if self.conditional else 'scoped'
        suffix=f'{self.variant}--{stateid}--{scope}'
        for i,c in enumerate(self.pages):
            self.c=c
            if self.footer:
                yy=self.bottom+8
                c.rect(self.left,yy-8,self.w-self.left,self.footerH,'surface.panel')
                if self.selectionH:
                    c.text('library.selection_summary',self.x,yy,self.cw,'heading',params={'selected':self.state.get('selected',24),'playable':0 if self.state.get('zeroPlayable') else self.state.get('playable',20)})
                    yy+=self.selectionH
                if self.cw>=600 and self.scale==1:
                    ww=(self.cw-12*(len(self.footer)-1))/len(self.footer)
                    for j,b in enumerate(self.footer):
                        self.btn(b,ww,self.x+j*(ww+12),yy+8)
                else:
                    for b in self.footer:
                        self.btn(b,self.cw,self.x,yy)
                        yy+=self.button_height(b,self.cw)+8
            note=f'{self.original["id"]} · {self.platform} · {self.locale} · {self.w}×{self.h} · {suffix} · viewport {i+1}/{len(self.pages)}'
            c.rect(0,self.h,self.w,184,'surface.panel')
            annotationY=self.h+8+c.label(note,16,self.h+8,self.w-32,'meta',scale=1)+8
            limits='Static design; values illustrative. Android PNG: local font approximation, not native rendering.' if self.platform=='android' else 'Static design; values illustrative. Native layout, safe areas and accessibility acceptance pending.'
            if self.reserved:
                limits='S14 reserved / NOT ROUTABLE: scoped Settings shown; weather entry hidden. See conditional-full S14.'
            elif self.conditional:
                limits='CONDITIONAL-FULL / M0 NOT PASSED: Google policy, OAuth, retention and/or weather service required. No live service.'
            elif self.original['id'] in ['S06b','S06c']:
                limits='SECURITY CANDIDATE / NOT VERIFIED: sample values, default reject; per-host confirmation only. M0 required.'
            annotationY+=c.label(limits,16,annotationY,self.w-32,'meta',scale=1)+8
            if self.variant=='large-type':
                c.label('1.6× finite design sample; NOT the OS maximum accessibility size.',16,annotationY,self.w-32,'meta',scale=1)
            elif len(self.pages)>1:
                c.label('Scroll vertically to the next numbered viewport; header/navigation/actions stay fixed. No content omitted.',16,annotationY,self.w-32,'meta',scale=1)
            stem=f'{self.original["id"]}-{self.platform}-{self.w}-{self.locale}--{suffix}'+(f'--scroll-{i+1:02}' if i else '')
            annotation={'safeArea':{'insetsCandidate':[24,32,24,32],'nativeInsetsRequired':True,'player':'Photos fill window; only controls and overlays respect safe area'},'scroll':{'direction':'vertical','contentFrame':[self.x,self.startY,self.cw,self.bottom-self.startY],'page':i+1,'totalViewports':len(self.pages),'continuation':'Same scroll surface represented as independent numbered complete-block viewports; not separate app routes','end':'No horizontal scroll; no truncation; continue until last viewport'},'fixedActions':{'keys':[b['key'] for b in self.footer],'height':self.footerH,'bottomNavigation':88 if self.nav and self.w<600 else 0},'touch':'iPad >=44pt; Android >=48dp; drawn hit regions recorded in manifest','keyboard':'Resize available viewport and scroll focused input fully above keyboard; move fixed actions above keyboard; secure password; no logging of input','navigation':{'widthClass':'expanded' if self.w>=840 else ('medium' if self.w>=600 else 'compact'),'style':'sidebar216' if self.left==216 else ('rail88' if self.left else 'bottom-four' if self.nav else 'fullscreen'),'back':'iPad visible Back/Done, swipe-back and Escape; Android Back closes sheet before leaving screen'},'bodyMaxWidth':720,'grid':'floor((available + 16)/(150 + 16)); reduce columns for large type','scopeNote':self.original.get('gateNote',''),'externalUI':self.original.get('externalUI','No system UI simulated'),'textLayout':'Explicit SVG text lines measured with local Pillow font; no shrinking; independent continuation viewports','material':'Static tint approximation; native blur and original pixels not validated','reduceMotion':'No forced launch wait; direct cut/no slide; static asset is not timing implementation','contrastTokens':['text.primary/surface.panel','text.secondary/surface.canvas','text.onPhoto/surface.photoControl']}
            c.save(category,stem,{'page':self.original['id'],'platform':self.platform,'locale':self.locale,'width':self.w,'height':self.h,'theme':self.theme,'state':stateid,'variant':self.variant,'scope':scope,'reservedHiddenRoute':self.reserved,'viewportIndex':i+1,'viewportCount':len(self.pages),'annotations':annotation})

def foundations():
    for theme in ['light','dark']:
        c=Canvas(1100,1240,theme)
        c.label('PhotoPilot / Foundation / 02 exact tokens',48,32,1004,'title','text.primary')
        for i,(key,values) in enumerate(TOKENS['colors'].items()):
            x=48+(i%2)*520
            y=116+(i//2)*132
            c.rect(x,y,84,84,key,16)
            c.label(key,x+108,y+8,370,'heading','text.primary')
            c.label(values[theme],x+108,y+48,370,'body')
        c.text('brand.tagline',48,840,960,'title')
        c.text('sources.import_vs_display',48,910,960,'body')
        c.label('4 / 8 / 12 / 16 / 24 / 32 / 48  •  body 17  •  caption 15  •  heading 22  •  title 32',48,984,990,'body')
        c.label('32 inset · 216 sidebar · 720 max body · 44pt / 48dp hit regions · 1.6× finite large-type sample',48,1040,990,'body')
        c.label('No native device or maximum accessibility size acceptance. No font files distributed.',48,1120,990,'body')
        c.save('foundations','tokens-'+theme,{'page':'foundation-tokens','platform':'shared','locale':'zh','width':1100,'height':1240,'theme':theme,'state':'default','variant':'foundation','annotations':{'source':'02 §3 precise color values'}})

def flows():
    specs=[('first-run',['welcome.primary_action','sources.add','source.local_photos.title','import.title','library.choose_title','playback.title','frames.play'],['S02','S04','S05','S08','S10','S13','S12'],'sources.import_vs_display'),('nas-safety',['source.nas.title','permission.local_network.title','cert.title','cleartext.title','source.nas.choose_folders','import.title'],['S06','S06a','S06b','S06c','S07','S08'],'error.source_plaintext_private_only.body'),('deletion-scope',['library.remove_from_frame','delete.from_device_action','sources.disconnect','sources.disconnect_keep_copies','delete.clear_cache','player.resting_title'],['S11 → S16','S09 → S16','S04 → S16','S16 → S03','S15 → S16','S12 → S17'],'delete.from_device_body'),('conditional-google',['source.google.choose','source.google.opening_browser','source.google.waiting_selection','import.title','library.choose_title','frames.play'],['S04','External official picker','S04 return','S08','S10','S12'],'source.google.not_full_sync')]
    for name,keys,ids,help in specs:
        for locale in ['zh','en']:
            c=Canvas(1100,1320,locale=locale)
            c.label('PhotoPilot / '+name+' / STATIC FLOW, NOT AN INTERACTIVE PROTOTYPE',48,32,1000,'heading','text.primary')
            c.text(help,48,92,1000,'body',params={'frames':2} if help=='delete.from_device_body' else None)
            for i,(k,id) in enumerate(zip(keys,ids)):
                if name=='nas-safety':
                    locations=[(48,204),(48,364),(592,364),(592,524),(48,684),(48,844)]
                    x,y=locations[i]
                    width=460
                else:
                    x,y,width=64,204+i*140,972
                c.rect(x,y,width,112,'surface.panel',16,'separator')
                c.label(id,x+20,y+14,width-40,'meta')
                c.text(k,x+20,y+46,width-40,'heading')
                if i<len(keys)-1 and name not in ['nas-safety','deletion-scope']:
                    c.path([(550,y+114),(550,y+132),(544,y+126),(550,y+132),(556,y+126)],'accent.action',2)
            if name=='nas-safety':
                for points in [[(278,316),(278,364)],[(278,476),(278,684)],[(278,796),(278,844)],[(508,420),(592,420)],[(508,430),(550,430),(550,580),(592,580)]]:
                    c.path(points,'accent.action',2)
                c.label('Certificate error → S06b; plaintext requested → S06c. Cancel/public/expired → S06. Only an accepted secure connection continues to S07.',48,1030,1000,'body')
            c.label('Conditional Google: policy/OAuth/retention gates required. NAS certificate or plaintext branches are optional error routes, not mandatory sequential authorization.',48,1216,1000,'caption')
            c.save('flows',name+'-'+locale,{'page':'flow-'+name,'platform':'shared','locale':locale,'width':1100,'height':1320,'theme':'light','state':'static-flow','variant':'flow','annotations':{'interactive':False,'externalUI':'Official browser/system surfaces are external nodes, not simulated screens'}})

def overview():
    base=[r for r in MANIFEST if r.get('variant')=='base' and r.get('viewportIndex')==1 and r.get('state')=='default' and r.get('scope')=='scoped']
    for platform in ['ipad','android']:
        for locale in ['zh','en']:
            for width in [1024 if platform=='ipad' else 1280,768,520]:
                rows=[r for r in base if r['platform']==platform and r['locale']==locale and r['width']==width]
                cw,ch=320,310 if width==520 else 272
                im=Image.new('RGB',(cw*4,ch*6+80),'#F4F1EB')
                d=ImageDraw.Draw(im)
                d.text((20,18),f'PhotoPilot / {platform} / {locale} / {width} / 23 IDs',font=font(22),fill='#202321')
                for i,r in enumerate(rows):
                    tile=Image.open(ROOT/r['paths']['png'])
                    tile.thumbnail((cw-20,ch-44),Image.Resampling.LANCZOS)
                    x=i%4*cw+10
                    y=i//4*ch+64
                    im.paste(tile,(x,y))
                    d.text((x,y+ch-38),r['page']+(' / reserved' if r.get('reservedHiddenRoute') else ''),font=font(15),fill='#202321')
                path=EXPORT/'screens'/f'overview-{platform}-{width}-{locale}.png'
                im.save(path,compress_level=8)

def report():
    counts=Counter(r.get('variant','unknown') for r in MANIFEST)
    first=[r for r in MANIFEST if r.get('viewportIndex')==1]
    basic=[r for r in first if r['variant']=='base' and r['state']=='default' and r['scope']=='scoped']
    expected={(p['id'],platform,locale,w) for p in CATALOG['pages'] for platform,dims in CATALOG['platforms'].items() for locale in CATALOG['locales'] for w,h in dims}
    actual={(r['page'],r['platform'],r['locale'],r['width']) for r in basic}
    missing=sorted(expected-actual)
    missingStates=[]
    for p in CATALOG['pages']:
        for st in p['states']:
            for platform in CATALOG['platforms']:
                for locale in CATALOG['locales']:
                    if not any(r['page']==p['id'] and r['platform']==platform and r['locale']==locale and r['state']==st['id'] for r in first):
                        missingStates.append([p['id'],st['id'],platform,locale])
    for r in MANIFEST:
        for target in r.get('touchTargets',[]):
            if target['box'][2]<44 or target['box'][3]<44:
                ISSUES.append({'kind':'undersized-hit-target','page':r['page'],'target':target})
    payload={'version':'1.1','generator':'design/motion/render_design.py','contractSha256':hashlib.sha256((ROOT/'contracts/strings/strings.json').read_bytes()).hexdigest(),'tokensSha256':hashlib.sha256((SOURCE/'tokens.json').read_bytes()).hexdigest(),'screens':MANIFEST}
    (SOURCE/'manifest.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n')
    checks={'version':'1.1','baseExpected':276,'baseFirstViewports':len(basic),'missingBase':missing,'missingStates':missingStates,'artboards':len(MANIFEST),'svgCount':len(MANIFEST),'pngCount':len(MANIFEST),'overviewPngCount':12,'variantCountsIncludingContinuations':dict(counts),'stateDefinitions':sum(len(p['states']) for p in CATALOG['pages']),'issues':ISSUES,'limitations':CATALOG['nativeAcceptance'],'noBusinessAppChanged':True,'acceptance':'Generated design evidence only; not user sign-off or native/M0 validation'}
    (SOURCE/'validation.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in checks.items() if k not in ['limitations','issues']},ensure_ascii=False,indent=2))
    print('Layout issues:',len(ISSUES))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--smoke',action='store_true')
    args=parser.parse_args()
    for category in ['screens','states','foundations','flows']:
        (SOURCE/category).mkdir(parents=True,exist_ok=True)
        (EXPORT/category).mkdir(parents=True,exist_ok=True)
    shutil.copytree(SOURCE/'foundations/photos',EXPORT/'foundations/photos',dirs_exist_ok=True)
    for p in CATALOG['pages']:
        if args.smoke and p['id'] not in ['S03','S09','S13','S15','S16','S12']:
            continue
        print('Rendering',p['id'],flush=True)
        for platform,dims in CATALOG['platforms'].items():
            for locale in CATALOG['locales']:
                for w,h in dims:
                    Screen(p,platform,locale,w,h).run()
                w,h=dims[0]
                if args.smoke:
                    continue
                for st in p['states']:
                    Screen(p,platform,locale,w,h,'state',st,conditional=p.get('conditionalOnly',False)).run()
                for variant,ids in CATALOG['variants'].items():
                    if p['id'] in ids:
                        Screen(p,platform,locale,w,h,variant,conditional=p.get('conditionalOnly',False)).run()
                if p['id'] in ['S04','S13','S14','S15']:
                    Screen(p,platform,locale,w,h,'conditional-full',conditional=True).run()
                    for st in p.get('conditionalStates',[]):
                        Screen(p,platform,locale,w,h,'conditional-full',st,True).run()
    if not args.smoke:
        foundations()
        flows()
        overview()
    report()

if __name__=='__main__':
    main()
