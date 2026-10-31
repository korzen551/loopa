// Loopa na Windows - proces glowny: okno, biblioteka, TikTok/YouTube/Spotify.

import { app, BrowserWindow, clipboard, ipcMain, Menu, nativeImage, safeStorage, screen, session, shell, Tray } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { DEFAULT_LOOP, Library } from './lib/library.mjs';
import { MediaServer } from './lib/media.mjs';
import { parseVideoLink, trackIdOf, youtubeUrl } from './lib/links.mjs';
import { tiktokMetadata, tiktokStream } from './lib/tiktok.mjs';
import { youtubeMetadata } from './lib/youtube.mjs';
import { PREVIEW_LIMIT, resolveSpotify, spotifyCollection, spotifySong } from './lib/spotify.mjs';
import { findAll, findOnYouTube } from './lib/matcher.mjs';
import { NEW_TOKEN_URL, SyncEngine } from './lib/sync.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

if (!app.requestSingleInstanceLock()) app.quit();

let win;
let library;
let media;
let sync;
const streams = new Map(); // trackId -> rozwiazany adres TikToka (wygasa)

async function streamFor(trackId) {
  const cached = streams.get(trackId);
  if (cached && cached.expiresAt > Date.now()) return cached;
  const track = library.track(trackId);
  if (!track) throw new Error('Nie ma takiego filmu w bibliotece.');
  const stream = await tiktokStream(parseVideoLink(track.url));
  streams.set(trackId, stream);
  if (!track.durationMs && stream.durationMs) library.updateTrack(trackId, { durationMs: stream.durationMs });
  return stream;
}

const send = (channel, payload) => win?.webContents.send(channel, payload);

function trackFromMeta(link, meta, extra = {}) {
  const resolved = { ...link, sourceId: meta.sourceId ?? link.sourceId };
  return {
    id: trackIdOf(resolved),
    source: link.source,
    sourceId: resolved.sourceId,
    url: meta.url ?? link.url,
    title: meta.title,
    author: meta.author,
    thumbnailUrl: meta.thumbnailUrl,
    durationMs: meta.durationMs ?? 0,
    ...(meta.aspect ? { aspect: meta.aspect } : {}),
    ...extra,
  };
}

async function addVideoLink(link) {
  // Krotki link TikToka trzeba rozwinac, zanim bedzie wiadomo, czy film juz jest.
  const meta = link.source === 'tiktok' ? await tiktokMetadata(link) : await youtubeMetadata(link);
  const track = trackFromMeta(link, meta);
  const existing = library.track(track.id);
  if (existing) return existing;
  const loop = link.startMs > 0 ? { startMs: link.startMs, loopStartMs: link.startMs } : {};
  return library.upsertTrack({ ...track, loop: { ...DEFAULT_LOOP, ...loop } });
}

function spotifyTrack(song, match) {
  return {
    id: `yt:${match.videoId}`,
    source: 'youtube',
    sourceId: match.videoId,
    url: youtubeUrl(match.videoId),
    title: song.title,
    author: song.artists.join(', ') || match.channel,
    thumbnailUrl: match.thumbnailUrl,
    durationMs: match.durationSec * 1000,
  };
}

// ---------- odswiezanie miniatur ----------

/** Podpisane miniatury TikToka wygasaja po kilku dniach - odswiezamy je w tle. */
function needsRefresh(t) {
  if (!t.thumbnailUrl || t.title === `TikTok ${t.sourceId}` || t.title === `YouTube ${t.sourceId}`) return true;
  if (t.source !== 'tiktok') return false;
  const exp = Number(t.thumbnailUrl.match(/[?&]x-expires=(\d+)/)?.[1] ?? 0);
  return exp > 0 && exp * 1000 < Date.now() + 6 * 3600 * 1000;
}

