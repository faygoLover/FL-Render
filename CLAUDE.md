# FL Render

Мод Minecraft Forge 1.20.1 (Java 17), `flrender`, пакет `faygolover.flrender`. Общая клиентская графика модов семейства:
Oculus, поздний проход, глубина сцены, цветной свет от эффектов, постэффекты, единые настройки качества.
**С автором общаемся по-русски.** План и решения — `docs/00_plan.md`.

## Что есть (0.3.0)
- `api.FlRender` — `technique(modid, key, fromQuality)`, `quality()`, `shaderPack()`, `shadowPass()`.
  `Quality` LOW/MEDIUM/HIGH/ULTRA, `Toggle` AUTO/ON/OFF, `Technique.on()`.
- `config.Settings` — `config/flrender.properties` (пресет, `quality_auto`, `fps_checked`, `detected`, `technique.<modid>.<key>`).
- `client.AutoDetect` — первый запуск: GL_RENDERER + видеопамять (NVX/ATI) + ядра + heap → пресет; в первой игре 10 с
  замера FPS после 10 с прогрева, < 25 → на ступень ниже (только если пресет от определялки). Сообщение в чат.
- `client.embeddium` — вкладка «FL Render» в настройках графики Embeddium (`OptionGUIConstructionEvent`): пресет и по
  группе на мод. Подписи техник — в lang самих модов: `flrender.technique.<modid>.<key>` и `.tooltip`.
- `render.LatePass` — с шейдерпаком этапы мира запоминаются и проигрываются на AFTER_LEVEL (моды: `register`,
  `deferred`, `replayed`, `deferEntity`); раз за кадр «кадр готов»: SceneDepth → WorldLight → `onFrameReady` хуки.
- `render.SceneDepth` (`need`), `render.PostFx` (свой PostChain), `render.ScreenPass`, `render.CameraPose`.
- **Цветной свет** (0.2.0, вместо прежнего экранного света по расстоянию): `light.ColorVolume` — куб вокруг камеры
  (средне 48, высоко 64, ультра 96 блоков), секции кэшируются (`light.SectionCache`: потеря света по блокам как у
  ванильного, небесный свет, цветные источники; грязные — по миксину `ClientLevel.sendBlockUpdated` /
  `setSectionDirtyWithNeighbors`), расчёт в фоне (`light.LightJobs`: растекание по уровням, сквозь стены не идёт).
  Две 3D-текстуры: подкраска от блоков (max по каналу, a = небо) и добавка от подвижных источников (сумма).
  Проход `color_light` в `render.WorldLight` (на «кадр готов»), 3D-текстуры на блоках 5 и 6.
  Источники: блоки и предметы — `light.ColorTable` (`config/flrender_colors.json`, по умолчанию только основная ваниль;
  моды — `ColorTable.block/item`), эффекты — `WorldLight.provider` (точки, прямоугольники проёмов), предметы в руках /
  на земле / в полёте — `light.ItemLights`. Техники `flrender.color_light`, `flrender.held_light` (с паком не светит);
  ванильные блоки с паком не красятся (`shaders: false`).
- **Свет фонаря SA** (0.3.0, `flashlight/`): техника `flrender.flashlight` (со «Среднего», с паком выключена).
  `SaBeams` — лучи `sa_combat:flashlight` по логике SA (до 4, свои и чужие, MethodHandle на `FlashlightItem`);
  `Beam` — форма (ядро/спад/ореол) и затухание, одна формула для шейдера и процессора; `Occupancy` — куб 96³ вокруг
  камеры, байт на блок = 8 половинок (занята, если форма столкновения покрывает ≥ ¼; стекло/решётки — нет),
  3D-текстура R8 с кольцевой адресацией на блоке 13; `ChunkPatch` — дописывает `sodium:blocks/block_layer_opaque`
  (миксины `mixin/embeddium/*`, конфиг `flrender.embeddium.mixins.json`): текстура × цвет вершины × (карта света + фонарь),
  проход по половинкам к лампе — сквозь стены нет; сущности и блоки-сущности — `EntityRendererMixin` /
  `BlockEntityRenderDispatcherMixin` (уровень как у факела, до 14). `FlRender.flashlightLight()` — для RP Fixes
  (круг SA тогда с малым усилением, `sa_flashlight_soft`). Обновление — на `AFTER_SKY`.
- **Цветной свет до дальности прорисовки** (0.3.3, 30.10): подкраска разреженная — кирпичи 18³ (секция + блок соседей)
  только у секций рядом с цветными источниками, в атласе (блок 5, растёт 256 → потолок качества 1024/2048/4096); индекс
  R16UI по секциям в дальности (блок 7, номер + 1). Дальность — прорисовка ∧ настройка «Дальность цветного света» в
  чанках (`color_light_range`) ∧ потолок качества 12/24/32 чанка, по высоте ±2/3/4 секции. Секции проверяются по палитре
  (`SectionCache.mayHaveSources`), пересчёт — только кирпичи у изменившейся секции; пересборка без перемен не считается.
  Подвижный свет — отдельный куб 48/64/96 у камеры (блок 6).
- **Цвета источников** (0.3.4, 30.10): таблица по умолчанию — `assets/flrender/default_colors.json` из галереи источников
  света (`gallery/`: `build.py` из выгрузки `lightdump.json` (мод `Pack Audit/lightdump`), пресеты и правила по названию —
  `gallery/presets.py`, отметки — хаб галерей id `lights`, `gallery_deploy.sh pull`, затем `export.py`). `"color": "none"` —
  без цвета (снимает и цвет из кода). Яркость цвета = сила подкраски (тёмный — слабее), оттенок — rgb / max; усиление
  в шейдере сохраняет яркость (белёсый почти не меняет кадр), сила 0.85. Версия файла 2: прежние строки по умолчанию
  обновляются, правки игрока остаются.
- **Экономия** (0.3.6): источники одного цвета растекаются одним проходом (`LightJobs.statics`, многосемянный `Flood.run`;
  проверено — результат тот же); кирпичи считаются кластерами 3³ секций (`LightJobs.bricks`, мир 5³); на «Низком» и
  «Среднем» почти белые источники (насыщенность < 15 %, `ColorTable.subtle`) не красят (`SectionCache.skipSubtle`).
- Ночное зрение ослабляет добавку цветного света (`color_light.fsh`, `NightVision`).
- Шейдеры только ASCII (строгие драйверы AMD/Intel).

## Кто пользуется
S5 Glow Sticks 2.0.0 (блоки `s5glow:colored_light_<цвет>`, цвета ХИС — `ColorTable`; старая подкраска и `light_<цвет>` удалены).
ZA 0.1.41.0 (всё своё на LatePass/SceneDepth/WorldLight, техники вместо `[graphics]`), RP Core 0.9.0 (глаза-шары —
техника, помехи на PostFx), RP Portals 0.2.0 (свет — WorldLight, живой вид и снимок — техники).
Зависимость у них: `flatDir libs` + `fg.deobf("blank:flrender:<версия>")`, в mods.toml `side="CLIENT"`.

## Сборка
`JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.14.7-hotspot" ./gradlew.bat build` → `build/libs/flrender-<версия>.jar`.
Embeddium для компиляции — `libs/embeddium-0.3.31+mc1.20.1.jar` (в git не идёт). SA при сборке не нужен.
Проверка фонаря в игре — зонд `Claude Code/FL Flash Probe` (файл `config/flashprobe.run` в инстансе включает прогон).
