import { hydrateIcons, icon } from './icons.js';
import { NativePlayer, PhotoCarousel, YouTubePlayer, segment } from './players.js';

const api = window.loopa;
const $ = (sel, root = document) => root.querySelector(sel);

const state = {
  lib: { tracks: [], playlists: [] },
  list: { type: 'all' },
  items: [],
  index: 0,
  view: 'list',
  volume: Number(localStorage.getItem('volume') ?? 1),
  muted: localStorage.getItem('muted') === '1',
  rate: 1,
};

let player = null;
let carousel = null;
let card = null;
let cardTrackId = null;
let loadToken = 0;

// ---------- pomocnicze ----------

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function formatTime(ms, precise = false) {
  const total = Math.max(0, ms / 1000);
  const m = Math.floor(total / 60);
  const s = total - m * 60;
  const sec = precise ? s.toFixed(1).padStart(4, '0') : String(Math.floor(s)).padStart(2, '0');
  return m >= 60 ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/** "83", "1:23", "1:23.5" -> milisekundy. */
function parseTime(text) {
  const parts = String(text).trim().replace(',', '.').split(':').map(Number);
  if (parts.some((p) => Number.isNaN(p))) return null;
  return Math.round(parts.reduce((acc, p) => acc * 60 + p, 0) * 1000);
}

let toastTimer;
function toast(text) {
  const el = $('#toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
}

const sourceLabel = (t) => (t.source === 'youtube' ? 'YouTube' : 'TikTok');
const currentTrack = () => state.items[state.index] ?? null;
const currentPlaylist = () => (state.list.type === 'playlist' ? state.lib.playlists.find((p) => p.id === state.list.id) : null);

function feedSettings() {
  const p = currentPlaylist();
  if (p) return { autoAdvance: !!p.autoAdvance, playsPerItem: p.playsPerItem || 1 };
  return {
    autoAdvance: localStorage.getItem('all.autoAdvance') === '1',
    playsPerItem: Number(localStorage.getItem('all.plays') || 1),
  };
}

async function saveFeedSettings(patch) {
  const p = currentPlaylist();
  if (p) {
    await api.updatePlaylist(p.id, patch);
  } else {
    if ('autoAdvance' in patch) localStorage.setItem('all.autoAdvance', patch.autoAdvance ? '1' : '0');
    if ('playsPerItem' in patch) localStorage.setItem('all.plays', String(patch.playsPerItem));
    renderHead();
  }
}

// ---------- dane ----------

function itemsFor(list) {
  const byId = new Map(state.lib.tracks.map((t) => [t.id, t]));
  if (list.type === 'playlist') {
    const p = state.lib.playlists.find((x) => x.id === list.id);
    return p ? p.trackIds.map((id) => byId.get(id)).filter(Boolean) : [];
  }
  return state.lib.tracks;
}

/**
 * Odswiezenie danych nie przerywa filmu, ktory leci: karta jest budowana od
 * nowa tylko wtedy, gdy zmienil sie sam film na ekranie.
 */
async function refresh() {
  state.lib = await api.library();
  if (state.list.type === 'playlist' && !currentPlaylist()) state.list = { type: 'all' };
  const keepId = cardTrackId;
  state.items = itemsFor(state.list);
  const found = state.items.findIndex((t) => t.id === keepId);
  if (found >= 0) state.index = found;
  state.index = Math.min(state.index, Math.max(0, state.items.length - 1));
  renderSidebar();
  renderHead();
  if (state.view === 'list') {
    renderList();
    return;
  }
  const t = currentTrack();
  if (!t) {
    showEmpty();
  } else if (t.id !== cardTrackId) {
    showTrack(0);
  } else {
    updateCaption(t);
    updateNav();
  }
}

/** Jak na telefonie: wejscie w playliste pokazuje najpierw liste filmow. */
function selectList(list) {
  state.list = list;
  state.items = itemsFor(list);
  state.index = 0;
  renderSidebar();
  renderHead();
  setView('list');
}

// ---------- menu ----------

function renderSidebar() {
  $('#count-all').textContent = state.lib.tracks.length || '';
  document.querySelector('[data-list="all"]').classList.toggle('on', state.list.type === 'all');
  const byId = new Map(state.lib.tracks.map((t) => [t.id, t]));
  $('#playlists').innerHTML = state.lib.playlists
    .map((p) => {
      const cover = byId.get(p.trackIds[0])?.thumbnailUrl;
      const on = state.list.type === 'playlist' && state.list.id === p.id;
      return `<div class="nav-item${on ? ' on' : ''}" role="button" tabindex="0" data-playlist="${p.id}">
        <span class="playlist-dot" style="${cover ? `background-image:url('${esc(cover)}')` : ''}"></span>
        <span class="nav-label">${esc(p.name)}</span>
        <span class="nav-count">${p.trackIds.length || ''}</span>
        <span class="row-tools">
          <button data-rename="${p.id}" title="Zmień nazwę">${icon('edit')}</button>
          <button data-delete="${p.id}" title="Usuń playlistę">${icon('trash')}</button>
        </span>
      </div>`;
    })
    .join('');
}

function renderHead() {
  const p = currentPlaylist();
  $('#list-title').innerHTML = `${esc(p ? p.name : 'Wszystkie filmy')}<small>${state.items.length} ${state.items.length === 1 ? 'film' : 'filmów'}</small>`;
  const s = feedSettings();
  $('#auto-advance').checked = s.autoAdvance;
  $('#plays-stepper').hidden = !s.autoAdvance;
  $('#plays-count').textContent = s.playsPerItem;
}

function showEmpty() {
  destroyPlayer();
  cardTrackId = null;
  $('#card-wrap').innerHTML = '';
  $('#empty').hidden = false;
  $('#feed').style.visibility = 'hidden';
}

// ---------- karta z filmem ----------

function destroyPlayer() {
  player?.destroy();
  carousel?.destroy();
  player = null;
  carousel = null;
}

function loopNote(loop) {
  const parts = [];
  if (loop.startMs > 0) parts.push(`start ${formatTime(loop.startMs)}`);
  if (loop.loopStartMs > 0) parts.push(`pętla od ${formatTime(loop.loopStartMs)}`);
  if (loop.endMs > 0) parts.push(`do ${formatTime(loop.endMs)}`);
  return parts.join(' · ');
}

function updateCaption(t) {
  if (!card) return;
  $('.author', card).textContent = t.author ? `@${t.author.replace(/^@/, '')}` : sourceLabel(t);
  $('.title', card).textContent = t.title;
  const note = loopNote(t.loop);
  $('.meta', card).innerHTML = `<span>${state.index + 1} / ${state.items.length}</span>${note ? `<span class="accent">${esc(note)}</span>` : ''}`;
  renderMarks(t);
}

function renderMarks(t) {
  const bar = card && $('.bar', card);
  if (!bar) return;
  bar.querySelectorAll('.mark').forEach((m) => m.remove());
  const dur = player?.duration() || t.durationMs;
  if (!dur) return;
  const marks = [
    [t.loop.loopStartMs, ''],
    [t.loop.endMs, 'end'],
  ];
  for (const [ms, cls] of marks) {
    if (ms > 0 && ms < dur) bar.insertAdjacentHTML('beforeend', `<span class="mark ${cls}" style="left:${(ms / dur) * 100}%"></span>`);
  }
}

function showTrack(direction) {
  const t = currentTrack();
  if (!t) return showEmpty();
  destroyPlayer();
  $('#empty').hidden = true;
  $('#feed').style.visibility = '';
  const token = ++loadToken;
  cardTrackId = t.id;

  const thumb = t.thumbnailUrl ? `url('${esc(t.thumbnailUrl)}')` : 'none';
  $('#card-wrap').style.setProperty('--ar', t.source === 'youtube' ? t.aspect || 16 / 9 : 9 / 16);
  $('#card-wrap').innerHTML = `
    <div class="card ${direction > 0 ? 'enter-down' : direction < 0 ? 'enter-up' : ''}">
      <div class="backdrop" style="background-image:${thumb}"></div>
      <div class="poster" style="background-image:${thumb}"></div>
      <div class="hit"></div>
      <div class="overlay-top">
        <div class="volume"><button class="vol-btn" title="Wycisz (M)"></button><input type="range" min="0" max="1" step="0.01"></div>
        <span class="badge">${sourceLabel(t)}</span>
      </div>
      <div class="paused-icon" hidden><span>${icon('play')}</span></div>
      <div class="spinner"></div>
      <div class="caption"><div class="author"></div><div class="title"></div><div class="meta"></div></div>
      <div class="progress"><div class="bar"><div class="fill"></div></div><span class="time"></span></div>
    </div>`;
  card = $('.card', $('#card-wrap'));
  updateCaption(t);
  setupVolume();
  setupProgress();
  $('.hit', card).addEventListener('click', () => player?.toggle());
  updateNav();

  const events = {
    onReady: (durationMs) => {
      if (token !== loadToken) return;
      $('.spinner', card)?.remove();
      if (!carousel) $('.poster', card).hidden = true;
      player.setRate(state.rate);
      if (!t.durationMs && durationMs > 0) api.updateTrack(t.id, { durationMs: Math.round(durationMs) });
      renderMarks(t);
      preloadNeighbours();
    },
    onState: () => token === loadToken && updatePaused(),
    onCycle: (n) => {
      const s = feedSettings();
      if (s.autoAdvance && n >= s.playsPerItem && state.index < state.items.length - 1) {
        setTimeout(() => go(1), 0);
        return 'stop';
      }
      return undefined;
    },
    onError: (msg) => token === loadToken && showError(msg),
  };

  api
    .prepare(t.id)
    .then((m) => {
      if (token !== loadToken) return;
      if (m.kind === 'youtube') {
        if (m.aspect) $('#card-wrap').style.setProperty('--ar', m.aspect);
        player = new YouTubePlayer(card, events);
        player.setVolume(state.volume, state.muted);
        player.load(m.videoId, t.loop, true);
      } else {
        player = new NativePlayer(card, events);
        player.setVolume(state.volume, state.muted);
        if (m.images?.length) {
          carousel = new PhotoCarousel(card, m.images, () => player?.playing());
          $('.poster', card).hidden = true;
        }
        player.load(m.url, t.loop, true);
      }
      card.querySelectorAll('.media').forEach((el) => card.prepend(el));
      $('.backdrop', card) && card.prepend($('.backdrop', card));
    })
    .catch((e) => token === loadToken && showError(cleanError(e)));
}

const cleanError = (e) => String(e?.message ?? e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');

function showError(message) {
  if (!card) return;
  $('.spinner', card)?.remove();
  $('.error', card)?.remove();
  const t = currentTrack();
  card.insertAdjacentHTML(
    'beforeend',
    `<div class="error"><p>${esc(message)}</p><div class="row">
      <button class="btn primary" data-err="retry">Spróbuj ponownie</button>
      ${state.index < state.items.length - 1 ? '<button class="btn" data-err="next">Następny</button>' : ''}
      <button class="btn" data-err="source">Otwórz w ${sourceLabel(t)}</button>
    </div></div>`,
  );
  $('.error', card).addEventListener('click', async (e) => {
    const action = e.target.closest('[data-err]')?.dataset.err;
    if (action === 'retry') {
      await api.retry(t.id);
      showTrack(0);
    } else if (action === 'next') {
      go(1);
    } else if (action === 'source') {
      api.openExternal(t.url);
    }
  });
}

function updatePaused() {
  if (!card) return;
  $('.paused-icon', card).hidden = !player || player.playing();
}

function setupVolume() {
  const btn = $('.vol-btn', card);
  const slider = $('.volume input', card);
  const render = () => {
    btn.innerHTML = icon(state.muted || state.volume === 0 ? 'mute' : 'volume');
    slider.value = state.muted ? 0 : state.volume;
  };
  btn.addEventListener('click', toggleMute);
  slider.addEventListener('input', () => {
    state.volume = Number(slider.value);
    state.muted = state.volume === 0;
    applyVolume();
  });
  card.renderVolume = render;
  render();
}

function applyVolume() {
  player?.setVolume(state.volume, state.muted);
  localStorage.setItem('volume', String(state.volume));
  localStorage.setItem('muted', state.muted ? '1' : '0');
  card?.renderVolume?.();
}

function toggleMute() {
  state.muted = !state.muted;
  if (!state.muted && state.volume === 0) state.volume = 0.6;
  applyVolume();
}

function setupProgress() {
  const el = $('.progress', card);
  const seekAt = (clientX) => {
    const r = el.getBoundingClientRect();
    const dur = player?.duration();
    if (dur) player.seek(Math.min(1, Math.max(0, (clientX - r.left) / r.width)) * dur);
  };
  el.addEventListener('pointerdown', (e) => {
    seekAt(e.clientX);
    const move = (ev) => seekAt(ev.clientX);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', () => window.removeEventListener('pointermove', move), { once: true });
  });
}

function tickProgress() {
  if (card && player) {
    const dur = player.duration();
    const pos = player.position();
    const fill = $('.fill', card);
    if (fill && dur) fill.style.width = `${Math.min(100, (pos / dur) * 100)}%`;
    const time = $('.time', card);
    if (time) time.textContent = `${formatTime(pos)} / ${formatTime(dur)}`;
  }
  requestAnimationFrame(tickProgress);
}

/** Klip obok jest pobierany z wyprzedzeniem - przejscie na niego rusza od razu. */
function preloadNeighbours() {
  for (const i of [state.index + 1, state.index - 1]) {
    const t = state.items[i];
    if (t?.source === 'tiktok') api.prepare(t.id).catch(() => {});
  }
}

function updateNav() {
  $('#prev').disabled = state.index <= 0;
  $('#next').disabled = state.index >= state.items.length - 1;
}

function go(delta) {
  const target = state.index + delta;
  if (target < 0 || target >= state.items.length) return;
  state.index = target;
  showTrack(delta);
}

// ---------- lista filmow ----------

function setView(view) {
  state.view = view;
  $('#view-feed').classList.toggle('on', view === 'feed');
  $('#view-list').classList.toggle('on', view === 'list');
  $('#feed').hidden = view !== 'feed';
  $('#list').hidden = view !== 'list';
  if (view === 'list') {
    // Lista to jak ekran playlisty na telefonie - nic w tle nie gra.
    destroyPlayer();
    cardTrackId = null;
    $('#card-wrap').innerHTML = '';
    $('#empty').hidden = true;
    renderList();
  } else if (state.items.length) {
    if (!player) showTrack(0);
  } else {
    showEmpty();
  }
}

function playFrom(index) {
  state.index = index;
  setView('feed');
}

function renderList() {
  const p = currentPlaylist();
  const total = state.items.reduce((sum, t) => sum + (t.durationMs || 0), 0);
  if (!state.items.length) {
    $('#list').innerHTML = '';
    $('#empty').hidden = false;
    return;
  }
  $('#empty').hidden = true;
  $('#list').innerHTML = `
    <div class="list-head">
      <button class="btn primary play-all" data-play="0">${icon('play')}<span>Odtwórz</span></button>
      <span class="list-meta">${state.items.length} ${state.items.length === 1 ? 'film' : 'filmów'}${total ? ` · ${formatTime(total)}` : ''}</span>
    </div>
    <div class="rows">
      ${state.items
        .map((t, i) => {
          const note = loopNote(t.loop);
          const thumb = t.thumbnailUrl ? `background-image:url('${esc(t.thumbnailUrl)}')` : '';
          return `<div class="row" data-play="${i}">
            <span class="row-num">${i + 1}</span>
            <span class="row-thumb" style="${thumb}"><span class="src">${t.source === 'youtube' ? 'YT' : 'TT'}</span></span>
            <span class="row-meta">
              <span class="row-title">${esc(t.title)}</span>
              <span class="row-sub">${esc([t.author, t.durationMs ? formatTime(t.durationMs) : null].filter(Boolean).join(' · '))}</span>
              ${note ? `<span class="row-note">${esc(note)}</span>` : ''}
            </span>
            <span class="row-tools">
              <button data-act="loop" data-i="${i}" title="Ustaw pętlę">${icon('repeat')}</button>
              ${p ? `<button data-act="up" data-i="${i}" title="W górę" ${i === 0 ? 'disabled' : ''}>${icon('up')}</button>
              <button data-act="down" data-i="${i}" title="W dół" ${i === state.items.length - 1 ? 'disabled' : ''}>${icon('down')}</button>` : ''}
              <button data-act="remove" data-i="${i}" title="${p ? 'Usuń z playlisty' : 'Usuń z biblioteki'}">${icon('trash')}</button>
            </span>
          </div>`;
        })
        .join('')}
    </div>`;
}

async function onListClick(e) {
  const act = e.target.closest('[data-act]');
  if (act) {
    e.stopPropagation();
    const t = state.items[Number(act.dataset.i)];
    const p = currentPlaylist();
    if (act.dataset.act === 'loop') openLoopEditor(t);
    else if (act.dataset.act === 'remove') removeTrack(t);
    else if (p) await api.movePlaylistItem(p.id, t.id, act.dataset.act === 'up' ? -1 : 1);
    return;
  }
  const play = e.target.closest('[data-play]');
  if (play) playFrom(Number(play.dataset.play));
}

// ---------- okna dialogowe ----------

function openModal(html, onMount) {
  const root = $('#modal-root');
  root.innerHTML = `<div class="backdrop-dim"></div><div class="modal">${html}</div>`;
  const modal = $('.modal', root);
  const close = () => {
    root.innerHTML = '';
    document.removeEventListener('keydown', onKey, true);
  };
  const onKey = (e) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      close();
    }
  };
  document.addEventListener('keydown', onKey, true);
  $('.backdrop-dim', root).addEventListener('click', close);
  modal.addEventListener('click', (e) => e.target.closest('[data-close]') && close());
  onMount?.(modal, close);
  return close;
}

const isModalOpen = () => !!$('#modal-root').firstChild;

function promptName(title, initial, confirm, onDone) {
  openModal(
    `<h3>${esc(title)}</h3>
     <div class="field"><input class="text-input" id="name" maxlength="80"></div>
     <div class="foot"><button class="btn" data-close>Anuluj</button><button class="btn primary" id="ok">${esc(confirm)}</button></div>`,
    (m, close) => {
      const input = $('#name', m);
      input.value = initial;
      input.focus();
      input.select();
      const submit = () => {
        if (!input.value.trim()) return;
        close();
        onDone(input.value.trim());
      };
      $('#ok', m).addEventListener('click', submit);
      input.addEventListener('keydown', (e) => e.key === 'Enter' && submit());
    },
  );
}

function confirmBox(title, body, confirm, onYes) {
  openModal(
    `<h3>${esc(title)}</h3><div class="sub">${esc(body)}</div>
     <div class="foot"><button class="btn" data-close>Anuluj</button><button class="btn primary" id="yes">${esc(confirm)}</button></div>`,
    (m, close) => $('#yes', m).addEventListener('click', () => { close(); onYes(); }),
  );
}

/** Dla filmu, ktory gra, albo dowolnego z listy (wtedy bez "Teraz" i "Posluchaj"). */
function openLoopEditor(track) {
  const t = track?.id ? track : currentTrack();
  if (!t) return;
  const live = !!player && cardTrackId === t.id;
  const durMs = (live && player.duration()) || t.durationMs || 60000;
  const loop = { ...t.loop };
  const fields = [
    ['startMs', 'Start pierwszego odtworzenia', ''],
    ['loopStartMs', 'Start każdego powtórzenia', 'tu wraca film zamiast na początek'],
    ['endMs', 'Koniec segmentu', '0:00 = do końca filmu'],
  ];
  openModal(
    `<h3>Pętla</h3>
     <div class="sub">Długość filmu: ${formatTime(durMs, true)}. Zmiany działają od razu po zapisaniu.</div>
     ${fields
       .map(
         ([key, label, hint]) => `<div class="field" data-key="${key}">
           <div class="field-head"><span>${label}</span><span class="hint">${hint}</span></div>
           <div class="field-row">
             <input type="range" min="0" max="${durMs}" step="100">
             <input class="time-input" spellcheck="false">
             ${live ? '<button class="mini" data-now>Teraz</button><button class="mini" data-listen>Posłuchaj</button>' : ''}
           </div></div>`,
       )
       .join('')}
     <div class="foot"><button class="btn" id="reset">Wyzeruj</button><span class="spacer"></span><button class="btn" data-close>Anuluj</button><button class="btn primary" id="save">Zapisz</button></div>`,
    (m, close) => {
      const sync = () => {
        m.querySelectorAll('.field').forEach((f) => {
          const v = loop[f.dataset.key];
          $('input[type=range]', f).value = v;
          if (document.activeElement !== $('.time-input', f)) $('.time-input', f).value = formatTime(v, true);
        });
      };
      m.querySelectorAll('.field').forEach((f) => {
        const key = f.dataset.key;
        $('input[type=range]', f).addEventListener('input', (e) => { loop[key] = Number(e.target.value); sync(); });
        $('.time-input', f).addEventListener('change', (e) => {
          const ms = parseTime(e.target.value);
          if (ms != null) loop[key] = Math.min(Math.max(0, ms), durMs);
          sync();
        });
        $('[data-now]', f)?.addEventListener('click', () => { loop[key] = Math.round(player?.position() ?? 0); sync(); });
        $('[data-listen]', f)?.addEventListener('click', () => { player?.seek(loop[key]); player?.play(); });
      });
      $('#reset', m).addEventListener('click', () => { Object.assign(loop, { startMs: 0, loopStartMs: 0, endMs: 0 }); sync(); });
      $('#save', m).addEventListener('click', async () => {
        // Petla bez wlasnego poczatku startuje tam, gdzie pierwsze odtworzenie.
        if (loop.loopStartMs === 0 && loop.startMs > 0) loop.loopStartMs = loop.startMs;
        if (loop.endMs >= durMs - 100) loop.endMs = 0;
        const seg = segment(loop, durMs / 1000);
        const clean = { ...t.loop, startMs: Math.round(seg.start * 1000), loopStartMs: Math.round(seg.loopStart * 1000), endMs: loop.endMs > 0 ? Math.round(seg.end * 1000) : 0 };
        close();
        t.loop = clean;
        if (live) {
          player.setLoop(clean, true);
          updateCaption(t);
        }
        await api.updateTrack(t.id, { loop: clean });
        toast('Zapisano pętlę');
      });
      sync();
    },
  );
}

function openSpeed() {
  const speeds = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2];
  openModal(
    `<h3>Szybkość</h3><div class="sub">Działa do zmiany filmu.</div>
     <div class="chips">${speeds.map((s) => `<button class="chip${s === state.rate ? ' on' : ''}" data-rate="${s}">${s}×</button>`).join('')}</div>
     <div class="foot"><button class="btn" data-close>Gotowe</button></div>`,
    (m) => {
      m.addEventListener('click', (e) => {
        const rate = e.target.closest('[data-rate]')?.dataset.rate;
        if (!rate) return;
        setRate(Number(rate));
        m.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', Number(c.dataset.rate) === state.rate));
      });
    },
  );
}

