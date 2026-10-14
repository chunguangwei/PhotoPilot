import sys
from pathlib import Path
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parent))
from render_asset_primitives import *

SHAPES = {
    'nav_frames': ['M15 4 H7 Q4 4 4 7 V17 Q4 20 7 20 H17 Q20 20 20 17 V9', 'M8 15 Q11 12 14 15 Q16 14 17 15', 'M19 4 H20'],
    'nav_library': [roundrect(4,4,16,16,3), 'M12 4 V20 M4 12 H20'],
    'nav_sources': [roundrect(4,4,16,6,2), roundrect(4,14,16,6,2), 'M8 7 H9 M8 17 H9 M12 10 V14'],
    'nav_settings': ['M4 6 H8 M12 6 H20 M4 12 H14 M18 12 H20 M4 18 H6 M10 18 H20',circle(10,6,2),circle(16,12,2),circle(8,18,2)],
    'play_previous': ['M6 5 V19 M18 5 L9 12 L18 19 Z'],
    'play_pause': ['M8 5 V19 M16 5 V19'],
    'play_next': ['M18 5 V19 M6 5 L15 12 L6 19 Z'],
    'play_more': [circle(5,12,1),circle(12,12,1),circle(19,12,1)],
    'play': ['M7 5 L18 12 L7 19 Z'],
    'check': ['M5 12 L10 17 L19 7'],
    'close': ['M6 6 L18 18 M18 6 L6 18'],
    'back': ['M14 5 L7 12 L14 19 M7 12 H20'],
    'empty': ['M15 4 H7 Q4 4 4 7 V17 Q4 20 7 20 H17 Q20 20 20 17 V10', 'M8 15 Q12 12 16 15', 'M19 4 H20'],
    'brand': ['M15 4 H7 Q4 4 4 7 V17 Q4 20 7 20 H17 Q20 20 20 17 V9', 'M8 16 Q11 13 14 16 Q16 15 17 16', 'M19 4 H20'],
    'download': ['M12 4 V14 M7 10 L12 15 L17 10 M5 16 V20 H19 V16'],
    'retry': ['M19 8 C16 3 7 3 5 9 C2 18 14 23 19 16 M19 4 V8 H15'],
    'warning': ['M12 4 L20 20 H4 Z M12 10 V14 M12 17 V17.2'],
    'search': [circle(10,10,6), 'M15 15 L20 20'],
    'lock': [roundrect(5,10,14,10,2), 'M8 10 V7 C8 2 16 2 16 7 V10 M12 14 V16'],
    'plus': ['M12 5 V19 M5 12 H19']
}
LABELS = {'nav_frames':('相框','Frames'),'nav_library':('照片库','Library'),'nav_sources':('来源','Sources'),'nav_settings':('设置','Settings'),'play_previous':('上一张','Previous photo'),'play_pause':('暂停','Pause'),'play_next':('下一张','Next photo'),'play_more':('更多','More'),'play':('播放','Play'),'check':('已选','Selected'),'close':('关闭','Close'),'back':('返回','Back'),'empty':('暂无内容','No content'),'brand':('光屿','PhotoPilot'),'download':('下载','Download'),'retry':('重试','Retry'),'warning':('警告','Warning'),'search':('搜索','Search'),'lock':('权限','Permission'),'plus':('添加','Add')}

def icon(scene,name,x,y,size=24,color='text.primary'):
    width={20:1.75,24:2,32:2.5}.get(size,2*size/24)
    for d in SHAPES[name]:
        scene.path(transform(d,x,y,size/24),color,width,name=f'icon/{name}/{size}')

def brand(scene,x,y,scale,mono=False,dot_only=False):
    tree=ET.parse(ROOT/'design/brand/logo-primary.svg')
    for e in tree.getroot():
        tag=e.tag.split('}')[-1]
        if tag=='path' and not dot_only:
            scene.path(transform(e.attrib['d'],x,y,scale),'text.primary',float(e.attrib['stroke-width'])*scale,name='brand/master-path')
        elif tag=='circle':
            scene.dot(x+float(e.attrib['cx'])*scale,y+float(e.attrib['cy'])*scale,float(e.attrib['r'])*scale,'text.primary' if mono else 'accent.brand',name='brand/light')

