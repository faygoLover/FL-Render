# Свет фонаря — состояние на 26.10 (сессия остановлена по лимиту)

Сделано (FL Render 0.3.0, собрано, В ИГРЕ НЕ ПРОВЕРЕНО):
- `flashlight/` — `SaBeams` (лучи SA по логике мода, через MethodHandle, до 4, свои и чужие), `Beam` (форма/затухание),
  `Occupancy` (куб 96³ вокруг камеры, байт на блок = 8 половинок, 3D-текстура с кольцевой адресацией, блок 13),
  `ChunkPatch` (дописывает `sodium:blocks/block_layer_opaque.vsh/.fsh`: текстура × цвет вершины × свет фонаря,
  проход по половинкам к лампе — сквозь стены нет), `Flashlight` (техника `flrender.flashlight` со «Среднего», AFTER_SKY).
- Миксины: `embeddium/ShaderLoaderMixin`, `embeddium/ChunkShaderInterfaceMixin` (конфиг `flrender.embeddium.mixins.json`
  с плагином), `EntityRendererMixin` + `BlockEntityRenderDispatcherMixin` (уровень света как от факела, до 14).
- API `FlRender.flashlightLight()` — для RP Fixes (снизить усиление круга SA только когда свет включён).
- Ночное зрение: `color_light` — добавка × (1 − 0.8·NV), подкраска без Boost.

Осталось:
1. Проверить в `S5_zatest` (тёмная комната, манекен, цветные блоки), подобрать `Beam.GAIN/FALLOFF`; скриншоты до/после
   с паком и без в `docs/`.
2. RP Fixes (после «RP Fixes свободен» от net-traffic): миксин на `FlashlightShader.ensureChain` — своя цепочка
   `rpfixes:sa_flash` (копия flash.fsh + uniform `FlCone`), при `FlRender.flashlightLight()` и без пака — gain/lift/knee
   сильно ниже; с паком всё как было.
3. CLAUDE.md / 00_plan.md — дописать раздел о фонаре.
