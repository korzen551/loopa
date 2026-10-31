// Wspolna biblioteka z telefonem - ten sam plik w tajnym gist'cie na GitHubie
// i ten sam sposob laczenia co SyncEngine.kt w apce na Androida.
//
// Urzadzenie pamieta, jak biblioteka wygladala przy ostatniej synchronizacji.
// Porownanie z tym, co jest teraz tutaj, i z tym, co jest w chmurze, mowi, co
// zmienilo sie po ktorej stronie - takze co usunieto.

import fs from 'node:fs';
import path from 'node:path';
import { emptyDoc } from './library.mjs';

const FILE = 'loopa-library.json';
const API = 'https://api.github.com';
export const NEW_TOKEN_URL = 'https://github.com/settings/tokens/new?scopes=gist&description=Loopa';
const PERIOD_MS = 60_000;
const DEBOUNCE_MS = 2_500;

/** Porownanie niezalezne od kolejnosci kluczy. */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().filter((k) => value[k] !== undefined).map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}
const same = (a, b) => canonical(a) === canonical(b);

function merge3(base, local, remote) {
  const out = {};
  for (const key of new Set([...Object.keys(base), ...Object.keys(local), ...Object.keys(remote)])) {
    const b = base[key];
    const l = local[key];
    const r = remote[key];
    // Zmiana tylko po jednej stronie - bierzemy ja (takze usuniecie). Po obu:
    // wygrywa to urzadzenie, chyba ze tu usunieto, a tam edytowano.
    const value = same(l, b) ? r : same(r, b) ? l : l === undefined ? r : l;
    if (value !== undefined) out[key] = value;
  }
  return out;
}

export function mergeDocs(base, local, remote) {
  const merged = {
    tracks: merge3(base.tracks, local.tracks, remote.tracks),
    playlists: merge3(base.playlists, local.playlists, remote.playlists),
    items: merge3(base.items, local.items, remote.items),
  };
  for (const [key, i] of Object.entries(merged.items)) {
    if (!merged.playlists[i.playlist] || !merged.tracks[i.track]) delete merged.items[key];
  }
  return merged;
}

function normalize(doc) {
  return { tracks: doc?.tracks ?? {}, playlists: doc?.playlists ?? {}, items: doc?.items ?? {} };
}

class Gist {
  constructor(token) {
    this.token = token;
  }

  async call(url, init = {}) {
    const res = await fetch(url, {
      ...init,
      headers: {
        Authorization: `Bearer ${this.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'Loopa',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      },
      signal: AbortSignal.timeout(30000),
    });
    if (res.status === 401) throw new Error('GitHub nie przyjął tokenu — wklej go jeszcze raz.');
    if (res.status === 403) throw new Error('Token nie ma uprawnienia „gist”. Utwórz nowy z zaznaczonym „gist”.');
    if (res.status === 404) throw Object.assign(new Error('Nie ma już pliku biblioteki na GitHubie.'), { gone: true });
    if (!res.ok) throw new Error(`GitHub odpowiedział ${res.status}`);
    return res;
  }

  async findOrCreate() {
    for (let page = 1; page <= 10; page++) {
      const list = await (await this.call(`${API}/gists?per_page=100&page=${page}`)).json();
      const hit = list.find((g) => g.files?.[FILE]);
      if (hit) return hit.id;
      if (list.length < 100) break;
    }
    const created = await (
      await this.call(`${API}/gists`, {
        method: 'POST',
        body: JSON.stringify({
          description: 'Loopa – wspólna biblioteka (synchronizacja telefonu i komputera)',
          public: false,
          files: { [FILE]: { content: JSON.stringify({ version: 1, ...emptyDoc() }) } },
        }),
      })
    ).json();
    return created.id;
  }

  async read(id) {
    const gist = await (await this.call(`${API}/gists/${id}`)).json();
    const file = gist.files?.[FILE];
    if (!file) return emptyDoc();
    // Duzy plik GitHub ucina w odpowiedzi - wtedy cala tresc jest pod raw_url.
    const text = file.truncated ? await (await this.call(file.raw_url)).text() : file.content;
    try {
      return normalize(JSON.parse(text));
    } catch {
      return emptyDoc();
    }
  }

  async write(id, doc) {
    await this.call(`${API}/gists/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ files: { [FILE]: { content: JSON.stringify({ version: 1, ...doc }) } } }),
    });
  }
}

/**
 * Synchronizacja w tle: przy starcie, kilka sekund po kazdej zmianie i co minute.
 * Token jest szyfrowany przez system (safeStorage) i nie wychodzi poza ten komputer.
 */
export class SyncEngine {
  constructor(dir, library, safeStorage, onStatus) {
    this.configFile = path.join(dir, 'sync.json');
    this.stateFile = path.join(dir, 'sync-state.json');
    this.library = library;
    this.safeStorage = safeStorage;
    this.onStatus = onStatus;
    // Polaczenie wczytujemy raz i trzymamy w pamieci. Wczesniej plik byl czytany
    // przy kazdej synchronizacji, a jeden nieudany odczyt (plik akurat zapisywany,
    // apka ubita przy aktualizacji) wygladal jak "rozlaczono".
    this.config = this.readJson(this.configFile);
    this.cachedToken = null;
    this.status = { state: this.connected() ? 'working' : 'off' };
    this.running = null;
    this.timer = null;
  }

