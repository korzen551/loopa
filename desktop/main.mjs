// Loopa na Windows - proces glowny: okno, biblioteka, TikTok/YouTube/Spotify.

import { app, BrowserWindow, ipcMain, session, shell } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { DEFAULT_LOOP, Library } from './lib/library.mjs';
import { MediaServer } from './lib/media.mjs';
import { parseVideoLink, trackIdOf, youtubeUrl } from './lib/links.mjs';
import { tiktokMetadata, tiktokStream } from './lib/tiktok.mjs';
import { youtubeMetadata } from './lib/youtube.mjs';
import { PREVIEW_LIMIT, resolveSpotify, spotifyCollection, spotifySong } from './lib/spotify.mjs';
import { findAll, findOnYouTube } from './lib/matcher.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

if (!app.requestSingleInstanceLock()) app.quit();

let win;
let library;
let media;
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
  for (const t of Object.values(library.data.tracks)) {
    if (!needsRefresh(t)) continue;
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
    if (track.source === 'youtube') return { kind: 'youtube', videoId: track.sourceId };
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
  ipcMain.handle('open:external', (_e, url) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
  });
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
      backgroundThrottling: !HIDDEN,
    },
  });
  if (HIDDEN) win.webContents.setAudioMuted(true);
  // Linki z okna (np. "Otworz zrodlo") ida do przegladarki, nie do nowego okna apki.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  await win.loadURL(`${media.origin}/`);
}

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.focus();
  }
});

app.whenReady().then(async () => {
  library = new Library(app.getPath('userData'));
  library.onChange(() => send('library:changed'));
  media = new MediaServer(path.join(here, 'renderer'), streamFor);
  await media.start();
  registerIpc();
  useChromeUserAgent();
  await createWindow();
  refreshStale();
});

app.on('window-all-closed', () => app.quit());