function setRate(rate) {
  state.rate = rate;
  player?.setRate(rate);
  $('.speed-label').textContent = `${rate}×`;
  $('#act-speed').classList.toggle('active', rate !== 1);
}

function openPlaylistPicker() {
  const t = currentTrack();
  if (!t) return;
  const render = (m) => {
    $('#pl-list', m).innerHTML = state.lib.playlists.length
      ? state.lib.playlists
          .map((p) => `<label class="check"><input type="checkbox" data-id="${p.id}" ${p.trackIds.includes(t.id) ? 'checked' : ''}><span class="grow">${esc(p.name)}<small>${p.trackIds.length} filmów</small></span></label>`)
          .join('')
      : '<div class="sub">Nie masz jeszcze playlist.</div>';
  };
  openModal(
    `<h3>Do playlisty</h3><div class="sub">${esc(t.title)}</div>
     <div id="pl-list"></div>
     <div class="field-row"><input class="text-input" id="new-name" placeholder="Nowa playlista…"><button class="btn" id="create">Utwórz</button></div>
     <div class="foot"><button class="btn primary" data-close>Gotowe</button></div>`,
    (m) => {
      render(m);
      $('#pl-list', m).addEventListener('change', async (e) => {
        const id = e.target.dataset.id;
        if (e.target.checked) await api.addToPlaylist(id, [t.id]);
        else await api.removeFromPlaylist(id, t.id);
        state.lib = await api.library();
        render(m);
      });
      const create = async () => {
        const name = $('#new-name', m).value.trim();
        if (!name) return;
        const p = await api.createPlaylist(name);
        await api.addToPlaylist(p.id, [t.id]);
        $('#new-name', m).value = '';
        state.lib = await api.library();
        render(m);
      };
      $('#create', m).addEventListener('click', create);
      $('#new-name', m).addEventListener('keydown', (e) => e.key === 'Enter' && create());
    },
  );
}

