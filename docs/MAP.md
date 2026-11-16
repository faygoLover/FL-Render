# FL Render — карта

Версия на момент написания: 0.4.0 (16.11.2026). Факты по классам и миксинам — `docs/INDEX.md`.

## 1. Что делает и чего не делает

Общая клиентская графика модов семейства — библиотека, как RP Core, только для картинки:
- **поздний проход** — рисовать свои эффекты правильно и с шейдерпаком (Oculus), и без него;
- **глубина сцены** для своих шейдеров; полноэкранные проходы и свой пост-эффект поверх кадра;
- **цветной свет** — подкраска мира от источников (блоки, предметы, эффекты модов); уровни света мира не меняются;
- **настоящий свет фонаря** без шейдерпака — конус освещает блоки, сущности и блок-сущности;
- **качество и техники** — общий пресет (картошка / обычный / мощный ПК) и переключатель на каждую технику
  каждого мода, вкладка в настройках графики Embeddium.

Только клиент. За этим — не сюда:
- сами эффекты модов (дым аномалий, поверхность портала, лучи фонарей) — в своих модах; здесь то, чем они рисуют;
- сервер ничего не знает о свете: «несёт ли игрок фонарь» — `RpCoreAPI.carriesLight`, а не этот мод;
- игровая логика любого рода.

## 2. Как течёт работа

**Мод подключается.** в клиентской настройке мод регистрирует свои техники: `api/FlRender.technique(…)` →
`api/Technique` (id, качество по умолчанию) → `config/Settings` хранит выбор игрока (`config/flrender.properties`)
→ вкладка `client/embeddium/FlRenderPage`. Перед рисованием мод спрашивает у техники, включена ли она.

**Кадр.** мод рисует эффект в `RenderLevelStageEvent`; если включён шейдерпак (`client/Oculus`) — через
`render/LatePass` (иначе шейдерпак затрёт эффект) → нужна глубина — `render/SceneDepth` → полноэкранный шейдер —
`render/ScreenPass` → свой пост-эффект — `render/PostFx`. Поворот камеры — `render/CameraPose`.

**Цветной свет.** источники: блоки и предметы с цветом (`light/ColorTable`, значения по умолчанию —
`light/Defaults`, дописывается файлом), подвижные источники кадра (`light/LightSources`: эффекты модов через
`render/WorldLight` и предметы `light/ItemLights`) → объём цвета вокруг камеры `light/ColorVolume` считается в
фоновом потоке (`light/LightJobs`) по знанию о мире `light/SectionCache` (обновляется из `mixin/ClientLevelMixin`)
→ `render/WorldLight` накладывает на кадр.

**Свет фонаря.** мод отдаёт лучи кадра (`api/LightBeams` → `flashlight/Beam`; фонарь Survivor's Arsenal —
`flashlight/SaBeams`) → `flashlight/Flashlight`: что задерживает луч — `flashlight/Occupancy`; блоки освещает
дописанный шейдер чанков Embeddium (`flashlight/ChunkPatch` + `mixin/embeddium/*`), сущности и блок-сущности —
миксины `EntityRendererMixin`, `BlockEntityRenderDispatcherMixin`. Включено ли — `FlRender.flashlightLight()`.

**Первый запуск.** `client/AutoDetect` оценивает компьютер и ставит пресет качества (`api/Quality`).

## 3. Карта пакетов

| Пакет | За что отвечает | С чего начать читать |
|---|---|---|
| `flrender` | вход мода | `FlRenderMod` |
| `api` | **всё, чем пользуются другие моды** | `FlRender` (точка входа), `Technique`, `Quality`, `Toggle`, `LightBeams` |
| `render` | проходы рендера | `LatePass`, `SceneDepth`, `ScreenPass`, `PostFx`, `WorldLight`, `FlShaders`, `CameraPose` |
| `light` | цветной свет | `ColorVolume` (797 строк — ядро), `LightJobs`, `SectionCache`, `ColorTable`, `LightSources` |
| `flashlight` | настоящий свет фонаря | `Flashlight`, `Beam`, `Occupancy`, `ChunkPatch`, `SaBeams` |
| `config` | настройки игрока | `Settings`, `ColorRange` |
| `client` | вход клиента, определение железа, Oculus | `ClientSetup`, `AutoDetect`, `Oculus` |
| `client.embeddium` | вкладка в настройках графики | `EmbeddiumCompat`, `FlRenderPage` |
| `mixin`, `mixin.embeddium` | изменения мира для света; шейдер чанков Embeddium | `ClientLevelMixin`; `EmbeddiumMixinPlugin` (миксины в Embeddium — только если он стоит) |