def generate_icons():
    entries=[]
    for name in SHAPES:
        for size in (20,24,32):
            s=Scene(size,size,scale=6)
            icon(s,name,0,0,size)
            base=ROOT/'design/icons'/f'ic_{name}_{size}'
            base.parent.mkdir(parents=True,exist_ok=True)
            base.with_suffix('.svg').write_text(s.svg())
            pdf=EXPORT/'icons/ios'/f'ic_{name}_{size}.pdf'
            xml=EXPORT/'icons/android'/f'ic_{name}_{size}.xml'
            vector_pdf(s,pdf)
            drawable(s,xml,True)
            entries.append({'semantic':name,'size':size,'stroke_width':{20:1.75,24:2,32:2.5}[size],'formats':{'svg':str(base.with_suffix('.svg').relative_to(ROOT)),'pdf':str(pdf.relative_to(ROOT)),'vector_drawable':str(xml.relative_to(ROOT))},'platforms':['ios','android'],'labels':dict(zip(('zh','en'),LABELS[name]))})
    for density in (1,2):
        w,h=900,128+len(SHAPES)*64
        b=Scene(w,h,scale=3)
        b.rect(0,0,w,h,'surface.canvas')
        b.text(32,24,'PhotoPilot / Original line icons','title')
        b.text(32,70,'20 / 1.75      24 / 2.0      32 / 2.5    •    Design review only','caption')
        for i,name in enumerate(SHAPES):
            y=116+i*64
            b.text(32,y+8,name,'body')
            for j,size in enumerate((20,24,32)):
                icon(b,name,340+j*170,y,size)
                icon(b,name,404+j*170,y,size,'accent.action')
        base=EXPORT/'icons'/f'icons-overview-{density}x'
        if density==1:
            b.save(base)
        else:
            b2=Scene(w*density,h*density,scale=3)
            for item in b.items:
                p=item.copy()
                if p['kind']=='path':
                    p['d']=transform(p['d'],scale=density)
                    p['width']*=density
                else:
                    p['x']*=density;p['y']*=density;p['size']*=density
                b2.items.append(p)
            b2.save(base)
    dump(EXPORT/'icons/icons-manifest.json',{'version':'1.1','date':DATE,'source':'Original AI-assisted path drawing; brand icon is an optically simplified UI derivative, not a replacement logo master.','grid':'24-unit canonical geometry; size-specific uniform stroke widths; >=2 units inset including stroke; rounded caps and joins.','optical_notes':'Below 32, brand light is a short rounded stroke and horizon simplified; all UI marks remain monochrome outline. Fractional 1.75/2.5 strokes are antialiased, not claimed pixel-perfect native rendering.','ios_integration':'Single-page vector PDF. Set template rendering and Preserve Vector Data when integrating; asset catalog not created.','android_integration':'VectorDrawable, strokeColor=?android:attr/textColorPrimary. Inflate/tint on device still pending.','limitations':LIMITS,'entries':entries})
    return len(entries)