function removeCurrent() {
  removeTrack(currentTrack());
}

function removeTrack(t) {
  if (!t) return;
  const p = currentPlaylist();
  if (p) {
    api.removeFromPlaylist(p.id, t.id).then(() => toast(`Usunięto z „${p.name}”`));
  } else {
    confirmBox('Usunąć film z biblioteki?', `„${t.title}” zniknie też ze wszystkich playlist.`, 'Usuń', () =>
      api.deleteTrack(t.id).then(() => toast('Usunięto film')),
    );
  }
}

// ---------- dodawanie linkow ----------

async function addTracksHere(trackIds) {
  const p = currentPlaylist();
  if (p) await api.addToPlaylist(p.id, trackIds);
}

async function showAdded(track) {
  await refresh();
  const i = state.items.findIndex((t) => t.id === track.id);
  if (i >= 0) {
    state.index = i;
    if (state.view === 'list') setView('feed');
    else showTrack(1);
  }
}

async function addLink(text) {
  const button = $('#add-button');
  button.disabled = true;
  try {
    const result = await api.addLink(text);
    if (result.type === 'error') return toast(result.message);
    if (result.type === 'track') {
      await addTracksHere([result.track.id]);
      toast(`Dodano: ${result.track.title}`);
      await showAdded(result.track);
    } else if (result.link.kind === 'track') {
      await importSpotifySong(result.link);
    } else {
      importSpotifyCollection(result.link);
    }
    $('#add-input').value = '';
  } catch (e) {
    toast(cleanError(e));
  } finally {
    button.disabled = false;
  }
}