async function refreshStale() {
  for (const id of Object.keys(library.doc.tracks)) {
    const t = library.track(id);
    if (!t || !needsRefresh(t)) continue;
    try {
      const link = parseVideoLink(t.url);
      const meta = t.source === 'tiktok' ? await tiktokMetadata(link) : await youtubeMetadata(link);
      const placeholder = t.title === `TikTok ${t.sourceId}` || t.title === `YouTube ${t.sourceId}`;
      library.updateTrack(t.id, {
        title: placeholder ? meta.title : t.title,
        author: t.author ?? meta.author,
        thumbnailUrl: meta.thumbnailUrl ?? t.thumbnailUrl,
        durationMs: t.durationMs || meta.durationMs || 0,
      });
    } catch {}
    await new Promise((r) => setTimeout(r, 300));
  }
}

// ---------- IPC ----------

function registerIpc() {
  ipcMain.handle('library:get', () => library.snapshot());

  ipcMain.handle('link:add', async (_e, text) => {
    const video = parseVideoLink(text);
    if (video) return { type: 'track', track: await addVideoLink(video) };
    const spotify = await resolveSpotify(text).catch(() => null);
    if (spotify) return { type: 'spotify', link: spotify };
    return { type: 'error', message: 'To nie wygląda na link do TikToka, YouTube ani Spotify.' };
  });

  ipcMain.handle('spotify:song', async (_e, link) => {
    const song = await spotifySong(link.id);
    const match = await findOnYouTube(song);
    if (!match) return { found: false, song };
    return { found: true, song, match, track: library.upsertTrack(spotifyTrack(song, match)) };
  });

  ipcMain.handle('spotify:collection', async (_e, link) => {
    const c = await spotifyCollection(link);
    let done = 0;
    let found = 0;
    send('spotify:progress', { done, found, total: c.songs.length, name: c.name, coverUrl: c.coverUrl });
    const matches = await findAll(c.songs, (_i, m) => {
      done += 1;
      if (m) found += 1;
      send('spotify:progress', { done, found, total: c.songs.length, name: c.name, coverUrl: c.coverUrl });
    });
    const tracks = [];
    const missing = [];
    matches.forEach((m, i) => (m ? tracks.push(spotifyTrack(c.songs[i], m)) : missing.push(c.songs[i])));
    const unique = [...new Map(tracks.map((t) => [t.id, t])).values()];
    return { ...c, tracks: unique, missing, total: c.songs.length, previewLimit: PREVIEW_LIMIT };
  });

  ipcMain.handle('spotify:save', (_e, { tracks, playlistIds, newName }) => {
    const ids = tracks.map((t) => library.upsertTrack(t).id);
    const targets = [...playlistIds];
    if (newName) targets.push(library.createPlaylist(newName).id);
    for (const id of targets) library.addToPlaylist(id, ids);
    return targets.at(-1) ?? null;
  });

  ipcMain.handle('media:prepare', async (_e, trackId) => {
    const track = library.track(trackId);
    if (!track) throw new Error('Nie ma takiego filmu.');
    if (track.source === 'youtube') {
      // Filmy dodane na telefonie nie znaja ksztaltu - doczytujemy go raz, z oEmbed.
      let aspect = track.aspect;
      if (!aspect) {
        aspect = (await youtubeMetadata(parseVideoLink(track.url)).catch(() => null))?.aspect ?? 16 / 9;
        library.local.aspect[trackId] = aspect;
      }
      return { kind: 'youtube', videoId: track.sourceId, aspect };
    }
    const stream = await streamFor(trackId);
    await media.load(trackId); // caly klip w pamieci, zanim odtwarzacz zacznie
    return { kind: 'native', url: media.mediaUrl(trackId), images: stream.images };
  });

  ipcMain.handle('media:retry', (_e, trackId) => {
    streams.delete(trackId);
    media.forget(trackId);
  });

  ipcMain.handle('track:update', (_e, id, patch) => library.updateTrack(id, patch));
  ipcMain.handle('track:delete', (_e, id) => library.deleteTrack(id));
  ipcMain.handle('playlist:create', (_e, name) => library.createPlaylist(name));
  ipcMain.handle('playlist:update', (_e, id, patch) => library.updatePlaylist(id, patch));
  ipcMain.handle('playlist:delete', (_e, id) => library.deletePlaylist(id));
  ipcMain.handle('playlist:add', (_e, id, trackIds) => library.addToPlaylist(id, trackIds));
  ipcMain.handle('playlist:remove', (_e, id, trackId) => library.removeFromPlaylist(id, trackId));
  ipcMain.handle('playlist:move', (_e, id, trackId, delta) => library.move(id, trackId, delta));
  ipcMain.handle('open:external', (_e, url) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
  });

  ipcMain.handle('sync:status', () => ({ ...sync.status, tokenUrl: NEW_TOKEN_URL }));
  ipcMain.handle('sync:connect', (_e, token) => sync.connect(String(token ?? '')));
  ipcMain.handle('sync:disconnect', () => sync.disconnect());
  ipcMain.handle('sync:now', () => sync.sync());
}