def generate_app_icons():
    b=Scene(1024,1024,scale=3)
    b.rect(0,0,1024,1024,'surface.canvas')
    brand(b,0,-8,4)
    out=EXPORT/'app-icons/ios'
    out.mkdir(parents=True,exist_ok=True)
    b.image().convert('RGB').save(out/'app-icon-1024.png')
    b.save(out/'app-icon-derived')
    (out/'app-icon-derived.png').unlink()
    layers={}
    for name in ('foreground','background','monochrome'):
        s=Scene(108,108,scale=6)
        if name=='background':
            s.rect(0,0,108,108,'surface.canvas')
        else:
            brand(s,15.6,15.6,0.3,name=='monochrome')
        path=EXPORT/'app-icons/android'/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.with_suffix('.svg').write_text(s.svg())
        drawable(s,path.with_suffix('.xml'))
        layers[name]=s
    master=Scene(108,108,scale=6)
    master.items=layers['background'].items+layers['foreground'].items
    for mask in ('circle','squircle','rounded-square'):
        size=432
        scene=Scene(size,size,scale=3)
        for item in master.items:
            p=item.copy();p['d']=transform(p['d'],scale=4);p['width']*=4;scene.items.append(p)
        im=scene.image()
        m=Image.new('L',(size,size));d=ImageDraw.Draw(m)
        if mask=='circle':
            d.ellipse((0,0,size-1,size-1),fill=255)
        elif mask=='rounded-square':
            d.rounded_rectangle((0,0,size-1,size-1),radius=size*.22,fill=255)
        else:
            pts=[]
            for i in range(720):
                t=2*math.pi*i/720
                pts.append((size/2+(size/2-1)*math.copysign(abs(math.cos(t))**.5,math.cos(t)),size/2+(size/2-1)*math.copysign(abs(math.sin(t))**.5,math.sin(t))))
            d.polygon(pts,fill=255)
        im.putalpha(m)
        im.save(EXPORT/'app-icons/android'/f'mask-{mask}.png')
    guide=Scene(980,540,scale=2)
    guide.rect(0,0,980,540,'surface.canvas')
    guide.text(32,24,'Adaptive icon / design candidate','title')
    guide.text(32,72,'108 × 108 grid  ·  centre (54,54)  ·  circular safe radius 30','body')
    for x,name in ((40,'foreground'),(330,'monochrome')):
        guide.rect(x,142,256,256,'surface.panel',16)
        for p in layers[name].items:
            q=p.copy();q['d']=transform(q['d'],x,142,256/108);q['width']*=256/108;guide.items.append(q)
        guide.dot(x+128,270,30*256/108,None,'status.warning',1)
        guide.text(x,414,name,'body')
    guide.wrap(632,164,'Master geometry retained. Independent 0.30 scale and centred foreground placement; not an iOS icon shrink.',296)
    guide.wrap(632,304,'Safe circle and three masks are design candidates. Verify launcher masks, animation and store policy before release.',296,'caption')
    guide.text(32,490,'No launcher, wallpaper, themed-icon or minimum-device acceptance claimed.','caption')
    guide.save(EXPORT/'app-icons/adaptive-safe-area')
    fg=layers['foreground'].image(scale=8)
    a=fg.getchannel('A')
    distances=[math.hypot(x+.5-54,y+.5-54) for y in range(108) for x in range(108) if a.getpixel((x,y))>16]
    dump(EXPORT/'app-icons/app-icons-manifest.json',{'version':'1.1','date':DATE,'source_masters':{str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'design/brand').glob('*.svg')},'ios':{'file':str((out/'app-icon-1024.png').relative_to(ROOT)),'size':[1024,1024],'mode':'RGB','alpha':False,'geometry':'translate(0,-8) scale(4), identical to app-icon-master.svg; no pre-rounded corners'},'android':{'grid':[108,108],'foreground_transform':'translate(15.6,15.6) scale(0.30)','safe_circle':{'centre':[54,54],'radius':30,'design_candidate':True,'rendered_max_ink_radius_px':round(max(distances),3),'ink_within_candidate_circle':max(distances)<30},'layers':['foreground.svg','foreground.xml','background.svg','background.xml','monochrome.svg','monochrome.xml'],'mask_previews':['circle','squircle','rounded-square']},'limitations':LIMITS+['Adaptive safe area, minimum launcher size, wallpaper/folder/settings/Spotlight and platform submission requirements need release-time platform verification.']})

if __name__=='__main__':
    print('Icon variants:',generate_icons())
    generate_app_icons()
    print('App icon candidates generated.')
