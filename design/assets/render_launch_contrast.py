import sys
from pathlib import Path
import numpy as np
from PIL import ImageChops
sys.path.insert(0,str(Path(__file__).resolve().parent))
from render_asset_primitives import *
from render_icon_assets import brand, icon

PROXY_COLORS={'white':['#FFFFFF'],'black':['#000000'],'leaves':['#163F24','#31723D','#79A447','#B6CA6C'],'snow':['#FDFEFF','#DFE9EE','#BECED9','#FFFFFF'],'portrait-blocks':['#BD8C75','#E2B8A0','#654C43','#ACA6A0']}


def launch():
    records=[]
    for platform,(w,h) in {'ios':(1024,768),'android':(1280,800)}.items():
        for lang in ('zh','en'):
            for theme in ('light','dark'):
                stem=f'{platform}-wide-{lang}-{theme}'
                base=EXPORT/'launch'/stem
                base.mkdir(parents=True,exist_ok=True)
                for name in ('S00','S01-t0'):
                    s=Scene(w,h,theme,platform,scale=3)
                    s.rect(0,0,w,h,'surface.canvas')
                    brand(s,w/2-128,h/2-160,1,dot_only=True)
                    s.save(base/name)
                a=Image.open(base/'S00.png').convert('RGB')
                b=Image.open(base/'S01-t0.png').convert('RGB')
                diff=ImageChops.difference(a,b)
                diff.save(base/'pixel-diff.png')
                arr=np.asarray(diff)
                records.append({'platform':platform,'language':lang,'appearance':theme,'dimensions':[w,h],'s00':str((base/'S00.png').relative_to(ROOT)),'s01_t0':str((base/'S01-t0.png').relative_to(ROOT)),'different_pixels':int(np.any(arr!=0,axis=2).sum()),'max_channel_difference':int(arr.max()),'difference_png':str((base/'pixel-diff.png').relative_to(ROOT)),'sha256_s00':sha(base/'S00.png'),'sha256_s01_t0':sha(base/'S01-t0.png')})
                r=Scene(w,h,theme,platform,scale=3)
                r.rect(0,0,w,h,'surface.canvas')
                brand(r,w/2-128,h/2-160,1)
                r.text(w/2-76,h/2+132,'PhotoPilot','title')
                if lang=='zh':
                    r.text(w/2-22,h/2+184,'光屿','heading')
                r.save(base/'S01-reduced-motion-static')
                timeline(platform,lang,theme,base)
    dump(EXPORT/'launch/launch-manifest.json',{'version':'1.1','date':DATE,'scope':'S00 and S01-t0 are design frames only; not native LaunchScreen, Android splash resources or app implementation.','language_rule':'S00 and S01-t0 contain no text and must match across zh/en.','dark_rule':'Dark design candidate uses surface.canvas dark and accent.brand dark; real system launch appearance behavior remains pending.','timing':[{'ms':[0,180],'stage':'Stable light, start frame reveal'},{'ms':[80,560],'stage':'Frame path then horizon reveal'},{'ms':[380,620],'stage':'PhotoPilot and localized subtitle fade'},{'condition':'ready','stage':'Route immediately, interrupt branding; suggested crossfade 160–220 ms or direct switch under reduced motion'}],'reduced_motion':'Static full mark in app, or direct route if ready. No waiting for reveal. S00 remains the matching light-dot frame.','limits':['No mandatory 800ms wait. No fake progress. Beyond 2s show actual stage; beyond 8s offer safe diagnostic/retry, never interrupt migration unsafely.','No native integration, device launch tests or release-policy verification.'],'pairs':records,'all_zero':all(r['different_pixels']==0 for r in records)})
    print('Launch exact-diff pairs:',len(records),flush=True)


