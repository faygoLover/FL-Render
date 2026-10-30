// Галерея «Источники света»: какие светящиеся блоки у нас в ходу (★) и какой цвет им давать в FL Render.
// Отметки — в сторе хаба 'review' заплатками {patch: {id: {use, color, comment} | null}}; color: '#RRGGBB', 'none' или нет (= предложено).
let blocks = [], byId = {}, review = { items: {} }, tab = 'all', presets = [], presetById = {};
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const store = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* без хранилища */ } };
const load = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };

async function start() {
  blocks = await (await fetch('data/blocks.json', { cache: 'no-store' })).json();
  for (const b of blocks) byId[b.id] = b;
  presets = await (await fetch('data/presets.json', { cache: 'no-store' })).json();
  for (const p of presets) presetById[p.id] = p;
  try {
    const r = await fetch('api/review', { cache: 'no-store' });
    if (r.ok) review = await r.json();
  } catch (e) { $('#saved').textContent = 'нет связи с сервером — отметки не сохранятся'; }
  review.items ??= {};
  const mods = [...new Set(blocks.map(b => b.mod))].sort((a, b) => a.localeCompare(b));
  $('#mod').innerHTML = '<option value="">все моды</option>' + mods.map(m => `<option>${esc(m)}</option>`).join('');
  $('#q').value = load('light_q') || '';
  $('#mod').value = load('light_mod') || '';
  $('#flag').value = load('light_flag') || '';
  for (const el of ['#q', '#mod', '#flag']) $(el).addEventListener('input', () => {
    store('light_q', $('#q').value); store('light_mod', $('#mod').value); store('light_flag', $('#flag').value); render();
  });
  document.querySelectorAll('#tabs button').forEach(b => b.onclick = () => setTab(b.dataset.tab));
  $('#copy').onclick = () => { $('#json').select(); navigator.clipboard?.writeText($('#json').value); };
  await stone();
  setTab(load('light_tab') || 'all');
}

// ------------------------------------------------------------------ сохранение
let saveTimer = null;
const dirty = new Set();
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    const patch = {};
    for (const id of dirty) patch[id] = review.items[id] || null;
    dirty.clear();
    $('#saved').textContent = 'сохраняю…';
    try {
      const r = await fetch('api/review', { method: 'POST', body: JSON.stringify({ patch }) });
      const j = await r.json();
      $('#saved').textContent = r.ok ? 'сохранено ' + j.saved_at : 'ошибка сохранения';
      if (r.ok) merge(j.items || {});
    } catch (e) {
      for (const id of Object.keys(patch)) dirty.add(id);
      $('#saved').textContent = 'нет связи — повторю';
      setTimeout(save, 3000);
    }
  }, 400);
}

function merge(server) {
  let changed = false;
  for (const id of new Set([...Object.keys(server), ...Object.keys(review.items)])) {
    if (dirty.has(id)) continue;
    if (JSON.stringify(review.items[id] || null) === JSON.stringify(server[id] || null)) continue;
    if (server[id]) review.items[id] = server[id]; else delete review.items[id];
    changed = true;
  }
  if (changed) { tab === 'out' ? renderOut() : refreshAll(); counts(); }
}
setInterval(async () => {
  if (dirty.size) return;
  try {
    const r = await fetch('api/review', { cache: 'no-store' });
    if (r.ok) merge((await r.json()).items || {});
  } catch (e) { /* позже */ }
}, 15000);

function edit(id, fn) {
  const r = review.items[id] ??= {};
  fn(r);
  for (const k of Object.keys(r)) if (r[k] === undefined || r[k] === '' || r[k] === false) delete r[k];
  if (!Object.keys(r).length) delete review.items[id];
  dirty.add(id);
  save();
  refresh(id);
  counts();
}