// ---------- okno ----------

/**
 * Okno przedstawia sie jak zwykly Chrome. Z dopiskiem "Electron" w
 * User-Agencie osadzony odtwarzacz YouTube'a odmawia grania (blad 150).
 */
function useChromeUserAgent() {
  const ua = session.defaultSession.getUserAgent().replace(/\s(?:Electron|loopa-desktop|Loopa)\/\S+/gi, '');
  session.defaultSession.setUserAgent(ua);
}

// Testy automatyczne odpalaja apke z ukrytym oknem, zeby nic nie wyskakiwalo na ekran.
const HIDDEN = process.env.LOOPA_HIDDEN === '1';

async function createWindow() {
  win = new BrowserWindow({
    show: !HIDDEN,
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#000000',
    title: 'Loopa',
    icon: path.join(here, 'assets', 'icon.png'),
    autoHideMenuBar: true,
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#000000', symbolColor: '#ffffff', height: 36 },
    webPreferences: {
      preload: path.join(here, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // Film ma ruszyc z dzwiekiem od razu, jak w TikToku - bez klikania "graj".
      autoplayPolicy: 'no-user-gesture-required',
      // Petla ma dzialac takze przy zminimalizowanym oknie - jak w odtwarzaczu muzyki.
      backgroundThrottling: false,
    },
  });
  if (HIDDEN) win.webContents.setAudioMuted(true);
  // Linki z okna (np. "Otworz zrodlo") ida do przegladarki, nie do nowego okna apki.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  // Zamkniecie okna chowa Loopa do zasobnika: dalej dziala zapisywanie
  // skopiowanych linkow. Calkiem wylacza "Zakoncz" w menu ikony.
  win.on('close', (e) => {
    if (quitting || HIDDEN) return;
    e.preventDefault();
    win.webContents.send('player:pause');
    win.hide();
    if (!trayHintShown) {
      trayHintShown = true;
      tray?.displayBalloon?.({
        iconType: 'info',
        title: 'Loopa działa w tle',
        content: 'Skopiuj link z TikToka, YouTube albo Spotify, a zapytam, czy go zapisać. Wyłączysz mnie w ikonie przy zegarze.',
      });
    }
  });
  await win.loadURL(`${media.origin}/`);
}

function showMain() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

let quitting = false;
let trayHintShown = false;
let tray = null;

function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(here, 'assets', 'icon.png')).resize({ width: 16, height: 16 }));
  tray.setToolTip('Loopa');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'Otwórz Loopa', click: showMain },
      { type: 'separator' },
      { label: 'Zakończ', click: () => { quitting = true; app.quit(); } },
    ]),
  );
  tray.on('click', showMain);
}

// ---------- "Udostepnij" na komputerze: skopiowany link ----------

