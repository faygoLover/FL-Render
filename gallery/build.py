# Галерея «Источники света» для хаба галерей: все блоки сборки, у которых есть свой свет (выгрузка lightdump.json из игры,
# мод Pack Audit/lightdump), картинка — изометрия модели из jar (рендер галереи Carry On), предложенный цвет — цвет
# FL Render, если он уже задан, иначе по самым ярким пикселям текстуры (почти белое — «без цвета»).
#   python build.py   — собрать site/ (data/blocks.json + img/*.png)
import ctypes, importlib.util, json, os, re, shutil, sys
from pathlib import Path

import numpy as np

if os.name == 'nt':
    k = ctypes.windll.kernel32
    k.SetPriorityClass(k.GetCurrentProcess(), 0x4000)  # BELOW_NORMAL

ROOT = Path(__file__).resolve().parent
CC = ROOT.parent.parent
SITE = ROOT / 'site'
IMG = SITE / 'img'

spec = importlib.util.spec_from_file_location('co', CC / 'Pack Audit/carryon_gallery/build.py')
co = importlib.util.module_from_spec(spec)
spec.loader.exec_module(co)
co.MODS = Path('D:/') / 'ElyPrismLauncher/instances/DEP_S5_rev/minecraft/mods'

# Рисуем блок включённым: свойства самого яркого состояния из выгрузки (поворот — как обычно, лицом к нам).
ORIENT = {'facing', 'horizontal_facing', 'rotation', 'axis', 'direction', 'face', 'attach_face', 'attachment', 'hanging'}
LIT = {}
_guess = co.guess_state


def lit_guess(props):
    st = _guess(props)
    for k, v in LIT.items():
        if k in props and v in props[k] and k not in ORIENT:
            st[k] = v
    return st


co.guess_state = lit_guess


def parse_state(s):
    m = re.search(r'\[(.*)\]$', s or '')
    return dict(kv.split('=', 1) for kv in m.group(1).split(',') if '=' in kv) if m else {}

import presets as P

# Светящиеся материалы, у которых цвет по текстуре уместен (остальные редкие блоки — без цвета).
GLOWY = re.compile(r'crystal|corundum|quartz|shroom|mushroom|glow|portal|core|rod|antiblock|pickle|cave_vines|obsidian|uranium|amber|'
                   r'heater|кристал|корунд|гриб|светя|портал|ядро|стерж|антиблок|лоза|обсидиан|уран|янтар|кварц|дыня|тыкв|кость')
SWITCH_KEYS = ('lit', 'powered', 'active', 'enabled', 'toggled', 'luminance', 'on', 'loaded')


def hexc(r, g, b):
    return '#%02X%02X%02X' % (int(r), int(g), int(b))


def from_flrender(c):
    return hexc(round(c['r'] * 255), round(c['g'] * 255), round(c['b'] * 255))


def from_image(img):
    """Цвет самых ярких непрозрачных пикселей; None — почти белый (цвет не нужен), '' — не понять."""
    a = np.asarray(img.convert('RGBA')).reshape(-1, 4).astype(np.float32)
    a = a[a[:, 3] > 200][:, :3]
    if len(a) < 20:
        return ''
    lum = a @ np.array([0.299, 0.587, 0.114], np.float32)
    mxp = a.max(axis=1)
    satp = (mxp - a.min(axis=1)) / np.maximum(mxp, 1)
    # Светящаяся часть — яркая и цветная: сначала яркие (верхние 30 %), среди них — самые цветные (верхняя треть).
    bright = lum >= np.quantile(lum, 0.7)
    score = np.where(bright, lum * (0.25 + satp), -1)
    top = a[score >= np.quantile(score[bright], 0.66)] if bright.sum() >= 6 else a[bright]
    c = top.mean(axis=0)
    mx, mn = c.max(), c.min()
    if mx < 50:
        return ''
    sat = (mx - mn) / mx
    if sat < 0.2:
        return None
    # Чистый цвет (как в шейдере: hue = rgb / max), чуть насыщеннее.
    h = c / mx
    h = 1 - (1 - h) * 1.15
    h = np.clip(h, 0, 1) * 255
    return hexc(*h)


def glow_diff(on, off):
    """Цвет пикселей, которые во включённом виде стали ярче; None — загорается белым; '' — разницы нет."""
    a = np.asarray(on.convert('RGBA')).astype(np.float32)
    b = np.asarray(off.convert('RGBA')).astype(np.float32)
    w = np.array([0.299, 0.587, 0.114], np.float32)
    la, lb = a[..., :3] @ w, b[..., :3] @ w
    mask = (a[..., 3] > 200) & (la - lb > 25)
    if mask.sum() < 6:
        return ''
    c = a[..., :3][mask].mean(axis=0)
    mx, mn = c.max(), c.min()
    if mx < 50:
        return ''
    if (mx - mn) / mx < 0.18:
        return None
    return hexc(*(c / mx * 255))


