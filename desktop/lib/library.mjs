// Biblioteka w tym samym formacie, co wspolna biblioteka w chmurze i w apce na
// Androida (SyncDoc.kt): filmy po id, playlisty po wspolnym uid, wpisy jako
// para playlista/film. Dzieki temu synchronizacja to tylko porownanie dwoch
// takich dokumentow. Zapis przez plik tymczasowy - przerwany zapis nie psuje
// biblioteki.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export const DEFAULT_LOOP = { startMs: 0, loopStartMs: 0, endMs: 0, repeatMode: 'OFF', repeatCount: 3 };

export const emptyDoc = () => ({ tracks: {}, playlists: {}, items: {} });
export const itemKey = (playlist, track) => `${playlist}/${track}`;

const TRACK_FIELDS = ['source', 'sourceId', 'url', 'title', 'author', 'thumbnailUrl', 'durationMs', 'addedAt', 'loop'];

/** Ze starego formatu (pierwsza wersja na Windows) do wspolnego. */
function migrate(raw) {
  if (raw?.doc) return raw;
  const doc = emptyDoc();
  for (const t of Object.values(raw?.tracks ?? {})) {
    doc.tracks[t.id] = Object.fromEntries(TRACK_FIELDS.map((k) => [k, t[k] ?? null]));
    doc.tracks[t.id].loop = { ...DEFAULT_LOOP, ...t.loop };
  }
  const uids = {};
  for (const p of raw?.playlists ?? []) {
    const uid = crypto.randomUUID();
    uids[p.id] = uid;
    doc.playlists[uid] = {
      name: p.name, createdAt: p.createdAt ?? Date.now(), autoAdvance: !!p.autoAdvance, advanceOnFinish: false,
      useGlobalCount: !!p.autoAdvance, globalCount: p.playsPerItem || 1, usePerItemCount: false,
    };
  }
  for (const i of raw?.items ?? []) {
    const uid = uids[i.playlistId];
    if (uid && doc.tracks[i.trackId]) {
      doc.items[itemKey(uid, i.trackId)] = { playlist: uid, track: i.trackId, position: i.position, hasLoopOverride: false, loopOverride: { ...DEFAULT_LOOP }, playCount: 1 };
    }
  }
  return { version: 2, doc, local: { aspect: {} } };
}