/**
 * W przegladarce "Udostepnij" TikToka czy YouTube'a nie pozwala dopisac wlasnej
 * aplikacji, ale zawsze ma "Kopiuj link". Gdy w schowku pojawi sie link do
 * TikToka, YouTube'a albo Spotify, w rogu ekranu wyskakuje male okienko
 * "Zapisz w Loopa" - jak polarkusz na telefonie. Schowek jest tylko czytany
 * tutaj, na komputerze; nic z niego nie wychodzi bez klikniecia "Zapisz".
 */
let lastClip = '';
let quick = null;

/** Od Electrona 45 odczyt schowka jest asynchroniczny (jak navigator.clipboard). */
const readClipboard = async () => String((await Promise.resolve(clipboard.readText()).catch(() => '')) ?? '');

async function watchClipboard() {
  lastClip = await readClipboard();
  let busy = false;
  setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      await checkClipboard();
    } finally {
      busy = false;
    }
  }, 800);
}

async function checkClipboard() {
  const text = await readClipboard();
  if (text === lastClip) return;
  lastClip = text;
  const trimmed = text.trim();
  if (trimmed.length > 2000) return;
  const video = parseVideoLink(trimmed);
  const spotify = !video && /open\.spotify\.com\/|spotify\.link\/|spoti\.fi\//i.test(trimmed);
  if ((video || spotify) && !win?.isFocused()) showQuickSave(trimmed);
}

function showQuickSave(text) {
  const { workArea } = screen.getPrimaryDisplay();
  const width = 380;
  const height = 300;
  if (!quick || quick.isDestroyed()) {
    quick = new BrowserWindow({
      width,
      height,
      x: workArea.x + workArea.width - width - 16,
      y: workArea.y + workArea.height - height - 16,
      frame: false,
      resizable: false,
      alwaysOnTop: true,
      skipTaskbar: true,
      show: false,
      backgroundColor: '#121212',
      webPreferences: { preload: path.join(here, 'preload.cjs'), contextIsolation: true, sandbox: true },
    });
    quick.loadURL(`${media.origin}/quick.html`);
  }
  const deliver = () => {
    quick.webContents.send('quick:link', text);
    quick.showInactive();
  };
  quick.webContents.isLoading() ? quick.webContents.once('did-finish-load', deliver) : deliver();
}

function registerQuickIpc() {
  ipcMain.handle('link:preview', async (_e, text) => {
    const video = parseVideoLink(text);
    if (!video) return { type: 'spotify' };
    const meta = video.source === 'tiktok' ? await tiktokMetadata(video) : await youtubeMetadata(video);
    const id = trackIdOf({ ...video, sourceId: meta.sourceId ?? video.sourceId });
    const inPlaylists = library.snapshot().playlists.filter((p) => p.trackIds.includes(id)).map((p) => p.id);
    return { type: 'track', id, source: video.source, title: meta.title, author: meta.author, thumbnailUrl: meta.thumbnailUrl, exists: !!library.track(id), inPlaylists };
  });
  ipcMain.handle('quick:close', () => quick?.hide());
  // Spotify i "Otworz" - dalsza obsluga w glownym oknie (import playlisty itd.).
  ipcMain.handle('quick:open', (_e, text) => {
    quick?.hide();
    showMain();
    if (text) send('link:open', text);
  });
}

app.on('second-instance', showMain);

app.whenReady().then(async () => {
  library = new Library(app.getPath('userData'));
  library.onChange(() => send('library:changed'));
  media = new MediaServer(path.join(here, 'renderer'), streamFor);
  await media.start();
  sync = new SyncEngine(app.getPath('userData'), library, safeStorage, (status) => send('sync:status', status));
  registerIpc();
  registerQuickIpc();
  useChromeUserAgent();
  if (!HIDDEN) createTray();
  await createWindow();
  if (!HIDDEN) watchClipboard();
  sync.start();
  // Miniatury odswiezamy dopiero po pierwszej synchronizacji - dochodza filmy z telefonu.
  setTimeout(refreshStale, 15000);
});

app.on('before-quit', () => {
  quitting = true;
});

app.on('window-all-closed', () => {
  if (quitting || HIDDEN) app.quit();
});