/** Итоговый цвет блока: '#RRGGBB' или null (без цвета). */
function colorOf(b) {
  const r = review.items[b.id] || {};
  if (r.color === 'none') return null;
  if (r.preset && presetById[r.preset]) return presetById[r.preset].color;
  if (r.color) return r.color;
  return b.prop || null;
}

/** Что выбрано в списке: '' — как предложено, id пресета, 'none', 'custom'. */
function choiceOf(r) {
  if (!r) return '';
  if (r.color === 'none') return 'none';
  if (r.preset) return r.preset;
  if (r.color) return 'custom';
  return '';
}

function propLabel(b) {
  if (!b.prop) return 'предложено: без цвета';
  return 'предложено: ' + (b.preset && presetById[b.preset] ? presetById[b.preset].name : b.prop);
}

// ------------------------------------------------------------------ вкладки
function setTab(t) {
  tab = t;
  store('light_tab', t);
  document.querySelectorAll('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === t));
  $('#out').hidden = t !== 'out';
  $('#grid').hidden = t === 'out';
  $('#filters').style.visibility = t === 'out' ? 'hidden' : '';
  t === 'out' ? renderOut() : render();
  counts();
}

function counts() {
  $('#n-all').textContent = blocks.length;
  $('#n-use').textContent = blocks.filter(b => review.items[b.id]?.use).length;
}

function visible() {
  const q = $('#q').value.trim().toLowerCase(), mod = $('#mod').value, flag = $('#flag').value;
  return blocks.filter(b => {
    const r = review.items[b.id];
    if (tab === 'use' && !r?.use) return false;
    if (mod && b.mod !== mod) return false;
    if (q && !(b.name.toLowerCase().includes(q) || b.id.includes(q) || (b.en || '').toLowerCase().includes(q))) return false;
    switch (flag) {
      case 'strong': return b.max > 3;
      case 'weak': return b.max <= 3;
      case 'cur': return !!b.cur;
      case 'prop': return !!b.prop;
      case 'none': return !b.prop;
      case 'inv': return b.invisible || !b.img;
      case 'switch': return b.switch;
      case 'mine': return !!(r?.color || r?.preset);
      case 'unmarked': return !r;
    }
    return true;
  });
}

let observer = null;
function render() {
  const list = visible();
  $('#shown').textContent = `показано ${list.length}`;
  const groups = {};
  for (const b of list) (groups[b.mod] ??= []).push(b);
  const html = [];
  // Моды с сильными источниками — вперёд, внутри мода сильные — первыми.
  const strong = m => groups[m].filter(b => b.max > 3).length;
  for (const mod of Object.keys(groups).sort((a, b) => strong(b) - strong(a) || a.localeCompare(b))) {
    groups[mod].sort((a, b) => (a.max <= 3) - (b.max <= 3) || b.max - a.max || a.name.localeCompare(b.name));
    html.push(`<div class="modhead">${esc(mod)} <span>${groups[mod].length}</span></div>`);
    for (const b of groups[mod]) html.push(`<div class="card" id="c-${cssId(b.id)}" data-id="${esc(b.id)}">${cardInner(b)}</div>`);
  }
  $('#grid').innerHTML = html.join('');
  observer?.disconnect();
  observer = new IntersectionObserver(es => {
    for (const e of es) if (e.isIntersecting) { draw(e.target); observer.unobserve(e.target); }
  }, { rootMargin: '300px' });
  document.querySelectorAll('#grid canvas').forEach(c => observer.observe(c));
  for (const b of list) mark(b);
}

const cssId = id => id.replace(/[^a-z0-9_-]/gi, '_');

function cardInner(b) {
  const r = review.items[b.id] || {};
  const pic = b.img ? `<img src="${b.img}" loading="lazy" alt="">` : `<div class="nopic">${b.invisible ? 'невидимый<br>блок' : 'нет<br>картинки'}</div>`;
  const ch = choiceOf(r);
  const custom = r.color && r.color !== 'none' ? r.color : (colorOf(b) || '#ffffff');
  const opts = [`<option value="">${esc(propLabel(b))}</option>`]
    .concat(presets.map(p => `<option value="${p.id}"${ch === p.id ? ' selected' : ''}>${esc(p.name)}</option>`))
    .concat([`<option value="none"${ch === 'none' ? ' selected' : ''}>без цвета (белый свет)</option>`,
      `<option value="custom"${ch === 'custom' ? ' selected' : ''}>свой цвет…</option>`]);
  const now = colorOf(b);
  const meta = [`свет ${b.max}`];
  if (b.switch) meta.push('вкл/выкл');
  if (b.cur) meta.push(`сейчас <span class="chip" style="background:${b.cur}"></span>`);
  if (b.glow) meta.push(`загорается <span class="chip" style="background:${b.glow}"></span>`);
  if (b.src) meta.push(esc(b.src));
  return `<div class="row"><div class="pic">${pic}</div><canvas width="128" height="64"></canvas></div>
    <div class="nm">${esc(b.name)}</div><div class="id">${esc(b.id)}</div>
    <div class="meta">${meta.join(' · ')}</div>
    ${r.comment ? `<div class="cm">${esc(r.comment)}</div>` : ''}
    <div class="btns">
      <button class="star${r.use ? ' on' : ''}" data-a="use" title="используем">★</button>
      <span class="chip big" style="background:${now || 'transparent'}" title="${now || 'без цвета'}"></span>
      <select class="pre${ch ? ' on' : ''}">${opts.join('')}</select>
      <label title="свой цвет"${ch === 'custom' ? '' : ' hidden'}><input type="color" value="${custom}"></label>
      <button class="c" data-a="cm" title="комментарий">💬</button>
    </div>`;
}

function mark(b) {
  const el = document.getElementById('c-' + cssId(b.id));
  if (el) el.classList.toggle('use', !!review.items[b.id]?.use);
}

function refresh(id) {
  const el = document.getElementById('c-' + cssId(id));
  if (!el) return;
  el.innerHTML = cardInner(byId[id]);
  mark(byId[id]);
  draw(el.querySelector('canvas'));
}

function refreshAll() {
  for (const el of document.querySelectorAll('#grid .card')) refresh(el.dataset.id);
}

$('#grid').addEventListener('click', e => {
  const btn = e.target.closest('button[data-a]');
  if (!btn) return;
  const id = btn.closest('.card').dataset.id;
  const a = btn.dataset.a;
  if (a === 'use') edit(id, r => { r.use = !r.use; });
  if (a === 'cm') {
    const t = prompt('Комментарий', review.items[id]?.comment || '');
    if (t !== null) edit(id, r => { r.comment = t.trim(); });
  }
});
$('#grid').addEventListener('change', e => {
  const card = e.target.closest('.card');
  if (!card) return;
  const id = card.dataset.id;
  if (e.target.type === 'color') edit(id, r => { r.preset = undefined; r.color = e.target.value.toUpperCase(); });
  if (e.target.classList.contains('pre')) {
    const v = e.target.value;
    edit(id, r => {
      if (v === '') { r.preset = undefined; r.color = undefined; }
      else if (v === 'none') { r.preset = undefined; r.color = 'none'; }
      else if (v === 'custom') { r.preset = undefined; r.color = (colorOf(byId[id]) || '#FFFFFF').toUpperCase(); }
      else { r.preset = v; r.color = undefined; }
    });
  }
});
// Живое превью, пока крутят палитру (без сохранения).
$('#grid').addEventListener('input', e => {
  if (e.target.type !== 'color') return;
  const card = e.target.closest('.card');
  drawWith(card.querySelector('canvas'), byId[card.dataset.id], e.target.value);
});

// ------------------------------------------------------------------ превью света
// Как в шейдере FL Render: кадр = текстура × ванильная яркость; подкраска = mix(кадр, кадр × цвет × 1.8, 0.95).
let stoneTex = null;
async function stone() {
  const im = new Image();
  im.src = 'img/_stone.png';
  await im.decode().catch(() => null);
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const g = c.getContext('2d');
  g.drawImage(im, 0, 0);
  stoneTex = g.getImageData(0, 0, 16, 16).data;
}

function draw(canvas) {
  if (!canvas) return;
  const b = byId[canvas.closest('.card').dataset.id];
  drawWith(canvas, b, colorOf(b));
}

function hexRgb(h) {
  const n = parseInt(h.slice(1), 16);
  return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
}

function drawWith(canvas, b, color) {
  if (!stoneTex) return;
  const W = 128, H = 64, g = canvas.getContext('2d'), img = g.createImageData(W, H), px = img.data;
  const hue = color ? hexRgb(color) : null;
  const top = hue ? Math.max(...hue) : 1;
  const h = hue ? hue.map(v => v / top) : null;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const half = x < 64 ? 0 : 1, lx = x - half * 64;
    // Источник — у пола посреди половинки; блок = 8 пикселей; пол темнее стены.
    const floor = y >= 44;
    const dx = (lx - 32) / 8, dy = (y - 44) / 8;
    const lv = Math.max(0, b.max - Math.hypot(dx, dy));
    // Ванильная кривая яркости + настройка «Яркость» посередине (как у большинства игроков).
    const f = lv / 15, v = f / (4 - 3 * f), bright = 0.03 + 0.97 * (v + (1 - Math.pow(1 - v, 4) - v) * 0.6);
    const t = ((y & 15) * 16 + (lx & 15)) * 4;
    const shade = floor ? 0.72 : 1;
    let r = stoneTex[t] / 255 * bright * shade, gg = stoneTex[t + 1] / 255 * bright * shade, bb = stoneTex[t + 2] / 255 * bright * shade;
    if (half === 1 && h && lv > 0) {
      // Как в шейдере: усиление с сохранением яркости (до 1.8), сила 0.85.
      const amt = 0.85 * top, boost = Math.min(1.8, Math.max(1, 1 / Math.max(0.05, h[0] * 0.299 + h[1] * 0.587 + h[2] * 0.114)));
      r = r + (Math.min(1, r * h[0] * boost) - r) * amt;
      gg = gg + (Math.min(1, gg * h[1] * boost) - gg) * amt;
      bb = bb + (Math.min(1, bb * h[2] * boost) - bb) * amt;
    }
    const o = (y * W + x) * 4;
    px[o] = r * 255; px[o + 1] = gg * 255; px[o + 2] = bb * 255; px[o + 3] = 255;
  }
  g.putImageData(img, 0, 0);
  // Сам источник и подписи.
  for (const half of [0, 1]) {
    const c = half === 1 && color ? color : '#ffffff';
    g.fillStyle = c;
    g.beginPath(); g.arc(half * 64 + 32, 43, 2.5, 0, 7); g.fill();
  }
  g.fillStyle = '#0008'; g.fillRect(0, 0, 128, 10);
  g.fillStyle = '#ddd'; g.font = '8px system-ui';
  g.fillText('ваниль', 3, 8); g.fillText(color ? 'с цветом' : 'без цвета', 67, 8);
  g.fillStyle = '#000'; g.fillRect(63, 0, 2, 64);
}

// ------------------------------------------------------------------ итог
function renderOut() {
  const used = blocks.filter(b => review.items[b.id]?.use);
  const colours = {}, none = [];
  for (const b of blocks) {
    const r = review.items[b.id];
    const c = colorOf(b);
    if (c) colours[b.id] = { color: c };
    else none.push(b.id);
  }
  $('#out-sum').textContent = `используем: ${used.length}; с цветом: ${Object.keys(colours).length}; без цвета: ${none.length}`;
  $('#json').value = JSON.stringify({ use: used.map(b => b.id), blocks: colours, no_color: none.filter(id => byId[id].max > 3) }, null, 2);
}

start();