  /** Rozlaczyc moze tylko przycisk "Rozlacz" - brak pliku to jedyne "off". */
  connected() {
    return !!this.config?.token;
  }

  readJson(file) {
    for (let attempt = 0; attempt < 5; attempt++) {
      try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
      } catch (e) {
        if (e.code === 'ENOENT') return null;
        // Windows potrafi chwilowo zablokowac plik (antywirus, trwajacy zapis).
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
      }
    }
    return null;
  }

  writeJson(file, data) {
    fs.writeFileSync(`${file}.tmp`, JSON.stringify(data));
    for (let attempt = 0; ; attempt++) {
      try {
        fs.renameSync(`${file}.tmp`, file);
        return;
      } catch (e) {
        if (attempt >= 5) throw e;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 80);
      }
    }
  }

  async token() {
    if (this.cachedToken) return this.cachedToken;
    if (!this.connected()) return null;
    const encrypted = Buffer.from(this.config.token, 'base64');
    const { result, shouldReEncrypt } = await this.safeStorage.decryptStringAsync(encrypted);
    this.cachedToken = result;
    if (shouldReEncrypt) await this.saveConfig({ ...this.config, token: result });
    return result;
  }

  /** Token zawsze zaszyfrowany przez system Windows (DPAPI), nigdy jawnym tekstem. */
  async saveConfig({ token, gistId }) {
    this.config = { token: (await this.safeStorage.encryptStringAsync(token)).toString('base64'), gistId };
    this.writeJson(this.configFile, this.config);
  }

  setStatus(status) {
    this.status = status;
    this.onStatus(status);
  }

  start() {
    this.library.onChange(({ silent }) => {
      if (!silent) this.soon();
    });
    this.schedule(1000);
  }

  schedule(ms) {
    clearTimeout(this.timer);
    this.timer = setTimeout(async () => {
      await this.sync();
      this.schedule(PERIOD_MS);
    }, ms);
  }

  soon() {
    this.schedule(DEBOUNCE_MS);
  }

  async connect(token) {
    const clean = token.trim();
    try {
      const gistId = await new Gist(clean).findOrCreate();
      await this.saveConfig({ token: clean, gistId });
      this.cachedToken = clean;
      await this.sync();
      return this.status;
    } catch (e) {
      this.setStatus({ state: 'error', message: e.message });
      return this.status;
    }
  }

  disconnect() {
    fs.rmSync(this.configFile, { force: true });
    fs.rmSync(this.stateFile, { force: true });
    this.config = null;
    this.cachedToken = null;
    this.setStatus({ state: 'off' });
  }

  async sync() {
    if (this.running) return this.running;
    this.running = this.runOnce().finally(() => {
      this.running = null;
    });
    return this.running;
  }

  async runOnce() {
    if (!this.connected()) return this.setStatus({ state: 'off' });
    this.setStatus({ state: 'working' });
    try {
      // Problem z tokenem albo siecia to blad "na chwile" - za minute proba
      // sie powtorzy. Polaczenie zostaje, dopoki nie klikniesz "Rozlacz".
      const token = await this.token();
      const gist = new Gist(token);
      const cfg = this.config;
      let gistId = cfg.gistId ?? (await gist.findOrCreate());
      const state = this.readJson(this.stateFile);
      const local = structuredClone(this.library.doc);

      let remote;
      try {
        remote = await gist.read(gistId);
      } catch (e) {
        if (!e.gone) throw e;
        gistId = await gist.findOrCreate();
        remote = await gist.read(gistId);
      }
      if (gistId !== cfg.gistId) {
        this.config = { ...cfg, gistId };
        this.writeJson(this.configFile, this.config);
      }

      // Pusty plik w chmurze przy niepustej historii: ktos go skasowal albo
      // zalozyl od nowa - nie wolno uznac, ze wszystko usunieto.
      let base = normalize(state?.base);
      if (!Object.keys(remote.tracks).length && !Object.keys(remote.playlists).length && Object.keys(base.tracks).length) {
        base = emptyDoc();
      }

      const merged = mergeDocs(base, local, remote);

      // To, co dodano tu w trakcie synchronizacji, zostaje (nie bylo go w porownaniu).
      const now = this.library.doc;
      const result = structuredClone(merged);
      for (const kind of ['tracks', 'playlists', 'items']) {
        for (const [key, value] of Object.entries(now[kind])) {
          if (!(key in local[kind]) && !(key in result[kind])) result[kind][key] = value;
        }
      }
      if (!same(result, now)) this.library.replaceDoc(result);
      if (!same(merged, remote)) await gist.write(gistId, merged);
      this.writeJson(this.stateFile, { base: merged });
      this.setStatus({ state: 'ok', at: Date.now() });
    } catch (e) {
      this.setStatus({ state: 'error', message: e.message || 'Synchronizacja się nie udała.' });
    }
  }
}
