import json
from collections import Counter
from pathlib import Path
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'design/source'
EXPORT=ROOT/'design/export/design-assets-v1.1'
manifest=json.loads((SOURCE/'manifest.json').read_text())
audit=json.loads((SOURCE/'asset-audit.json').read_text())
validation=json.loads((SOURCE/'validation.json').read_text())


def lum(rgb):
    v=[x/255 for x in rgb]
    v=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in v]
    return sum(x*y for x,y in zip(v,[.2126,.7152,.0722]))

fg=lum((244,241,235))
material=[]
for name in ['white','black','portrait-tones','leaves','snow']:
    for mode in ['tinted','opaque']:
        file=EXPORT/'foundations'/f'material-{name}-ipad-en-{mode}.png'
        im=Image.open(file).convert('RGB')
        samples=[im.getpixel((x,y)) for y in [166,260] for x in range(200,900,10)]
        contrasts=[(max(fg,lum(p))+.05)/(min(fg,lum(p))+.05) for p in samples]
        material.append({'background':name,'mode':mode,'sampledMinContrast':round(min(contrasts),3),'samples':len(samples),'scope':'Static Pillow panel pixels only; excludes glyph pixels; no native blur/compositing acceptance'})
(SOURCE/'material-contrast.json').write_text(json.dumps(material,ensure_ascii=False,indent=2)+'\n')
provenance=json.loads((SOURCE/'photo-provenance.json').read_text())
provenance['materialStressAssets']={'generator':'design/source/render_materials.py','files':['material-'+x+'.jpg' for x in ['white','black','portrait-tones','leaves','snow']],'method':'Deterministic script-generated abstract surfaces; portrait-tone silhouette contains no identifiable person','use':'Design material contrast samples; real-photo native test still required'}
(SOURCE/'photo-provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n')
first=[r for r in manifest['screens'] if r.get('viewportIndex')==1]
report={'version':'1.1','date':'2026-10-04','status':'Design assets generated and machine-audited; awaiting product visual review and explicit next instruction; no business App implementation','scope':['design/source/','design/export/design-assets-v1.1/screens/','design/export/design-assets-v1.1/states/','design/export/design-assets-v1.1/foundations/','design/export/design-assets-v1.1/flows/','design/motion/render_design.py'],'counts':{'uniquePrimaryArtboards':len(manifest['screens']),'firstViewportByVariant':dict(Counter(r['variant'] for r in first)),'allViewportsByVariant':validation['variantCountsIncludingContinuations'],'annotatedCompanionPairs':sum('annotatedSvg' in r['paths'] for r in manifest['screens']),'overviewPngs':12,'actualExportFileCounts':audit['actualExportFileCounts'],'exportBytes':audit['exportBytes']},'checks':{'missingBase':validation['missingBase'],'missingStates':validation['missingStates'],'geometryIssues':validation['issues'],'assetAuditErrors':audit['errors'],'contractUnmodifiedHashMatches':audit['contractSha256Matches'],'makeCheck':'PASS; 21 checker tests on 2026-10-04; existing fixture/tool checks are not business/native tests','representativeVisualInspection':['iPad zh 23-page overview','S03 iPad zh wide','S09 Android en compact fixed selection summary','S10 iPad zh zero-playable','S12 iPad zh complete portrait plus same-image blurred edges','S13 iPad en preview and duration controls','S13 Android en no-transition continuation','S15 iPad en final continuation privacy/about/legal entry controls','S16 iPad en 1.6x finite type','S06b Android en expired/refused annotated','S04 Android en conditional Google waiting','S14 Android en conditional weather annotated','S17 iPad zh black resting surface'],'notClaimed':['Exhaustive human visual proof of every raster glyph','User visual signature','Native fonts or layout render parity','Real keyboard/safe-area/VoiceOver/TalkBack acceptance','Any completed M0 gate']},'handoffNotes':['S14 base boards are reserved-ID bookkeeping: they show scoped Settings with no weather route. Actual S14 is conditional-full, with gate warning outside the screen.','S13 full controls span 5–6 base viewports. S15 spans 4–6. Numbered scroll continuations are views into the same scrolling surface, not independent application screens.','SVG screen viewport and PNG are exact requested dimensions. Conditional and security --annotated companions include an outside-canvas notes band.','All visible UI text is sourced from contract entries or separately registered design additions. Parameters are illustrative. 35 design additions include sample labels and essential missing safety/help/unit copy; no contract edits.','The 1.6x stress sample scales content, not an assertion of maximum OS accessibility sizing. Navigation rails remain a finite design approximation.','Portrait material test is an abstract skin-tone silhouette, not real-person photography; actual native five-background testing is still outstanding.','Flow diagrams are static and not interactive prototypes. NAS security error branches are conditional; no real connection was verified.','This work did not modify root handoff documents or existing generator scripts because the task assigned only design-side paths. The supervising owner must merge these actual results into document 16.','Out-of-scope component-library/native-icon/launch-motion/legal-document deliverables are not certified by this screen-design work.'],'reproduce':['python3 -B design/source/build_catalog.py','python3 -B design/source/synthesize_photos.py','python3 -B design/motion/render_design.py','python3 -B design/source/render_materials.py','python3 -B design/source/clean_generated.py','python3 -B design/source/validate_assets.py','python3 -B design/source/finalize_handoff.py'],'next':'Wait for explicit instruction; do not start business development.'}
(SOURCE/'delivery-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report['counts'],ensure_ascii=False,indent=2))
print('Material sampled contrast minimum:',min(x['sampledMinContrast'] for x in material))