async function importSpotifySong(link) {
  const close = openModal(`<h3>Spotify</h3><div class="sub">Szukam tego utworu na YouTube…</div><div class="progress-line"><div style="width:35%"></div></div>`);
  try {
    const r = await api.spotifySong(link);
    close();
    if (!r.found) {
      openModal(
        `<h3>Nie znalazłem tego utworu</h3><div class="sub">„${esc(r.song.title)}” — ${esc(r.song.artists.join(', '))}</div>
         <p>Spotify nie wypuszcza dźwięku poza swoją apkę, więc Loopa gra muzykę z YouTube'a — a tam nie ma nic wystarczająco podobnego.</p>
         <div class="foot"><button class="btn primary" data-close>OK</button></div>`,
      );
      return;
    }
    await addTracksHere([r.track.id]);
    toast(`Ze Spotify: ${r.song.title}`);
    await showAdded(r.track);
  } catch (e) {
    close();
    toast(cleanError(e));
  }
}

function importSpotifyCollection(link) {
  openModal(
    `<div class="import-head"><div class="cover" id="sp-cover"></div><div>
       <h3 id="sp-name">${link.kind === 'album' ? 'Album' : 'Playlista'} ze Spotify</h3>
       <div class="sub" id="sp-status">Czytam ${link.kind === 'album' ? 'album' : 'playlistę'}…</div></div></div>
     <div class="progress-line"><div id="sp-bar" style="width:0%"></div></div>
     <div class="note">Spotify nie wypuszcza dźwięku poza swoją apkę, więc każdy utwór wyszukuję na YouTube po tytule, wykonawcy i długości.</div>
     <div id="sp-result"></div>`,
    async (m, close) => {
      api.onSpotifyProgress((p) => {
        if (!document.body.contains(m)) return;
        $('#sp-name', m).textContent = p.name;
        if (p.coverUrl) $('#sp-cover', m).style.backgroundImage = `url('${p.coverUrl}')`;
        $('#sp-status', m).textContent = `Szukam na YouTube: ${p.done} z ${p.total} · znaleziono ${p.found}`;
        $('#sp-bar', m).style.width = `${p.total ? (p.done / p.total) * 100 : 0}%`;
      });
      let result;
      try {
        result = await api.spotifyCollection(link);
      } catch (e) {
        $('#sp-status', m).textContent = cleanError(e);
        return;
      }
      if (!document.body.contains(m)) return;
      const found = result.total - result.missing.length;
      $('#sp-status', m).textContent = `${result.kind === 'album' ? 'Album' : 'Playlista'}${result.owner ? ` · ${result.owner}` : ''} · znalazłem ${found} z ${result.total}`;
      $('#sp-bar', m).style.width = '100%';
      if (!result.tracks.length) {
        $('#sp-result', m).innerHTML = '<p>Nie znalazłem na YouTube żadnego z tych utworów.</p><div class="foot"><button class="btn primary" data-close>OK</button></div>';
        return;
      }
      $('#sp-result', m).innerHTML = `
        ${result.missing.length ? `<details><summary class="note">Czego nie znalazłem (${result.missing.length})</summary><div class="missing">${result.missing.map((s) => `${esc(s.artists.join(', '))} – ${esc(s.title)}`).join('<br>')}</div></details>` : ''}
        ${result.mayBeTruncated ? `<div class="note">Spotify pokazuje publicznie tylko pierwsze ${result.previewLimit} utworów playlisty.</div>` : ''}
        <div class="field"><div class="field-head"><span>Gdzie zapisać?</span></div>
          <label class="check"><input type="checkbox" id="sp-new" checked><span class="grow">Nowa playlista<small>w kolejności ze Spotify</small></span></label>
          <input class="text-input" id="sp-new-name">
          ${state.lib.playlists.map((p) => `<label class="check"><input type="checkbox" data-id="${p.id}"><span class="grow">${esc(p.name)}</span></label>`).join('')}
        </div>
        <div class="foot"><button class="btn" data-close>Anuluj</button><button class="btn primary" id="sp-save">Zapisz ${result.tracks.length} ${result.tracks.length === 1 ? 'utwór' : 'utworów'}</button></div>`;
      $('#sp-new-name', m).value = result.name;
      $('#sp-save', m).addEventListener('click', async () => {
        const playlistIds = [...m.querySelectorAll('[data-id]:checked')].map((c) => c.dataset.id);
        const newName = $('#sp-new', m).checked ? $('#sp-new-name', m).value.trim() || result.name : null;
        $('#sp-save', m).disabled = true;
        const target = await api.spotifySave({ tracks: result.tracks, playlistIds, newName });
        close();
        toast(`Zapisano ${result.tracks.length} utworów`);
        await refresh();
        if (target != null) selectList({ type: 'playlist', id: target });
      });
    },
  );
}

