# FL Render — указатель кода

Создано `python _family/tools/mapgen.py` 15.11.2026, версия 0.4.0-1.20.1-forge, коммит `8a1803d`.
**Руками не править**: пересоздаётся скриптом. Смысл, потоки работы и рецепты — в `docs/MAP.md`.
Разбор по шаблонам, без компиляции: необычно оформленная регистрация может отсутствовать в списках.

Всего: 38 файлов, 4433 строк Java.

## 1. Пакеты

| Пакет | Файлов | Строк |
|---|---:|---:|
| `faygolover.flrender` | 1 | 21 |
| `faygolover.flrender.api` | 5 | 215 |
| `faygolover.flrender.client` | 3 | 311 |
| `faygolover.flrender.client.embeddium` | 2 | 127 |
| `faygolover.flrender.config` | 2 | 176 |
| `faygolover.flrender.flashlight` | 5 | 1009 |
| `faygolover.flrender.light` | 7 | 1687 |
| `faygolover.flrender.mixin` | 4 | 125 |
| `faygolover.flrender.mixin.embeddium` | 2 | 51 |
| `faygolover.flrender.render` | 7 | 711 |

## 2. Классы

### `faygolover.flrender`
- `FlRenderMod`, 21 — FL Render — общая клиентская графика модов семейства.

### `faygolover.flrender.api`
- `FlRender`, 62 — Точка входа для модов.
- `LightBeams`, 66 — Лучи фонарей других модов для настоящего света фонаря (техника flrender.flashlight, без шейдерпака): блоки в луче освещаются при рисовании, сквозь стены свет не идёт, сущности получают уровень света как от факела.
- `Quality` (enum), 19 — Сколько графика эффектов может стоить компьютеру.
- `Technique`, 61 — Одна графическая техника мода (например, «объёмный дым» аномалий).
- `Toggle` (enum), 7 — Одна техника: как велит качество, всегда включена или всегда выключена.

### `faygolover.flrender.client`
- `AutoDetect`, 172 — Определялка на первом запуске: картошка, нормальный ПК или «NASA-PC».
- `ClientSetup`, 37 — Клиент: шейдеры, проходы, определялка, вкладка в настройках графики Embeddium.
- `Oculus`, 102 — Oculus / Iris через их API, без жёсткой зависимости: включён ли шейдерпак и идёт ли проход теней.

### `faygolover.flrender.client.embeddium`
- `EmbeddiumCompat`, 13 — Своя вкладка в «Настройках графики» Embeddium (как у True Darkness, Sodium Extras).
- `FlRenderPage`, 114 — Вкладка «FL Render»: общий пресет качества и по группе на каждый мод с его техниками («Авто» — как велит пресет, «Вкл», «Выкл»).

### `faygolover.flrender.config`
- `ColorRange` (enum), 21 — Дальность цветного света (подкраски от источников), в чанках: как дальность прорисовки — свет и его цвет видны одинаково далеко; меньше — для слабого компьютера.
- `Settings`, 155 — config/flrender.properties — свой файл, не ForgeConfigSpec: техники регистрируют другие моды, и список заранее не известен.

### `faygolover.flrender.flashlight`
- `Beam` (record), 48 — Один луч фонаря на этот кадр: откуда (мир), куда (единичный вектор), дальность, сила и форма пятна — три вложенных конуса как у Survivor's Arsenal (ядро, спад, широкий ореол; косинусы углов), цвет лампы.
- `ChunkPatch`, 313 — Свет фонаря в шейдере чанков Embeddium (sodium:blocks/block_layer_opaque): исходник дописывается при загрузке (миксин на ShaderLoader.getShaderSource), значения — при включении программы (ChunkShaderInterface.setupState).
- `Flashlight`, 172 — Настоящий свет фонаря (без шейдерпака): конус от лампы освещает блоки при рисовании — текстура × (свет мира + свет фонаря), как от факела, — а сущности и блоки-сущности в луче получают уровень света, как будто рядом факел.
- `Occupancy`, 320 — Что задерживает луч фонаря, по половинкам блока: на блок — байт, бит на каждую из 8 половинок (x + 2y + 4z).
- `SaBeams`, 156 — Лучи фонарей Survivor's Arsenal (sa_combat:flashlight) — так же, как их собирает сам мод (FlashlightBeams/FlashlightBeam.resolve): включённый фонарь в любой руке у игроков ближе 72 блоков, (порядок и лимит — Flashlight); место лам…

