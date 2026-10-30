# Таблица цветов по умолчанию для FL Render из галереи: предложения + отметки (review.json, `bash gallery_deploy.sh pull`).
#   python export.py  ->  ../src/main/resources/assets/flrender/default_colors.json
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'src/main/resources/assets/flrender/default_colors.json'


def main():
    blocks = json.loads((ROOT / 'site/data/blocks.json').read_text('utf-8'))
    presets = {p['id']: p['color'] for p in json.loads((ROOT / 'site/data/presets.json').read_text('utf-8'))}
    rv = ROOT / 'review.json'
    items = json.loads(rv.read_text('utf-8')).get('items', {}) if rv.exists() else {}
    out, none = {}, []
    for b in blocks:
        r = items.get(b['id'], {})
        if r.get('color') == 'none':
            c = None
        elif r.get('preset') in presets:
            c = presets[r['preset']]
        elif r.get('color'):
            c = r['color']
        else:
            c = b.get('prop')
        if c:
            out[b['id']] = {'color': c.upper()}
        elif b.get('cur') or r.get('color') == 'none':
            none.append(b['id'])   # было с цветом (код мода или прежняя таблица) — явно снять
    for bid in none:
        out[bid] = {'color': 'none'}
    root = {'version': 2, 'blocks': dict(sorted(out.items())),
            'items': {'minecraft:lava_bucket': {'color': presets['lava'], 'level': 15}}}
    OUT.write_text(json.dumps(root, ensure_ascii=False, indent=1) + '\n', 'utf-8')
    print('цветов:', sum(1 for v in out.values() if v['color'] != 'none'), ' снято:', len(none), ' отметок:', len(items))


if __name__ == '__main__':
    main()