def timeline(platform,lang,theme,base):
    zh=lang=='zh'
    s=Scene(1280,920,theme,platform,scale=2)
    s.rect(0,0,1280,920,'surface.canvas')
    s.text(40,28,'启动时间线 / Launch timeline' if zh else 'Launch timeline / design only','title')
    s.text(40,76,f'{platform} / {theme} / '+('就绪即跳转 · 不等待品牌动画' if zh else 'Ready means route now — never wait for branding'),'body')
    labels=[('0–180 ms','光点稳定；框体开始出现','Stable light; frame begins',0,180),('80–560 ms','框体路径与地平线显现','Frame and horizon reveal',80,560),('380–620 ms','字标及本地化副标淡入','Wordmark and subtitle fade',380,620)]
    for i,(time,z,e,start,end) in enumerate(labels):
        y=150+i*118
        s.text(40,y,time,'heading','accent.action')
        s.text(238,y,z if zh else e,'body')
        s.rect(238,y+44,936,8,'separator',4)
        s.rect(238+start/800*936,y+38,(end-start)/800*936,20,'accent.action',10)
    for tick in (0,180,380,560,620,800):
        tx=238+tick/800*936
        s.path(f'M{tx} 488 V502','text.secondary',1)
        s.text(tx-8,514,str(tick),'meta')
    s.text(1198,514,'ms','meta')
    for i,label in enumerate(('S00 = S01 t0','80–560 ms','380–620 ms','Ready / Route')):
        x=40+i*304
        s.rect(x,558,272,194,'surface.panel',16)
        brand(s,x+74,574,.48,dot_only=i==0)
        if i>=2:
            s.text(x+76,702,'PhotoPilot','body')
        s.text(x,770,label,'caption')
    s.wrap(40,824,'减少动态：静态标志或直接切换。淡出参考 160–220ms；仅设计说明，非客户端实现。' if zh else 'Reduced motion: static mark or direct switch. Optional route crossfade 160–220ms. Design specification, not client code.',1180,'body')
    s.save(base/'motion-timeline')


def background(kind,w=600,h=420,theme='light',platform='ios'):
    s=Scene(w,h,theme,platform,scale=2)
    colors=PROXY_COLORS[kind]
    s.rect(0,0,w,h,colors[0],name='original-synthetic-background')
    if kind=='leaves':
        for row in range(13):
            for col in range(18):
                x=12+col*32+(row%2)*12;y=16+row*30
                s.path(f'M{x} {y+8} Q{x+18} {y-14} {x+30} {y+6} Q{x+16} {y+29} {x} {y+8} Z',fill=colors[(row*3+col)%len(colors)],name='synthetic-leaf-shape')
    elif kind=='snow':
        s.path(f'M0 260 L160 56 L286 230 L410 104 L600 286 V420 H0 Z',fill=colors[1])
        s.path(f'M75 178 L160 56 L213 133 L174 117 L147 148 L124 129 Z',fill=colors[3])
        s.path(f'M0 334 Q180 246 314 348 Q474 236 600 310 V420 H0 Z',fill=colors[2])
    elif kind=='portrait-blocks':
        for row in range(4):
            for col in range(5):
                s.rect(col*120,row*105,120,105,colors[(row+col*3)%4],name='portrait-palette-block-not-a-person')
    return s


def contrast(fg,bg):
    def lum(c):
        c=np.asarray(c,dtype=float)/255
        c=np.where(c<=.04045,c/12.92,((c+.055)/1.055)**2.4)
        return c[...,0]*.2126+c[...,1]*.7152+c[...,2]*.0722
    a=lum(fg);b=lum(bg)
    return (np.maximum(a,b)+.05)/(np.minimum(a,b)+.05)


def composite_evidence(scene,regions):
    image=np.asarray(scene.image().convert('RGB'))
    fg=rgb(TOKENS['text.primary'][scene.theme])
    result=[]
    for name,box in regions:
        x,y,w,h=box
        pixels=image[y:y+h,x:x+w,:].reshape(-1,3)
        colors,counts=np.unique(pixels,axis=0,return_counts=True)
        ratios=contrast(fg,colors)
        result.append({'region':name,'interior_box_px':box,'foreground_token':'text.primary','foreground_rgb':fg,'pixel_count':len(pixels),'minimum_ratio':round(float(ratios.min()),4),'maximum_ratio':round(float(ratios.max()),4),'normal_text_4_5_pass':bool(ratios.min()>=4.5),'key_icon_3_pass':bool(ratios.min()>=3),'actual_composite_colors':[{'rgb':c.tolist(),'pixels':int(n),'ratio':round(float(r),4)} for c,n,r in zip(colors,counts,ratios)]})
    return result