// ---------- sterowanie ----------

function wireUi() {
  hydrateIcons();

  $('#add-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const text = $('#add-input').value.trim();
    if (text) addLink(text);
  });

  document.querySelector('[data-list="all"]').addEventListener('click', () => selectList({ type: 'all' }));
  $('#playlists').addEventListener('click', (e) => {
    const rename = e.target.closest('[data-rename]')?.dataset.rename;
    const del = e.target.closest('[data-delete]')?.dataset.delete;
    if (rename) {
      e.stopPropagation();
      const p = state.lib.playlists.find((x) => x.id === rename);
      return promptName('Zmień nazwę', p.name, 'Zapisz', (name) => api.updatePlaylist(p.id, { name }));
    }
    if (del) {
      e.stopPropagation();
      const p = state.lib.playlists.find((x) => x.id === del);
      return confirmBox('Usunąć playlistę?', `„${p.name}” zniknie, ale filmy zostaną w bibliotece.`, 'Usuń', () => api.deletePlaylist(p.id));
    }
    const id = e.target.closest('[data-playlist]')?.dataset.playlist;
    if (id) selectList({ type: 'playlist', id });
  });
  $('#new-playlist').addEventListener('click', () =>
    promptName('Nowa playlista', '', 'Utwórz', async (name) => {
      const p = await api.createPlaylist(name);
      await refresh();
      selectList({ type: 'playlist', id: p.id });
    }),
  );

  $('#auto-advance').addEventListener('change', (e) => saveFeedSettings({ autoAdvance: e.target.checked }));
  $('#plays-stepper').addEventListener('click', (e) => {
    const step = Number(e.target.closest('[data-step]')?.dataset.step ?? 0);
    if (!step) return;
    saveFeedSettings({ playsPerItem: Math.min(99, Math.max(1, feedSettings().playsPerItem + step)) });
  });
  $('#view-feed').addEventListener('click', () => setView('feed'));
  $('#view-list').addEventListener('click', () => setView('list'));
  $('#list').addEventListener('click', onListClick);

  $('#prev').addEventListener('click', () => go(-1));
  $('#next').addEventListener('click', () => go(1));
  $('#act-loop').addEventListener('click', () => openLoopEditor());
  $('#act-speed').addEventListener('click', openSpeed);
  $('#act-playlist').addEventListener('click', openPlaylistPicker);
  $('#act-source').addEventListener('click', () => currentTrack() && api.openExternal(currentTrack().url));
  $('#act-remove').addEventListener('click', removeCurrent);

  // Kolko: jeden ruch = jeden film, jak w TikToku w przegladarce.
  let wheelAcc = 0;
  let wheelLock = 0;
  $('#feed').addEventListener(
    'wheel',
    (e) => {
      e.preventDefault();
      if (Date.now() < wheelLock) return;
      wheelAcc += e.deltaY;
      if (Math.abs(wheelAcc) > 60) {
        go(wheelAcc > 0 ? 1 : -1);
        wheelAcc = 0;
        wheelLock = Date.now() + 550;
      }
    },
    { passive: false },
  );

  document.addEventListener('keydown', (e) => {
    if (isModalOpen() || e.target.closest('input, textarea') || state.view !== 'feed') return;
    const keys = {
      ArrowDown: () => go(1),
      ArrowUp: () => go(-1),
      ' ': () => player?.toggle(),
      m: toggleMute,
      M: toggleMute,
      l: openLoopEditor,
      L: openLoopEditor,
      ArrowRight: () => (carousel ? carousel.go(1) : player?.seek(player.position() + 5000)),
      ArrowLeft: () => (carousel ? carousel.go(-1) : player?.seek(Math.max(0, player.position() - 5000))),
    };
    const fn = keys[e.key];
    if (fn) {
      e.preventDefault();
      fn();
    }
  });

  api.onLibraryChanged(() => refresh());
  // Link z okienka "Zapisz w Loopa" (Spotify albo "Otworz") - dalej jak wklejony.
  api.onLinkOpen((text) => addLink(text));
  api.onPause(() => player?.pause());

  $('#sync-button').addEventListener('click', openSync);
  $('#browser-button').addEventListener('click', openBrowserHelp);
  api.onSyncStatus(renderSyncBadge);
  api.syncStatus().then(renderSyncBadge);
}

