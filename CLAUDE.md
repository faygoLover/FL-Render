# FL Render

Мод Minecraft Forge 1.20.1 (Java 17), `flrender`, пакет `faygolover.flrender`. Общая клиентская графика модов семейства:
Oculus, поздний проход, глубина сцены, цветной свет от эффектов, постэффекты, единые настройки качества.
**С автором общаемся по-русски.** План и решения — `docs/00_plan.md`.

## Что есть (0.1.0)
- `api.FlRender` — `technique(modid, key, fromQuality)`, `quality()`, `shaderPack()`, `shadowPass()`.
  `Quality` LOW/MEDIUM/HIGH/ULTRA, `Toggle` AUTO/ON/OFF, `Technique.on()`.
- `config.Settings` — `config/flrender.properties` (пресет, `quality_auto`, `fps_checked`, `detected`, `technique.<modid>.<key>`).
- `client.AutoDetect` — первый запуск: GL_RENDERER + видеопамять (NVX/ATI) + ядра + heap → пресет; в первой игре 10 с
  замера FPS после 10 с прогрева, < 25 → на ступень ниже (только если пресет от определялки). Сообщение в чат.
- `client.embeddium` — вкладка «FL Render» в настройках графики Embeddium (`OptionGUIConstructionEvent`): пресет и по
  группе на мод. Подписи техник — в lang самих модов: `flrender.technique.<modid>.<key>` и `.tooltip`.
- `render.LatePass` — с шейдерпаком этапы мира запоминаются и проигрываются на AFTER_LEVEL (моды: `register`,
  `deferred`, `replayed`, `deferEntity`); раз за кадр «кадр готов»: SceneDepth → WorldLight → `onFrameReady` хуки.
- `render.SceneDepth` (`need`), `render.WorldLight` (`provider`, точки и прямоугольники, техника `flrender.world_light`),
  `render.PostFx` (свой PostChain), `render.ScreenPass`, `render.CameraPose`.
- Шейдеры только ASCII (строгие драйверы AMD/Intel).

## Кто пользуется
ZA 0.1.41.0 (всё своё на LatePass/SceneDepth/WorldLight, техники вместо `[graphics]`), RP Core 0.9.0 (глаза-шары —
техника, помехи на PostFx), RP Portals 0.2.0 (свет — WorldLight, живой вид и снимок — техники).
Зависимость у них: `flatDir libs` + `fg.deobf("blank:flrender:<версия>")`, в mods.toml `side="CLIENT"`.

## Сборка
`JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.14.7-hotspot" ./gradlew.bat build` → `build/libs/flrender-<версия>.jar`.
Embeddium для компиляции — `libs/embeddium-0.3.31+mc1.20.1.jar` (в git не идёт).