### `faygolover.flrender.light`
- `ColorTable`, 236 — Цвета источников света: блоки (светят, если у состояния есть свой ванильный свет) и предметы (в руках, брошенные, летящие).
- `ColorVolume`, 797 — Цветной свет вокруг камеры (0.3.3, 30.10: разреженный — до дальности прорисовки, как ванильный свет).
- `Defaults`, 73 — Цвета по умолчанию — только основные и самые частые ванильные источники; остальное дописывается в файл.
- `ItemLights`, 48 — Свет от предметов: в руках (у игроков и мобов), лежащих на земле и летящих (брошенная ХИС).
- `LightJobs`, 299 — Расчёт объёма цветного света (фоновый поток).
- `LightSources`, 55 — Подвижные источники кадра: эффекты модов (WorldLight#provider) и предметы (ItemLights).
- `SectionCache`, 179 — Что цветной свет знает о мире, по секциям 16³ (только клиент, основной поток): сколько свет теряет в каждом блоке (как у ванильного света: 15 — не проходит), небесный свет (днём солнце перебивает цвет) и цветные источники.

### `faygolover.flrender.mixin`
- `BlockEntityRenderDispatcherMixin`, 24 — Свет фонаря: блок-сущность (сундук, табличка, ...) в луче освещена, как будто рядом факел.
- `ClientLevelMixin`, 30 — Цветной свет и свет фонаря узнают, что в мире поменялся блок или свет (загрузка чанка, пакет света).
- `EmbeddiumMixinPlugin`, 47 — Миксины в Embeddium — только если он стоит.
- `EntityRendererMixin`, 24 — Свет фонаря: сущность в луче освещена, как будто рядом факел (Flashlight#entityLight).

### `faygolover.flrender.mixin.embeddium`
- `ChunkShaderInterfaceMixin`, 30 — Свет фонаря: места его значений в программе чанков и загрузка значений, когда программа включается.
- `ShaderLoaderMixin`, 21 — Свет фонаря: дописать шейдер чанков Embeddium при загрузке (ChunkPatch).

### `faygolover.flrender.render`
- `CameraPose`, 21 — Поворот камеры, как у мира (без «крена»).
- `FlShaders`, 50 — Свои шейдеры библиотеки.
- `LatePass`, 245 — Рисование эффектов, когда включён шейдерпак (Oculus).
- `PostFx`, 69 — Свой постэффект (цепочка shaders/post/*.json) поверх кадра — не занимает слот постэффекта игры (его держат другие моды).
- `SceneDepth`, 79 — Глубина кадра, которую шейдеры могут читать, пока рисуют в кадр: раз за кадр упакована в цветную текстуру (24 бита в r, g, b; текстуру нельзя читать, пока в её же буфер рисуют) — и проекция мира, чтобы вернуть из неё расстояния.
- `ScreenPass`, 87 — Полноэкранные проходы: один квад на всю цель с шейдером, какими бы ни были матрицы в этот момент.
- `WorldLight`, 160 — Цветной свет — только картинка: уровни света мира не меняются.

Классов без описания: 0 из 38.

## 3. Подписки на события

| Событие | Кто слушает |
|---|---|
| `ChunkEvent.Load` | `ColorVolume.onChunkLoad` |
| `ChunkEvent.Unload` | `ColorVolume.onChunkUnload` |
| `RegisterShadersEvent` | `FlShaders.onRegisterShaders` |
| `RenderLevelStageEvent` | `Flashlight.onRenderLevelStage`, `LatePass.onRenderLevelStage` |
| `TickEvent.ClientTickEvent` | `AutoDetect.onClientTick` |

## 4. Сеть

Нет.

## 5. Команды

Нет.

## 6. Данные

- свои файлы на диске: `Settings`
- свои файлы на диске: `ColorTable`

## 7. Конфиги

Нет.

## 8. Что берёт из RP Core и что ему даёт

Не использует.

## 9. Связи с другими модами

Проверки `isLoaded`: `embeddium` (ClientSetup); `sa_combat` (Flashlight).

Отражение (`Class.forName`):
- `SaBeams` → `com.ogaba.sa_survival.item.common.FlashlightItem`

Миксины (5):
- `ChunkShaderInterfaceMixin` (client) → ChunkShaderInterface.class, remap = false
- `ShaderLoaderMixin` (client) → ShaderLoader.class, remap = false
- `BlockEntityRenderDispatcherMixin` (client) → BlockEntityRenderDispatcher.class
- `ClientLevelMixin` (client) → ClientLevel.class
- `EntityRendererMixin` (client) → EntityRenderer.class

## 10. Зарегистрированные id

Нет или регистрируются не строковыми литералами.
