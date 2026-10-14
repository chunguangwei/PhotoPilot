import importlib.util
import json
import random
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'design/source'
EXPORT=ROOT/'design/export/design-assets-v1.1'
spec=importlib.util.spec_from_file_location('design_renderer',ROOT/'design/motion/render_design.py')
r=importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

patterns={}
patterns['white']=Image.new('RGB',(1100,420),'white')
patterns['black']=Image.new('RGB',(1100,420),'black')
portrait=Image.new('RGB',(1100,420),(194,165,130))
d=ImageDraw.Draw(portrait)
d.ellipse((450,60,650,300),fill=(233,198,157))
d.ellipse((350,300,750,650),fill=(80,91,97))
patterns['portrait-tones']=portrait
leaves=Image.new('RGB',(1100,420),(48,76,34))
d=ImageDraw.Draw(leaves)
rng=random.Random(32)
for i in range(3200):
    x,y=rng.randrange(1100),rng.randrange(420)
    d.ellipse((x,y,x+rng.randrange(4,25),y+rng.randrange(5,17)),fill=(rng.randrange(30,160),rng.randrange(50,195),rng.randrange(20,125)))
patterns['leaves']=leaves
snow=Image.new('RGB',(1100,420),(235,245,250))
d=ImageDraw.Draw(snow)
d.polygon([(0,300),(170,80),(370,280),(620,40),(950,320),(1100,120),(1100,420),(0,420)],fill=(252,253,255))
patterns['snow']=snow

for name,im in patterns.items():
    for base in [SOURCE,EXPORT]:
        im.save(base/'foundations/photos'/('material-'+name+'.jpg'),quality=92,optimize=True)

for platform in ['ipad','android']:
    for locale in ['zh','en']:
        for name in patterns:
            for reduced in [False,True]:
                c=r.Canvas(1100,650,theme='dark',platform=platform,locale=locale)
                c.picture('material-'+name,0,0,1100,420,'fill_crop')
                c.rect(160,160,780,112,'surface.photoControl',32,opacity=1 if reduced else .94)
                for i,(key,icon) in enumerate([('player.previous','previous'),('player.pause','pause'),('player.next','next'),('player.more','more')]):
                    x=172+i*190
                    c.icon(icon,x+70,176,token='text.onPhoto')
                    c.text(key,x,214,172,'body','text.onPhoto',center=True)
                c.label(f'{platform} / {locale} / {name} / '+('opaque fallback' if reduced else 'static tinted control'),32,456,1036,'heading','text.primary')
                c.text('settings.reduce_transparency_note' if reduced else 'a11y.player_controls',32,504,1036,'body')
                c.label('Synthetic flat/portrait-tone/high-frequency/snow stress panel. Not a photograph of a person. Local Pillow composition, not native blur or device acceptance.',32,554,1036,'caption')
                c.save('foundations',f'material-{name}-{platform}-{locale}-'+('opaque' if reduced else 'tinted'),{'page':'material-'+name,'platform':platform,'locale':locale,'width':1100,'height':650,'theme':'dark','state':'opaque' if reduced else 'tinted','variant':'material','annotations':{'background':'Deterministic script synthesis; portrait tones are an abstract silhouette, no identifiable person','opacity':1 if reduced else .94,'nativeAcceptance':'Repeat on actual white, black, portrait, leaves and snow photography in native renderer'}})

manifest=json.loads((SOURCE/'manifest.json').read_text())
manifest['screens']=[x for x in manifest['screens'] if x.get('variant')!='material']+r.MANIFEST
(SOURCE/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
validation=json.loads((SOURCE/'validation.json').read_text())
validation['artboards']=len(manifest['screens'])
validation['svgCount']=len(manifest['screens'])
validation['pngCount']=len(manifest['screens'])
validation['variantCountsIncludingContinuations']['material']=len(r.MANIFEST)
validation['issues'].extend(r.ISSUES)
(SOURCE/'validation.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2)+'\n')
print('Material stress boards:',len(r.MANIFEST),'layout issues:',len(r.ISSUES))
