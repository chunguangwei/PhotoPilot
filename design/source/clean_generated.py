import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'design/source'
EXPORT=ROOT/'design/export/design-assets-v1.1'
manifest=json.loads((SOURCE/'manifest.json').read_text())
keep={str((ROOT/v).resolve()) for row in manifest['screens'] for v in row['paths'].values()}
for row in manifest['screens']:
    if 'annotatedSvg' in row['paths']:
        path=ROOT/row['paths']['sourceSvg']
        keep.add(str(path.with_name(path.stem+'--annotated.svg').resolve()))
removed=[]
for base in [SOURCE,EXPORT]:
    for category in ['screens','states']:
        for path in (base/category).glob('S*--*--*--*.*'):
            if path.suffix not in ['.svg','.png'] or str(path.resolve()) in keep:
                continue
            if '--scoped' not in path.name and '--conditional-full' not in path.name:
                continue
            removed.append(str(path.relative_to(ROOT)))
            path.unlink()
result={'removedObsoleteGeneratedViewports':removed,'scope':'Only this generator Sxx/platform/width/locale output names, never other design assets','manifest':'design/source/manifest.json'}
(SOURCE/'generation-cleanup.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Removed obsolete generated viewports:',len(removed))
