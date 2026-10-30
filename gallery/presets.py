# Пресеты цветного света и разбор блоков по названию (id и русское имя). Насыщенность пресета = сила подкраски:
# в шейдере цвет усиливается с сохранением яркости, поэтому белёсый цвет почти не меняет кадр, а чистый — красит.
import re

PRESETS = [
    # id, название, цвет
    ('fire', 'Огонь (костёр, факел, печь)', '#F59A55'),
    ('candle', 'Свеча', '#E8B478'),
    ('kerosene', 'Керосин, масло, фонарь со свечой', '#D9B47A'),
    ('soul', 'Огонь душ', '#8CCAE3'),
    ('redstone', 'Редстоун', '#E04A3C'),
    ('lava', 'Лава, магма', '#FF7030'),
    ('incandescent', 'Лампа накаливания (тёплая)', '#E8D2B4'),
    ('fluorescent', 'Люминесцентная лампа (холодная)', '#DCE6F2'),
    ('led', 'Светодиод / современная лампа', '#E4EAF2'),
    ('flood', 'Прожектор, рабочий свет (галоген)', '#EEE4D2'),
    ('sodium', 'Уличный фонарь (натриевый)', '#F0B060'),
    ('mercury', 'Уличный фонарь (ртутный, белый)', '#D8E8E2'),
    ('glowstone', 'Светокамень, иллюмина', '#EED08E'),
    ('sea', 'Морской фонарь, холодный кристалл', '#C4E2EE'),
    ('screen', 'Экран, монитор, телевизор', '#A8C4E6'),
    ('alarm', 'Аварийный красный, сигнализация', '#F0302A'),
    ('exit', 'Табличка «Выход» (зелёная)', '#5ADC72'),
    ('chem_green', 'Купорос, кислота (зелёный)', '#7CFF5A'),
    ('neon', 'Неон (газ неон)', '#FF5A28'),
    ('argon', 'Аргон (фиолетовый)', '#B47CFF'),
    ('helium', 'Гелий (розовато-оранжевый)', '#FFB49A'),
    ('krypton', 'Криптон (бело-голубой)', '#E2EAFF'),
    ('xenon', 'Ксенон (голубовато-белый)', '#C8D6FF'),
    ('radon_gas', 'Радон (красный)', '#FF4A64'),
    ('uv', 'Ультрафиолет', '#9A6CFF'),
    ('ender', 'Энд, пустота (бирюзовый)', '#40E0B0'),
    ('amethyst', 'Аметист, фиолетовый кристалл', '#C07CFF'),
    ('bio_blue', 'Биолюминесценция (голубая)', '#6CC8FF'),
    ('bio_green', 'Биолюминесценция (зелёная)', '#8CFF8C'),
]
PRESET_COLOR = {p[0]: p[2] for p in PRESETS}

# Цвет красителя для цветных ламп (неон-лампы, кристаллы, радоновые): насыщенно — ради этого их и ставят.
DYES = {
    'orange': '#FF8A1E', 'magenta': '#E040E0', 'light_blue': '#5AC8FF', 'yellow': '#FFE030', 'lime': '#8CFF30',
    'pink': '#FF7AB4', 'cyan': '#20E0E0', 'purple': '#A040FF', 'blue': '#3050FF', 'brown': '#C07838',
    'green': '#30D030', 'red': '#FF2A1A',
}
DYE_RE = re.compile(r'(?:^|_)(light_blue|light_gray|white|orange|magenta|yellow|lime|pink|gray|cyan|purple|blue|brown|green|red|black)(?:_|$)')
RU_DYE = [('голуб', 'light_blue'), ('светло-сер', 'light_gray'), ('бирюз', 'cyan'), ('пурпур', 'magenta'), ('фиолет', 'purple'),
          ('розов', 'pink'), ('лаймов', 'lime'), ('оранж', 'orange'), ('жёлт', 'yellow'), ('желт', 'yellow'),
          ('зелён', 'green'), ('зелен', 'green'), ('синий', 'blue'), ('синяя', 'blue'), ('синее', 'blue'), ('коричн', 'brown'),
          ('красн', 'red'), ('бел', 'white'), ('сер', 'gray'), ('чёрн', 'black'), ('черн', 'black')]

