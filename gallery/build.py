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
co.MODS = Path(os.environ['APPDATA']) / 'ElyPrismLauncher/instances/DEP_S5_rev/minecraft/mods'

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

DYES = {
    'white': '#F4F4F4', 'orange': '#FF8A1E', 'magenta': '#E040E0', 'light_blue': '#5AC8FF', 'yellow': '#FFE030',
    'lime': '#8CFF30', 'pink': '#FF7AB4', 'gray': '#8C8C8C', 'light_gray': '#C8C8C8', 'cyan': '#20E0E0',
    'purple': '#A040FF', 'blue': '#3050FF', 'brown': '#A0602C', 'green': '#30C030', 'red': '#FF2A1A', 'black': '#303030',
}
FLAME, SOUL = '#FF9A3C', '#3CC8FF'
# Невидимые и без картинки — по названию.
BY_ID = {
    'minecraft:light': None, 'minecraft:lava': '#FF5A10', 'minecraft:end_portal': '#40E0B0', 'minecraft:end_gateway': '#B060FF',
    'exposure:flash': None, 'sa_decor:work_light_beam': None, 'pneumaticcraft:kerosene_lamp_light': '#FFB060',
    'alexscaves:ambersol_light': '#FFB040', 'alexscaves:acid': '#9CFF30', 'blocksyouneed_luna:liquid_illumina': None,
    'securitycraft:fake_lava_block': '#FF5A10', 'mekanism:bounding_block': None, 'supplementaries:block_generator': None,
    'amendments:skull_candle': FLAME, 'amendments:skull_candle_wall': FLAME,
    'amendments:skull_candle_soul': SOUL, 'amendments:skull_candle_soul_wall': SOUL,
}


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
        if not d['invisible']:
            try:
                img, kind, _ = co.render_block(res, bid)
            except Exception as ex:  # кривая модель мода — без картинки
                print('render failed', bid, ex)
        fn = re.sub(r'[^a-z0-9_.-]', '_', bid.replace(':', '__')) + '.png'
        if img is not None:
            img.save(IMG / fn, optimize=True)
        # Предложение.
        src, prop = '', None
        if d['max'] <= 3 and 'flrender' not in d:
            src = 'слабый свет — цвет не заметен'
        elif 'flrender' in d:
            prop, src = from_flrender(d['flrender']), 'сейчас в FL Render'
        elif bid in BY_ID:
            prop, src = BY_ID[bid], 'по названию'
        else:
            m = re.search(r'(?:^|_)(light_blue|light_gray|white|orange|magenta|yellow|lime|pink|gray|cyan|purple|blue|brown|green|red|black)(?:_|$)', p)
            if 'soul' in p:
                prop, src = SOUL, 'по названию (душ)'
            elif 'redstone' in p and 'lamp' not in p:
                prop, src = '#FF2A1A', 'по названию (редстоун)'
            elif re.search(r'(?:^|_)(candles?|torch|campfire|fire|brazier|candelabra|kerosene|oil_lamp|fireplace)(?:_|$)', p):
                prop, src = FLAME, 'по названию (огонь)'
            elif m and m.group(1) not in ('white', 'gray', 'light_gray', 'black'):
                prop, src = DYES[m.group(1)], 'по названию (цвет)'
            elif img is not None and (c := from_image(img)) != '':
                prop, src = c, 'по текстуре'
            elif d.get('map_color'):
                prop, src = from_map(d['map_color']), 'по цвету на карте'
        e = {'id': bid, 'ns': ns, 'mod': res.modnames.get(ns, ns),
             'name': d['name'] if not str(d['name']).startswith('block.') else (ru.get(key) or en.get(key) or p.replace('_', ' ')),
             'en': en.get(key, ''), 'max': d['max'], 'levels': d['levels'], 'switch': d['lit_states'] < d['states'],
             'img': ('img/' + fn) if img is not None else None, 'invisible': d['invisible'] or d['air'],
             'prop': prop, 'src': src}
        if 'flrender' in d:
            e['cur'] = from_flrender(d['flrender'])
            if not d['flrender']['with_shaders']:
                e['noshaders'] = True
        out.append(e)
        if i % 100 == 0:
            print(i, '/', len(dump), flush=True)
    # Сильные источники — вперёд (слабый свет 1–3 обычно ставят моды, чтобы модель не была чёрной).
    out.sort(key=lambda e: (e['max'] <= 3, e['id']))
    (SITE / 'data/blocks.json').write_text(json.dumps(out, ensure_ascii=False, separators=(',', ':')), 'utf-8')
    props = sum(1 for e in out if e['prop'])
    print('блоков:', len(out), ' с предложенным цветом:', props, ' без цвета:', len(out) - props,
          ' без картинки:', sum(1 for e in out if not e['img']))


if __name__ == '__main__':
    main()
