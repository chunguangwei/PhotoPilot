import sys
from pathlib import Path
import json
import ast
import io
import tokenize
import xml.etree.ElementTree as ET
from collections import Counter
import pymupdf as fitz
import numpy as np
from PIL import Image, ImageChops
sys.path.insert(0,str(Path(__file__).resolve().parent))
from render_asset_primitives import *


def main():
    folders=[ROOT/'design/icons',EXPORT/'components',EXPORT/'icons',EXPORT/'launch',EXPORT/'app-icons',ROOT/'design/assets/proxies']
    paths=sorted(p for folder in folders for p in folder.rglob('*') if p.is_file() and p.name!='validation-report.json')
    failures=[]
    pdfs=[]
    for p in paths:
        try:
            if p.suffix in ('.svg','.xml'):
                root=ET.parse(p).getroot()
                if p.suffix=='.svg':
                    assert root.tag.endswith('svg')
                    assert not any(e.tag.endswith('image') for e in root.iter()), 'Embedded bitmap in SVG'
                    for e in root.iter():
                        for key,value in e.attrib.items():
                            if key in ('fill','stroke') and value.startswith('#'):
                                assert '/proxies/' in str(p) or '/contrast/' in str(p), 'Unbound UI color'
                else:
                    assert root.tag=='vector'
                    ns='{http://schemas.android.com/apk/res/android}'
                    assert float(root.attrib[ns+'viewportWidth'])>0
                    for e in root:
                        commands(e.attrib[ns+'pathData'])
            elif p.suffix=='.png':
                with Image.open(p) as im:
                    im.verify()
            elif p.suffix=='.pdf':
                with fitz.open(p) as doc:
                    assert len(doc)==1
                    page=doc[0]
                    assert not page.get_images(full=True)
                    assert not page.get_fonts(full=True)
                    assert len(page.get_drawings())>0
                    pix=page.get_pixmap(matrix=fitz.Matrix(4,4),alpha=True)
                    assert any(pix.samples)
                    assert b'/Subtype /Image' not in p.read_bytes()
                    pdfs.append({'path':str(p.relative_to(ROOT)),'pages':1,'vector_paths':len(page.get_drawings()),'images':0,'fonts':0,'mupdf_rendered':True})
            elif p.suffix=='.json':
                json.loads(p.read_text())
        except Exception as e:
            failures.append({'path':str(p),'error':str(e)})
    cm=json.loads((EXPORT/'components/components-manifest.json').read_text())
    assert len(cm['boards'])==224
    assert not cm['text_width_issues']
    matrix=cm['matrix']
    state_checks=[]
    for board in cm['boards']:
        root=ET.parse(ROOT/board['formats']['svg']).getroot()
        paths_xml=[e for e in root if e.tag.endswith('path')]
        assert set(board['states'])==set(matrix[board['component']]['meaningful'])
        for state in ('focused','selected','disabled','pressed'):
            if state not in board['states']:
                continue
            index=board['states'].index(state)
            bounds=board['cells'][index]['bounds']
            normal=board['cells'][0]['bounds']
            with Image.open(ROOT/board['formats']['png']) as im:
                def crop(b):
                    x,y,w,h=b
                    return im.crop((x+12,y+44,x+w-12,y+h-12)).convert('RGB')
                changed=ImageChops.difference(crop(bounds),crop(normal)).getbbox() is not None
            if not changed:
                failures.append({'path':board['formats']['png'],'error':f'{state} visually identical to normal excluding label'})
            state_checks.append({'board':board['formats']['png'],'state':state,'visually_distinct_from_normal':changed})
        if 'focused' in board['states']:
            assert any('focus' in e.attrib.get('data-name','') for e in paths_xml)
    imf=json.loads((EXPORT/'icons/icons-manifest.json').read_text())
    for entry in imf['entries']:
        root=ET.parse(ROOT/entry['formats']['svg']).getroot()
        for e in root:
            if e.tag.endswith('path'):
                assert float(e.attrib['stroke-width'])==entry['stroke_width']
        for p in entry['formats'].values():
            assert (ROOT/p).is_file()
    lm=json.loads((EXPORT/'launch/launch-manifest.json').read_text())
    for pair in lm['pairs']:
        with Image.open(ROOT/pair['s00']) as a,Image.open(ROOT/pair['s01_t0']) as b:
            assert a.size==b.size
            assert np.array_equal(np.asarray(a),np.asarray(b))
    for platform in ('ios','android'):
        for theme in ('light','dark'):
            a=Image.open(EXPORT/'launch'/f'{platform}-wide-zh-{theme}'/'S00.png')
            b=Image.open(EXPORT/'launch'/f'{platform}-wide-en-{theme}'/'S00.png')
            assert np.array_equal(np.asarray(a),np.asarray(b))
    app=json.loads((EXPORT/'app-icons/app-icons-manifest.json').read_text())
    for path,expected in app['source_masters'].items():
        assert sha(ROOT/path)==expected
    with Image.open(ROOT/app['ios']['file']) as im:
        assert im.mode=='RGB' and im.size==(1024,1024)
    c=json.loads((EXPORT/'components/contrast/contrast-measurements.json').read_text())
    regions=[region for entry in c['entries'] for surf in entry['surfaces'] for region in surf['regions']]
    assert len(c['entries'])==40
    for region in regions:
        assert sum(row['pixels'] for row in region['actual_composite_colors'])==region['pixel_count']
    scripts=[ROOT/'design/motion/render_components.py',*(ROOT/'design/assets').glob('render_*.py'),Path(__file__).resolve()]
    for p in scripts:
        ast.parse(p.read_text())
        assert not any(t.type==tokenize.COMMENT for t in tokenize.generate_tokens(io.StringIO(p.read_text()).readline)),str(p)
    counts=dict(Counter(p.suffix for p in paths))
    report={'date':DATE,'status':'passed' if not failures else 'failed','file_counts':counts,'files_checked':len(paths),'components':{'boards':len(cm['boards']),'state_cells':cm['state_cell_count'],'width_overflow_issues':len(cm['text_width_issues']),'nonlabel_state_distinctness_checks':len(state_checks),'checks':state_checks},'pdf_validation':{'engine':'Installed PyMuPDF / MuPDF','count':len(pdfs),'all_path_only':True,'files':pdfs},'launch':{'exact_zero_diff_pairs':len(lm['pairs']),'language_equivalence_checks':4},'contrast':{'boards':len(c['entries']),'regions':len(regions),'minimum':min(r['minimum_ratio'] for r in regions),'maximum':max(r['maximum_ratio'] for r in regions),'all_these_synthetic_regions_meet_4_5':all(r['normal_text_4_5_pass'] for r in regions),'not_native_or_all_photos':True},'brand_masters_unchanged':True,'python_sources_parsed_no_comments':len(scripts),'failures':failures,'limits':LIMITS+['No client build, native resource inflation, platform font render or real-device verification performed for this design-only task.','XML well-formedness and path parsing are not Android aapt/native rendering acceptance.','SVG/PNG are generated from the same primitives; independent full SVG renderer equivalence is not claimed.']}
    dump(EXPORT/'components/validation-report.json',report)
    inventory_paths=[*paths,EXPORT/'components/validation-report.json',*scripts,ROOT/'design/assets/LICENSES.md']
    inventory=[{'path':str(p.relative_to(ROOT)),'format':p.suffix[1:],'sha256':sha(p),'source_date':DATE,'ai_assisted':True} for p in inventory_paths]
    dump(ROOT/'design/assets/asset-inventory-v1.1.json',{'version':'1.1','scope':'Only this task directories. Existing historical exploration images excluded.','licenses':'design/assets/LICENSES.md','files':inventory})
    print(json.dumps({k:v for k,v in report.items() if k not in ('components','pdf_validation')},ensure_ascii=False,indent=2))
    if failures:
        raise SystemExit(1)

if __name__=='__main__':
    main()