def generate_contrast():
    entries=[]
    names={'white':('纯白','Pure white'),'black':('纯黑','Pure black'),'leaves':('树叶合成','Synthetic leaves'),'snow':('雪景合成','Synthetic snow'),'portrait-blocks':('人像色块代理 · 非人物','Portrait palette proxy · Not a person')}
    for kind in PROXY_COLORS:
        original=background(kind)
        original.save(ROOT/'design/assets/proxies'/kind)
        for platform in ('ios','android'):
            for lang in ('zh','en'):
                for theme in ('light','dark'):
                    board=Scene(1300,700,theme,platform,scale=2)
                    board.rect(0,0,1300,700,'surface.canvas')
                    board.text(32,24,names[kind][0 if lang=='zh' else 1],'title')
                    board.text(32,72,f'{platform} / {lang} / {theme} / '+('脚本合成背景，不是真机材质验收' if lang=='zh' else 'Synthetic backgrounds, not native-material acceptance'),'body')
                    surfaces=[]
                    for i,mode in enumerate(('glass','solid')):
                        s=background(kind,theme=theme,platform=platform)
                        alpha=.94 if mode=='glass' else 1
                        s.rect(24,30,552,160,'surface.panel',16,opacity=alpha,name='overlay-backing')
                        s.rect(24,310,552,80,'surface.panel',40 if platform=='ios' else 28,opacity=alpha,name='toolbar-backing')
                        evidence=composite_evidence(s,[('overlay',[44,50,512,120]),('toolbar',[64,326,472,48])])
                        s.text(44,44,'09:41','clock')
                        s.text(46,132,'晴 · 22°C（示例）' if lang=='zh' else 'Sunny · 22°C (example)','heading')
                        for j,name in enumerate(('play_previous','play_pause','play_next','play_more')):
                            icon(s,name,86+j*125,338,24)
                        ox=32+i*636;oy=144
                        for item in s.items:
                            p=item.copy()
                            if p['kind']=='path':
                                p['d']=transform(p['d'],ox,oy)
                            else:
                                p['x']+=ox;p['y']+=oy
                            board.items.append(p)
                        minimum=min(v['minimum_ratio'] for v in evidence)
                        board.text(ox,588,f'{mode} / panel alpha {alpha:.2f} / min {minimum:.2f}:1','body')
                        surfaces.append({'material':mode,'panel_token':'surface.panel','alpha':alpha,'regions':evidence})
                    board.text(32,646,'人像色块不等于人像真机验收；未加载任何真人、AI 人物或旧湖图。' if lang=='zh' else 'Portrait blocks are not portrait-device tests. No real/AI people or historical lake image used.','caption')
                    paths=board.save(EXPORT/'components/contrast'/f'{kind}-{platform}-{lang}-{theme}')
                    entries.append({'background':kind,'platform':platform,'language':lang,'theme':theme,'formats':paths,'surfaces':surfaces})
        print('Contrast:',kind,flush=True)
    dump(EXPORT/'components/contrast/contrast-measurements.json',{'version':'1.1','date':DATE,'method':'WCAG sRGB relative luminance; contrast of text.primary against each unique ACTUAL post-alpha-composite RGB color in the rendered backing interiors, before glyph/icon paint. All sampled interior pixels enumerated, not a single nominal background token. Rounded antialiased edges and glyph antialiasing excluded.','source_palettes':PROXY_COLORS,'source_type':'Original deterministic script synthesis; no image model, external photos or identifiable people.','glass_model':'Static .94 panel tint composite only; no real platform blur, Liquid Glass or moving-photo material. Solid fallback alpha=1, layout unchanged.','scope_limit':'This measures exactly these synthetic pixels. It is not all-background testing, portrait-device acceptance, HDR/display validation or dynamic/native material testing.','entries':entries})

if __name__=='__main__':
    launch()
    generate_contrast()
