import hashlib
import json
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'design/source'
EXPORT=ROOT/'design/export/design-assets-v1.1'

def luminance(value):
    values=[int(value[i:i+2],16)/255 for i in [1,3,5]]
    lin=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in values]
    return sum(a*b for a,b in zip(lin,[.2126,.7152,.0722]))

def ratio(a,b):
    la,lb=sorted([luminance(a),luminance(b)])
    return (lb+.05)/(la+.05)

def main():
    manifest=json.loads((SOURCE/'manifest.json').read_text())
    tokens=json.loads((SOURCE/'tokens.json').read_text())
    catalog=json.loads((SOURCE/'screens.json').read_text())
    entries={e['key'] for e in json.loads((ROOT/'contracts/strings/strings.json').read_text())['entries']}
    entries.update(e['key'] for e in json.loads((SOURCE/'copy-additions.json').read_text())['entries'])
    errors=[]
    referenced=set()
    states={}
    for row in manifest['screens']:
        svgpath=ROOT/row['paths']['svg']
        pngpath=ROOT/row['paths']['png']
        sourcepath=ROOT/row['paths']['sourceSvg']
        for path in [svgpath,pngpath,sourcepath]:
            if not path.is_file():
                errors.append(['missing-file',str(path)])
        svg=ET.parse(svgpath).getroot()
        if svg.attrib['width']!=str(row['width']) or svg.attrib['height']!=str(row['height']):
            errors.append(['svg-dimensions',str(svgpath)])
        with Image.open(pngpath) as im:
            if im.size!=(row['width'],row['height']):
                errors.append(['png-dimensions',str(pngpath),im.size])
            im.verify()
        if svg.find('.//{http://www.w3.org/2000/svg}g[@id="Annotations"]') is None:
            errors.append(['missing-annotations',str(svgpath)])
        for node in svg.iter():
            tag=node.tag.rsplit('}',1)[-1]
            if tag=='text':
                key=node.attrib.get('data-string-key')
                if key and key not in entries:
                    errors.append(['unknown-key',key])
                if 'data-token' not in node.attrib:
                    errors.append(['unbound-text-token',str(svgpath)])
                if not key and not node.attrib.get('data-annotation'):
                    errors.append(['unbound-text-key',str(svgpath)])
                if '{' in (node.text or ''):
                    errors.append(['unresolved-placeholder',str(svgpath),node.text])
            if tag=='image':
                href=node.attrib.get('href','')
                if href.startswith('data:'):
                    errors.append(['embedded-raster',str(svgpath)])
                target=svgpath.parent/href
                if not target.is_file():
                    errors.append(['missing-image-href',str(svgpath),href])
            if tag in ['rect','path','ellipse'] and node.attrib.get('fill')!='none' and 'data-token' not in node.attrib:
                errors.append(['unbound-shape-token',str(svgpath)])
        if row.get('scope')=='scoped':
            prohibited=[k for k in row['keys'] if k.startswith(('source.google.','weather.')) or k=='playback.weather']
            if prohibited:
                errors.append(['gate-visible-in-scoped',row['page'],prohibited])
        if row['page']=='S00':
            live=[n for n in svg.iter() if n.tag.endswith('text') and float(n.attrib.get('y',0))<row['height']]
            if live:
                errors.append(['system-launch-has-text',str(svgpath)])
        if row['page']=='S13' and row.get('viewportIndex'):
            states.setdefault((row['platform'],row['locale'],row['width'],row['variant'],row['state'],row['scope']),set()).update(row['keys'])
        referenced.update([svgpath,pngpath])
    required=['playback.hold_duration','playback.transition_duration','playback.fit_mode','playback.clock','playback.date','playback.overlay_position','playback.hour_format','playback.order','playback.brightness','playback.keep_awake_paused','settings.rest_window','design.quality']
    for identity,keys in states.items():
        if identity[4]=='default':
            for key in required:
                if key not in keys:
                    errors.append(['S13-missing-control',identity,key])
    contrasts=[]
    for theme in ['light','dark']:
        for fg,bg in [('text.primary','surface.canvas'),('text.primary','surface.panel'),('text.secondary','surface.canvas'),('text.secondary','surface.panel'),('accent.action','surface.panel'),('status.error','surface.panel')]:
            value=ratio(tokens['colors'][fg][theme],tokens['colors'][bg][theme])
            contrasts.append({'theme':theme,'foreground':fg,'background':bg,'ratio':round(value,3),'normalText4_5':value>=4.5})
            if value<4.5:
                errors.append(['contrast',theme,fg,bg,value])
    geometry=json.loads((SOURCE/'validation.json').read_text())
    errors.extend(geometry.get('issues',[]))
    byPage={}
    for row in manifest['screens']:
        if row.get('viewportIndex'):
            identity=(row['page'],row['platform'],row['locale'],row['width'],row['variant'],row['state'],row['scope'])
            byPage.setdefault(identity,set()).update(row['keys'])
    settingsRequired=['settings.appearance','settings.language','settings.accessibility','settings.storage_threshold','settings.wifi_only','source.nas.mirror_delete','cert.trusted_hosts_title','settings.auto_play','settings.rest_window','settings.rest_start','settings.rest_end','settings.privacy','settings.diagnostics','settings.share_statistics','settings.about','settings.licenses','settings.privacy_policy','settings.terms','design.backup_photos']
    for identity,keys in byPage.items():
        if identity[0]=='S15' and identity[5]=='default':
            for key in settingsRequired:
                if key not in keys:
                    errors.append(['S15-missing-control',identity,key])
    reportData={'boardInventory':[{k:r.get(k) for k in ['page','platform','locale','width','height','theme','state','variant','scope','viewportIndex','viewportCount','paths','keys','tokens']} for r in manifest['screens']]}
    (EXPORT/'screens'/'manifest.json').write_text(json.dumps(reportData,ensure_ascii=False,indent=2)+'\n')
    actual=Counter(p.suffix for folder in ['screens','states','foundations','flows'] for p in (EXPORT/folder).rglob('*') if p.is_file())
    totalBytes=sum(p.stat().st_size for folder in ['screens','states','foundations','flows'] for p in (EXPORT/folder).rglob('*') if p.is_file())
    result={'checkedArtboards':len(manifest['screens']),'errors':errors,'contrast':contrasts,'actualExportFileCounts':dict(actual),'exportBytes':totalBytes,'contractSha256Matches':hashlib.sha256((ROOT/'contracts/strings/strings.json').read_bytes()).hexdigest()==manifest['contractSha256'],'nativeAcceptanceRequired':catalog['nativeAcceptance'],'validationBoundary':'Machine checks cover static geometry, references, exact dimensions and source binding; manual visual sampling is separate. No screen reader, OS text scaling, keyboard or live renderer tested.'}
    (SOURCE/'asset-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if errors else 0

if __name__=='__main__':
    raise SystemExit(main())