# Моды, где свет — побочный (машины, трубы, техника): без цвета, чтобы не красить случайно и не тратить расчёт.
MACHINE_NS = {'mekanism', 'mekanismgenerators', 'mekanismelements', 'csaugmentations', 'pneumaticcraft'}

# Отдельные блоки (сравнены руками).
BY_ID = {
    'minecraft:light': None, 'minecraft:lava': 'lava', 'minecraft:lava_cauldron': 'lava', 'minecraft:magma_block': 'lava',
    'securitycraft:fake_lava_block': 'lava', 'securitycraft:reinforced_lava_cauldron': 'lava',
    'minecraft:end_portal': 'ender', 'minecraft:end_gateway': 'ender', 'minecraft:end_rod': 'led',
    'minecraft:sea_lantern': 'sea', 'securitycraft:reinforced_sea_lantern': 'sea', 'minecraft:beacon': 'sea',
    'minecraft:glowstone': 'glowstone', 'securitycraft:reinforced_glowstone': 'glowstone', 'mekanism:block_refined_glowstone': 'glowstone',
    'blocksyouneed_luna:barrel_glowstone': 'glowstone',
    'minecraft:shroomlight': 'kerosene', 'securitycraft:reinforced_shroomlight': 'kerosene',
    'minecraft:ochre_froglight': '#FFE29A', 'securitycraft:reinforced_ochre_froglight': '#FFE29A',
    'minecraft:verdant_froglight': '#C8FFB0', 'securitycraft:reinforced_verdant_froglight': '#C8FFB0',
    'minecraft:pearlescent_froglight': '#FFD0F0', 'securitycraft:reinforced_pearlescent_froglight': '#FFD0F0',
    'alexscaves:carmine_froglight': '#FFB4A0',
    'minecraft:redstone_lamp': None, 'securitycraft:reinforced_redstone_lamp': None,
    'minecraft:jack_o_lantern': 'candle', 'minecraft:crying_obsidian': 'amethyst', 'minecraft:respawn_anchor': 'amethyst',
    'minecraft:amethyst_cluster': 'amethyst', 'minecraft:large_amethyst_bud': 'amethyst', 'minecraft:medium_amethyst_bud': 'amethyst',
    'minecraft:glow_lichen': 'bio_green', 'quark:glow_lichen_growth': 'bio_green', 'quark:potted_glow_lichen_growth': 'bio_green',
    'minecraft:sculk_catalyst': 'bio_blue', 'minecraft:sculk_sensor': 'bio_blue', 'minecraft:calibrated_sculk_sensor': 'bio_blue',
    'minecraft:enchanting_table': None, 'minecraft:brewing_stand': None, 'minecraft:dragon_egg': None,
    'chemlib:neon_lamp_block': 'neon', 'chemlib:argon_lamp_block': 'argon', 'chemlib:helium_lamp_block': 'helium',
    'chemlib:krypton_lamp_block': 'krypton', 'chemlib:xenon_lamp_block': 'xenon', 'chemlib:radon_lamp_block': 'radon_gas',
    'pneumaticcraft:uv_light_box': 'uv', 'pneumaticcraft:kerosene_lamp_light': 'kerosene', 'pneumaticcraft:kerosene_lamp': 'kerosene',
    'alexscaves:ambersol_light': '#FFC060', 'alexscaves:acid': 'chem_green', 'alexscaves:tesla_bulb': '#D8B8FF',
    'alexscaves:siren_light': 'alarm', 'alexscaves:magnetic_light': '#FF7080', 'alexscaves:sundrop': '#FFE8A0',
    'alexscaves:hologram_projector': 'screen', 'alexscaves:bioluminescent_torch': 'bio_blue', 'alexscaves:bioluminescent_wall_torch': 'bio_blue',
    'supplementaries:sconce_green': 'chem_green', 'supplementaries:sconce_wall_green': 'chem_green',
    'supplementaries:redstone_illuminator': 'incandescent', 'supplementaries:blackstone_lamp': '#9CF0FF',
    'supplementaries:end_stone_lamp': '#F4FFC0', 'supplementaries:lumisene': 'glowstone',
    'quark:blaze_lantern': 'fire', 'quark:duskbound_lantern': '#C4BCFF', 'quark:myalite_crystal': 'amethyst',
    'quark:glowberry_sack': 'kerosene', 'quark:paper_lantern': 'candle', 'quark:paper_lantern_sakura': 'candle',
    'blocksyouneed_luna:paper_lantern': '#FF8A5A', 'xkdeco:paper_lantern': 'candle', 'xkdeco:festival_lantern': '#FF9A40',
    'xkdeco:red_lantern': '#C8505A', 'horror_element_mod:redlantern': '#C8505A', 'horror_element_mod:redlanternchain': '#C8505A',
    'xkdeco:factory_warning_lamp': 'alarm', 'xkdeco:glow_item_frame_cover': None,
    'xkdeco:tech_lamp_block': 'led', 'xkdeco:tech_lamp_slab': 'led', 'xkdeco:translucent_lamp_block': 'led',
    'xkdeco:translucent_lamp_slab': 'led', 'xkdeco:translucent_lamp_stairs': 'led',
    'xkdeco:emergency_exit': 'exit', 'xkdeco:emergency_exit_ground': 'exit', 'xkdeco:emergency_exit_left': 'exit',
    'xkdeco:emergency_exit_right': 'exit', 'xkdeco:small_sign_ground': 'exit', 'xkdeco:small_sign_left': 'exit',
    'xkdeco:small_sign_right': 'exit',
    'soviet:innerdeco/lamp/red_lamp': 'alarm', 'soviet:innerdeco/lamp/inc_lamp': 'incandescent',
    'rpmedicine:surgical_lamp': 'led', 'cuffed:reinforced_lamp': 'led',
    'doomsday_decoration:green_screen_steps': None, 'doomsday_decoration:greenscreenstaircase': None, 'doomsday_decoration:greenwall': None,
    'securitycraft:alarm': 'alarm', 'mekanism:industrial_alarm': 'alarm',
    'fire_extinguisher:fire_alarm_bell': 'alarm', 'fire_extinguisher:fire_alarm_siren': 'alarm',
    'fire_extinguisher:fire_alarm_smoke_detector': None, 'fire_extinguisher:fire_alarm_smoke_detector_silent': None,
    'fire_extinguisher:fire_extinguisher_sign': None, 'fire_extinguisher:fire_extinguisher_sign_left': None,
    'fire_extinguisher:fire_extinguisher_sign_right': None,
    'exposure:flash': None, 'sa_decor:work_light_beam': None, 'blocksyouneed_luna:liquid_illumina': 'glowstone',
    'mekanism:bounding_block': None, 'supplementaries:block_generator': None, 'supplementaries:crystal_display': 'screen',
    'betterarcheology:radiance_totem': 'glowstone', 'biomesoplenty:glowflower': 'bio_blue', 'biomesoplenty:potted_glowflower': 'bio_blue',
    'biomesoplenty:lumaloop': '#FFE070', 'biomesoplenty:lumaloop_plant': '#FFE070',
    'pneumaticcraft:wall_lamp_black': 'incandescent', 'pneumaticcraft:wall_lamp_inverted_black': 'incandescent',
    'refurbished_furniture:black_lamp': 'incandescent', 'refurbished_furniture:gray_lamp': 'incandescent',
    'refurbished_furniture:light_gray_lamp': 'incandescent', 'refurbished_furniture:white_lamp': 'incandescent',
    'blocksyouneed_luna:anticrystal': 'sea', 'blocksyouneed_luna:elder_lantern': 'sea',
    'blocksyouneed_luna:glowescent_lights': 'incandescent',
    'scpo:scp_006': None, 'glow_sticks:glow_stick_light': None, 'glow_sticks:glow_stick_light_water': None,
}