export class Library {
  constructor(dir) {
    this.file = path.join(dir, 'library.json');
    fs.mkdirSync(dir, { recursive: true });
    let raw = null;
    try {
      raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch {}
    const data = migrate(raw);
    this.doc = data.doc;
    this.local = data.local ?? { aspect: {} };
    this.listeners = new Set();
  }

  onChange(fn) {
    this.listeners.add(fn);
  }

  save({ silent = false } = {}) {
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ version: 2, doc: this.doc, local: this.local }));
    fs.renameSync(tmp, this.file);
    for (const fn of this.listeners) fn({ silent });
  }

  /** Zastepuje caly dokument (po synchronizacji). */
  replaceDoc(doc) {
    this.doc = doc;
    this.save({ silent: true });
  }

  // ---------- widok dla okna ----------

  playlistItems(uid) {
    return Object.values(this.doc.items)
      .filter((i) => i.playlist === uid && this.doc.tracks[i.track])
      .sort((a, b) => a.position - b.position || a.track.localeCompare(b.track));
  }

  snapshot() {
    const tracks = Object.entries(this.doc.tracks)
      .map(([id, t]) => ({ id, ...t, aspect: this.local.aspect[id] }))
      .sort((a, b) => b.addedAt - a.addedAt);
    const playlists = Object.entries(this.doc.playlists)
      .sort((a, b) => b[1].createdAt - a[1].createdAt)
      .map(([id, p]) => ({
        id,
        name: p.name,
        autoAdvance: !!p.autoAdvance,
        playsPerItem: p.useGlobalCount ? p.globalCount || 1 : 1,
        trackIds: this.playlistItems(id).map((i) => i.track),
      }));
    return { tracks, playlists };
  }

  track(id) {
    const t = this.doc.tracks[id];
    return t ? { id, ...t, aspect: this.local.aspect[id] } : null;
  }

  // ---------- zmiany ----------

  /** Dodaje film; istniejacy zostaje nietkniety, razem ze swoja petla. */
  upsertTrack(track) {
    if (!this.doc.tracks[track.id]) {
      const t = { addedAt: Date.now(), loop: { ...DEFAULT_LOOP }, ...track };
      this.doc.tracks[track.id] = Object.fromEntries(TRACK_FIELDS.map((k) => [k, t[k] ?? null]));
      if (track.aspect) this.local.aspect[track.id] = track.aspect;
      this.save();
    }
    return this.track(track.id);
  }

  updateTrack(id, patch) {
    const t = this.doc.tracks[id];
    if (!t) return null;
    for (const k of TRACK_FIELDS) if (k in patch) t[k] = patch[k];
    this.save();
    return this.track(id);
  }

  deleteTrack(id) {
    delete this.doc.tracks[id];
    for (const [key, i] of Object.entries(this.doc.items)) if (i.track === id) delete this.doc.items[key];
    this.save();
  }

  createPlaylist(name) {
    const id = crypto.randomUUID();
    this.doc.playlists[id] = {
      name: name.trim() || 'Nowa playlista', createdAt: Date.now(), autoAdvance: false, advanceOnFinish: false,
      useGlobalCount: false, globalCount: 1, usePerItemCount: false,
    };
    this.save();
    return { id, name: this.doc.playlists[id].name };
  }

  /** Okno zna tylko nazwe, "przewijaj samo" i liczbe odtworzen - tlumaczymy na pola z telefonu. */
  updatePlaylist(id, patch) {
    const p = this.doc.playlists[id];
    if (!p) return null;
    if ('name' in patch) p.name = patch.name;
    if ('autoAdvance' in patch) {
      p.autoAdvance = !!patch.autoAdvance;
      if (p.autoAdvance && !p.usePerItemCount && !p.advanceOnFinish) p.useGlobalCount = true;
    }
    if ('playsPerItem' in patch) {
      p.useGlobalCount = true;
      p.usePerItemCount = false;
      p.advanceOnFinish = false;
      p.globalCount = patch.playsPerItem;
    }
    this.save();
    return p;
  }

  deletePlaylist(id) {
    delete this.doc.playlists[id];
    for (const [key, i] of Object.entries(this.doc.items)) if (i.playlist === id) delete this.doc.items[key];
    this.save();
  }

  /** Dokłada filmy na koniec playlisty, bez powtorzen, w podanej kolejnosci. */
  addToPlaylist(playlistId, trackIds) {
    if (!this.doc.playlists[playlistId]) return;
    let position = Math.max(-1, ...this.playlistItems(playlistId).map((i) => i.position)) + 1;
    for (const trackId of trackIds) {
      const key = itemKey(playlistId, trackId);
      if (this.doc.items[key] || !this.doc.tracks[trackId]) continue;
      this.doc.items[key] = { playlist: playlistId, track: trackId, position: position++, hasLoopOverride: false, loopOverride: { ...DEFAULT_LOOP }, playCount: 1 };
    }
    this.save();
  }

  /** Przesuwa film o [delta] miejsc; kolejnosc jedzie na telefon jak kazda zmiana. */
  move(playlistId, trackId, delta) {
    const items = this.playlistItems(playlistId);
    const from = items.findIndex((i) => i.track === trackId);
    const to = from + delta;
    if (from < 0 || to < 0 || to >= items.length) return;
    const [moved] = items.splice(from, 1);
    items.splice(to, 0, moved);
    items.forEach((item, index) => {
      item.position = index;
    });
    this.save();
  }

  removeFromPlaylist(playlistId, trackId) {
    delete this.doc.items[itemKey(playlistId, trackId)];
    this.save();
  }
}