// ---------- rozszerzenie do przegladarki ----------

/**
 * Menu "Udostepnij na" w TikToku jest czescia ich strony - aplikacja nie moze
 * sie do niego dopisac sama. Robi to male rozszerzenie do Brave, ktore lezy
 * w folderze Loopa; trzeba je raz wczytac w przegladarce.
 */
async function openBrowserHelp() {
  const folder = await api.extensionPath();
  openModal(
    `<h3>„Loopa” w menu Udostępnij na TikToku</h3>
     <div class="sub">Po jednorazowej instalacji w TikToku w przeglądarce zobaczysz w „Udostępnij na” przycisk <b>Loopa</b> — jak na telefonie. Do tego „Wyślij do Loopa” pod prawym przyciskiem myszy na linkach z TikToka, YouTube'a i Spotify.</div>
     <ol class="steps">
       <li>W Brave wpisz w pasek adresu <span class="code">brave://extensions</span> i naciśnij Enter.</li>
       <li>Włącz <b>Tryb dewelopera</b> (przełącznik w prawym górnym rogu).</li>
       <li>Kliknij <b>Załaduj rozpakowane</b> i wskaż folder rozszerzenia:<br><span class="code" id="ext-path"></span></li>
       <li>Odśwież kartę z TikTokiem.</li>
     </ol>
     <div class="note">Rozszerzenie wysyła link tylko do Loopa na tym komputerze, a zapis i tak zatwierdzasz w okienku „Zapisz w Loopa”. Loopa musi być włączona (może być schowana przy zegarze).</div>
     <div class="foot"><button class="btn" id="copy-ext">Kopiuj ścieżkę folderu</button><span class="spacer"></span><button class="btn" id="open-ext">Otwórz folder</button><button class="btn primary" data-close>Gotowe</button></div>`,
    (m) => {
      $('#ext-path', m).textContent = folder;
      $('#open-ext', m).addEventListener('click', () => api.openExtensionFolder());
      $('#copy-ext', m).addEventListener('click', async () => {
        await navigator.clipboard.writeText(folder).catch(() => {});
        toast('Skopiowano ścieżkę — wklej ją w oknie „Załaduj rozpakowane”');
      });
    },
  );
}