# Правила по словам (порядок важен). Слова в id через «_», в русском имени — подстрокой.
RULES = [
    ('soul', r'soul', r'душ'),
    ('redstone', r'redstone', r'редстоун'),
    ('candle', r'candles?|candle_holder|candlestick|candelabra|skull_candle', r'свеч|подсвечн'),
    ('fire', r'torch|campfire|fire_pit|fire|brazier|bonfire|fireplace', r'факел|костёр|костер|кострищ|жаровн|камин|огонь'),
    ('fire', r'stove|furnace|oven|firebox|brew_oven|smoker', r'печь|плита|топка|пивоварн'),
    ('kerosene', r'kerosene|oil_lamp|oil|lantern', r'керосин|масля'),
    ('fluorescent', r'fluo|flourecent|florescent|fluorescent|tube_light|tube', r'люминесц|флюоресц|лампа дневного'),
    (None, r'flood\w*|spot\w*|work_light|searchlight|projector|reflector|floodlamp', r'прожектор|рабоч'),
    ('sodium', r'street\w*|park\w*', r'уличн|парков'),
    ('exit', r'exit\w*', r'выход'),
    ('alarm', r'alarm|siren|warning|emergency|beacon_light', r'аварийн|сигнализ|сирен|предупрежд'),
    ('screen', r'screen|monitor|tv|tva|tvat|television|computer|laptop|pc|crt|display|teclado|oldtv', r'экран|монитор|телевиз|тв |компьют|ноутбук|дисплей'),
    ('glowstone', r'glowstone|illumina', r'светокам|иллюмин'),
    ('lava', r'lava|magma', r'лава|лавов|магм'),
]
LAMP_RE = re.compile(r'(?:^|_)(lamps?|light|lights|lantern|bulb|chandelier|sconce|pendant|ceiling|fan|light_bar|lamp_block)(?:_|$)')
LAMP_RU = re.compile(r'ламп|светильн|фонар|люстр|бра\b|лампочк|вентилятор')