def soften(hx, k=0.7):
    """Цвет мягче: к белому на 1 − k (у всего, что не задумано цветным)."""
    if not hx:
        return hx
    r, g, b = (int(hx[i:i + 2], 16) for i in (1, 3, 5))
    return hexc(*(255 - (255 - v) * k for v in (r, g, b)))


def from_map(col):
    r, g, b = col >> 16 & 255, col >> 8 & 255, col & 255
    mx, mn = max(r, g, b), min(r, g, b)
    if mx < 40 or (mx - mn) / mx < 0.2:
        return None
    k = 255 / mx
    return hexc(r * k, g * k, b * k)


def main():
    dump = json.loads((ROOT / 'lightdump.json').read_text('utf-8'))
    res = co.Res()
    ru, en = co.langs(res)
    IMG.mkdir(parents=True, exist_ok=True)
    (SITE / 'data').mkdir(parents=True, exist_ok=True)
    shutil.copy(CC / '_mcref/vanilla-1.20.1/assets/minecraft/textures/block/stone_bricks.png', IMG / '_stone.png')
    out = []
    for i, d in enumerate(sorted(dump, key=lambda x: x['id'])):
        bid = d['id']
        ns, p = co.split(bid)
        key = f'block.{ns}.{p}'
        img, kind = None, 'none'
        LIT.clear()
        LIT.update(parse_state(d.get('best_state')))
        diff = ''
        if not d['invisible']:
            try:
                img, kind, _ = co.render_block(res, bid)
                # Выключенный вид — для сравнения: цвет того, что загорается.
                off = {k: 'false' for k, v in LIT.items() if k in SWITCH_KEYS and v == 'true'}
                if img is not None and off:
                    LIT.update(off)
                    img_off, _, _ = co.render_block(res, bid)
                    if img_off is not None and img_off.size == img.size:
                        diff = glow_diff(img, img_off)
            except Exception as ex:  # кривая модель мода — без картинки
                print('render failed', bid, ex)
        fn = re.sub(r'[^a-z0-9_.-]', '_', bid.replace(':', '__')) + '.png'
        if img is not None:
            img.save(IMG / fn, optimize=True)
        # Предложение: пресет по названию, иначе — цвет загорающейся части (вкл/выкл) или текстуры, мягче.
        name = d['name'] if not str(d['name']).startswith('block.') else (ru.get(key) or en.get(key) or p.replace('_', ' '))
        preset, src = None, ''
        if d['max'] <= 3:
            src = 'слабый свет — цвет не заметен'
        else:
            preset, src = P.classify(bid, name, ns)
            # Цвет, который задаёт сам мод (ХИС, цветной свет S5 Glow Sticks): он точнее правила по названию.
            if 'flrender' in d and ns in ('glow_sticks', 's5glow') and bid not in P.BY_ID:
                preset, src = from_flrender(d['flrender']), 'цвет задан модом'
            if preset == '':
                preset = None
                if diff not in ('', None):
                    preset, src = soften(diff), 'загорается этим цветом (вкл/выкл)'
                elif diff is None:
                    src = 'загорается белым (вкл/выкл)'
                elif 'flrender' in d:
                    preset, src = from_flrender(d['flrender']), 'сейчас в FL Render'
                elif not GLOWY.search(p + ' ' + name.lower()):
                    src = 'редкий блок — без цвета'
                elif img is not None and (c := from_image(img)):
                    preset, src = soften(c), 'по текстуре (мягче)'
                elif not src:
                    src = 'по текстуре — белый'
        prop = P.PRESET_COLOR.get(preset, preset) if preset else None
        e = {'id': bid, 'ns': ns, 'mod': res.modnames.get(ns, ns),
             'name': name,
             'en': en.get(key, ''), 'max': d['max'], 'levels': d['levels'], 'switch': d['lit_states'] < d['states'],
             'img': ('img/' + fn) if img is not None else None, 'invisible': d['invisible'] or d['air'],
             'prop': prop, 'preset': preset if preset in P.PRESET_COLOR else None, 'src': src}
        if diff:
            e['glow'] = diff
        if 'flrender' in d:
            e['cur'] = from_flrender(d['flrender'])
            if not d['flrender']['with_shaders']:
                e['noshaders'] = True
        out.append(e)
        if i % 100 == 0:
            print(i, '/', len(dump), flush=True)
    # Сильные источники — вперёд (слабый свет 1–3 обычно ставят моды, чтобы модель не была чёрной).
    out.sort(key=lambda e: (e['max'] <= 3, e['id']))
    (SITE / 'data/presets.json').write_text(json.dumps([{'id': i, 'name': n, 'color': c} for i, n, c in P.PRESETS],
                                                       ensure_ascii=False), 'utf-8')
    (SITE / 'data/blocks.json').write_text(json.dumps(out, ensure_ascii=False, separators=(',', ':')), 'utf-8')
    props = sum(1 for e in out if e['prop'])
    print('блоков:', len(out), ' с предложенным цветом:', props, ' без цвета:', len(out) - props,
          ' без картинки:', sum(1 for e in out if not e['img']))


if __name__ == '__main__':
    main()