## 4. Хочу добавить… — рецепты

| Что | Где | Образец | Не забыть |
|---|---|---|---|
| свою технику (эффект с переключателем) в другом моде | в том моде: `FlRender.technique(…)` в клиентской настройке; подпись — lang `flrender.technique.<мод>.<имя>` | RP Core `client/effect/CoreTechniques`, RP Portals `client/PortalTechniques` | этот мод не править |
| эффект, который виден и с шейдерпаком | рисовать через `render/LatePass` | аномалии Zone Artifacts | без `LatePass` шейдерпак затрёт эффект |
| цветной свет от своего эффекта | поставщик подвижных источников (`WorldLight.provider`) | S5 Glow Sticks | свет — только картинка, уровни света мира не меняются |
| цвет света блока или предмета | файл цветов (`light/ColorTable`), значения по умолчанию — `light/Defaults` | факел | основное — в `Defaults`, остальное дописывается файлом без пересборки |
| луч своего фонаря | в своём моде: `api/LightBeams` каждый кадр | RP Lights `client/ClientBeams` | луч описывается записью `flashlight/Beam` |
| новый проход рендера для всех | класс в `render/` + метод в `api/FlRender` | `ScreenPass` | API менять только добавлением: им пользуются 6 модов |
| настройку качества | `config/Settings` + `client/embeddium/FlRenderPage` | дальность цветного света `ColorRange` | свой файл `flrender.properties`, не `ForgeConfigSpec` |

## 5. Данные

| Что | Где хранится | Ключ | Что при смерти | Как попадает клиенту |
|---|---|---|---|---|
| выбор качества и техник | клиент, `config/flrender.properties` | id техники | — | — |
| цвета источников света | клиент, файл цветов (`ColorTable`) | id блока или предмета | — | — |

Сервер и мир мод не трогает. Id техник — ключи в файле игрока: не переименовывать.

## 6. Связи

- От модов семейства не зависит. От него зависят: RP Core (на клиенте), Zone Artifacts, RP Lights, RP Portals,
  S5 Glow Sticks; мягко — RP Fixes (`FlRender.flashlightLight`).
- Embeddium — необязателен (`mods.toml`): вкладка настроек и свет фонаря в шейдере чанков; миксины в него
  подключает `EmbeddiumMixinPlugin`, только если он стоит.
- Oculus / Iris — через их API без жёсткой зависимости (`client/Oculus`).
- Survivor's Arsenal — лучи его фонаря (`SaBeams`, отражением; при `isLoaded("sa_combat")`).

## 7. Конфиги

`config/flrender.properties` — свой формат (`config/Settings`): список техник заранее не известен, их
регистрируют другие моды. `ForgeConfigSpec` здесь нет.

## 8. Сеть

Нет: мод клиентский.

## 9. Ловушки

- **API — общий для шести модов:** `api/FlRender`, `Technique`, `LightBeams` менять только добавлением. Старый
  jar соседа должен работать с новым FL Render.
- **Шейдерпак меняет всё:** при Oculus обычный порядок рисования не работает — эффекты идут через `LatePass`,
  свет фонаря «настоящий» отключается (`flashlightLight()` вернёт нет), в проходе теней рисовать нельзя (`Oculus.shadowPass`).
- **Шейдер чанков Embeddium дописывается текстом** (`ChunkPatch`): обновление Embeddium может сломать вставку —
  тогда свет фонаря по блокам пропадёт без ошибки. Проверять после обновления сборки.
- **Цветной свет считается в фоновом потоке** (`LightJobs`); мир читается только из основного (`SectionCache`).
  Не читать мир из задачи расчёта.
- **Три полноэкранных прохода на кадр** в `WorldLight` — известная цена; снижение разрешения меняет вид и
  требует просмотра Алексом.
- Вид эффектов проверяет Алекс глазами: автотестов у графики нет.

## 10. Тесты

Игровых тестов нет. `python ../_family/family.py verify "FL Render"` — компиляция. Проверка — в игре, с
шейдерпаком и без, на трёх пресетах качества.

## 11. Документы в docs/

| Файл | Состояние |
|---|---|
| `MAP.md`, `INDEX.md` | действуют |
| `00_plan.md` | действует: план библиотеки |
| `flashlight_handover.md`, `flashlight_2026-10-26/` | действуют: как устроен настоящий свет фонаря, снимки |
| `CLAUDE_old.md` | прежняя памятка: сборка — верно |