def dye_of(path, name):
    m = DYE_RE.search(path)
    if m:
        return m.group(1)
    low = name.lower()
    for k, v in RU_DYE:
        if k in low:
            return v
    return None


def classify(bid, name, ns):
    """-> (пресет или '#RRGGBB' или None (без цвета) или '' (не понять), откуда)."""
    path = bid.split(':', 1)[1]
    low = name.lower()
    if bid in BY_ID:
        return BY_ID[bid], 'сравнено вручную'
    for preset, en, ru in RULES:
        if re.search(r'(?:^|[_/])(' + en + r')(?:[_/]|$)', path) or re.search(ru, low):
            if preset == 'kerosene' and ('lantern' in path or 'фонар' in low) and not re.search(r'kerosene|oil|керосин|масля', path + low):
                # Обычный фонарь со свечой / пламенем внутри (ваниль, латунь, медь, сталь): как свеча, чуть теплее.
                return 'kerosene', 'по названию (фонарь)'
            return preset, 'по названию' if preset else 'по названию — белый свет (как у тебя у рабочих фонарей)'
    dye = dye_of(path, name)
    is_lamp = LAMP_RE.search(path) or LAMP_RU.search(low) or re.search(r'crystal_lamp|radon_lamp|wall_lamp', path)
    if is_lamp:
        if dye and dye in DYES:
            return DYES[dye], 'цветная лампа'
        if re.search(r'modern|tech|titanium|reinforced', path):
            return 'led', 'по названию (современная)'
        return 'incandescent', 'по названию (лампа)'
    if ns in MACHINE_NS:
        return None, 'машина — свет побочный'
    return '', ''