// ---------- synchronizacja ----------

function syncText(s) {
  if (s.state === 'off') return 'Wyłączona';
  if (s.state === 'working') return 'Synchronizuję…';
  if (s.state === 'ok') return `Zsynchronizowano o ${new Date(s.at).toLocaleTimeString('pl-PL')}`;
  return s.message ?? 'Błąd synchronizacji';
}

function renderSyncBadge(s) {
  const el = $('#sync-button');
  el.dataset.state = s.state;
  $('.sync-text', el).textContent = s.state === 'off' ? 'Połącz z telefonem' : syncText(s);
  const open = $('#sync-status');
  if (open) open.textContent = syncText(s);
}

async function openSync() {
  const s = await api.syncStatus();
  if (s.state === 'off') {
    openModal(
      `<h3>Wspólna biblioteka z telefonem</h3>
       <div class="sub">Playlisty i filmy takie same na telefonie i tutaj — zmiana w jednym miejscu po chwili jest w drugim, jak w Spotify. Biblioteka leży w ukrytym pliku na Twoim koncie GitHub.</div>
       <ol class="steps">
         <li><button class="link" id="token-link">Utwórz token na GitHubie</button> — zaznaczone jest tylko „gist”. Skopiuj go.</li>
         <li>Wklej go tutaj i w apce na telefonie: Ustawienia → Synchronizacja z komputerem.</li>
       </ol>
       <input class="text-input" id="token" type="password" placeholder="Token z GitHuba" spellcheck="false">
       <div class="note" id="sync-status"></div>
       <div class="foot"><button class="btn" data-close>Anuluj</button><button class="btn primary" id="connect">Połącz</button></div>`,
      (m, close) => {
        $('#token-link', m).addEventListener('click', () => api.openExternal(s.tokenUrl));
        $('#token', m).focus();
        $('#connect', m).addEventListener('click', async () => {
          const token = $('#token', m).value.trim();
          if (!token) return;
          $('#connect', m).disabled = true;
          $('#sync-status', m).textContent = 'Łączę…';
          const result = await api.syncConnect(token);
          $('#connect', m).disabled = false;
          if (result.state === 'ok') {
            close();
            toast('Połączono — biblioteka jest wspólna z telefonem');
          } else {
            $('#sync-status', m).textContent = syncText(result);
          }
        });
      },
    );
    return;
  }
  openModal(
    `<h3>Synchronizacja z telefonem</h3>
     <div class="sub" id="sync-status">${esc(syncText(s))}</div>
     <p class="note">Zmiany z telefonu pojawiają się tu w ciągu minuty, a stąd na telefonie tak samo.</p>
     <div class="foot"><button class="btn" id="disconnect">Rozłącz</button><span class="spacer"></span><button class="btn primary" id="now">Synchronizuj teraz</button></div>`,
    (m, close) => {
      $('#now', m).addEventListener('click', () => api.syncNow());
      $('#disconnect', m).addEventListener('click', () =>
        confirmBox('Rozłączyć synchronizację?', 'Biblioteka na tym komputerze zostanie, ale przestanie się zgadzać z telefonem.', 'Rozłącz', async () => {
          await api.syncDisconnect();
          close();
        }),
      );
    },
  );
}

wireUi();
requestAnimationFrame(tickProgress);
refresh();
