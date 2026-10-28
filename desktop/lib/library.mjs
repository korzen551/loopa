// Biblioteka: filmy, playlisty i ustawienia petli w jednym pliku JSON
// w katalogu danych aplikacji. Zapis przez plik tymczasowy, zeby przerwany
// zapis nie zostawil uszkodzonej biblioteki.

import fs from 'node:fs';
import path from 'node:path';

const EMPTY = { version: 1, tracks: {}, playlists: [], items: [], nextId: 1 };

export const DEFAULT_LOOP = { startMs: 0, loopStartMs: 0, endMs: 0, repeatMode: 'LOOP', repeatCount: 3 };

export class Library {
  constructor(dir) {
    this.file = path.join(dir, 'library.json');
    fs.mkdirSync(dir, { recursive: true });
    try {
      this.data = { ...EMPTY, ...JSON.parse(fs.readFileSync(this.file, 'utf8')) };
    } catch {
      this.data = structuredClone(EMPTY);
    }
    this.listeners = new Set();
  }

  onChange(fn) {
    this.listeners.add(fn);
  }

  save() {
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data));
    fs.renameSync(tmp, this.file);
    for (const fn of this.listeners) fn();
  }

  snapshot() {
    const d = this.data;
    return {
      tracks: Object.values(d.tracks).sort((a, b) => b.addedAt - a.addedAt),
      playlists: d.playlists.map((p) => ({
        ...p,
        trackIds: d.items
          .filter((i) => i.playlistId === p.id)
          .sort((a, b) => a.position - b.position)
          .map((i) => i.trackId),
      })),
    };
  }

  track(id) {
    return this.data.tracks[id] ?? null;
  }

  /** Dodaje film; istniejacy zostaje nietkniety, razem ze swoja petla. */
  upsertTrack(track) {
    if (!this.data.tracks[track.id]) {
      this.data.tracks[track.id] = { addedAt: Date.now(), loop: { ...DEFAULT_LOOP }, ...track };
      this.save();
    }
    return this.data.tracks[track.id];
  }

  updateTrack(id, patch) {
    const t = this.data.tracks[id];
    if (!t) return null;
    Object.assign(t, patch);
    this.save();
    return t;
  }

  deleteTrack(id) {
    delete this.data.tracks[id];
    this.data.items = this.data.items.filter((i) => i.trackId !== id);
    this.save();
  }

  createPlaylist(name) {
    const p = {
      id: this.data.nextId++,
      name: name.trim() || 'Nowa playlista',
      createdAt: Date.now(),
      autoAdvance: false,
      playsPerItem: 1,
    };
    this.data.playlists.push(p);
    this.save();
    return p;
  }

  updatePlaylist(id, patch) {
    const p = this.data.playlists.find((x) => x.id === id);
    if (p) {
      Object.assign(p, patch);
      this.save();
    }
    return p;
  }

  deletePlaylist(id) {
    this.data.playlists = this.data.playlists.filter((p) => p.id !== id);
    this.data.items = this.data.items.filter((i) => i.playlistId !== id);
    this.save();
  }

  /** Dokłada filmy na koniec playlisty, bez powtorzen, w podanej kolejnosci. */
  addToPlaylist(playlistId, trackIds) {
    const existing = new Set(this.data.items.filter((i) => i.playlistId === playlistId).map((i) => i.trackId));
    let position = Math.max(-1, ...this.data.items.filter((i) => i.playlistId === playlistId).map((i) => i.position)) + 1;
    for (const trackId of trackIds) {
      if (existing.has(trackId) || !this.data.tracks[trackId]) continue;
      existing.add(trackId);
      this.data.items.push({ id: this.data.nextId++, playlistId, trackId, position: position++ });
    }
    this.save();
  }

  removeFromPlaylist(playlistId, trackId) {
    this.data.items = this.data.items.filter((i) => !(i.playlistId === playlistId && i.trackId === trackId));
    this.save();
  }
}
