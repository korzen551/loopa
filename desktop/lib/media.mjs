// Lokalny serwer na 127.0.0.1: podaje okno aplikacji i pliki z TikToka.
//
// Okno ladujemy przez http://localhost, a nie z pliku - osadzony odtwarzacz
// YouTube'a odmawia dzialania na stronie bez zwyklego adresu (blad 153),
// a na adresie 127.0.0.1 tez (blad 150). Na localhost dziala.
//
// Filmy TikToka wymagaja naglowkow (Referer, ciasteczka), ktorych element
// <video> sam nie wysle, wiec idzie to przez ten serwer.
//
// Serwer TikToka wysyla plik celowo powoli - mniej wiecej w tempie odtwarzania
// (5 MB potrafi isc kilkanascie sekund, kilka polaczen naraz nie pomaga).
// Dlatego film gra od pierwszych bajtow, a reszta dociaga sie w trakcie:
// odtwarzacz dostaje to, co juz jest, i czeka tylko na brakujacy kawalek.
// Pobrany w calosci klip zostaje w pamieci (dwa odtwarzacze robiace petle bez
// przerwy czytaja go wiele razy) i na dysku - po ponownym uruchomieniu
// komputera obejrzane filmy startuja od razu, bez siegania do TikToka.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { EventEmitter, once } from 'node:events';

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml' };
const MEMORY_LIMIT = 400 * 1024 * 1024;
const DISK_LIMIT = 1024 * 1024 * 1024;
/**
 * Odtwarzacz prosi o kawalek daleko przed tym, co juz pobrano (film zaczyna sie
 * od srodka) - wtedy ten kawalek pobieramy osobno, zamiast czekac, az glowne
 * pobieranie do niego dojdzie.
 */
const JUMP_BYTES = 512 * 1024;
/** Brak nowych danych przez tyle czasu = zerwane pobieranie. */
const STALL_MS = 30000;

/** Jeden klip: rosnacy bufor, ktory mozna czytac, zanim sie zapelni. */
class Clip {
  constructor(foreground) {
    this.foreground = foreground;
    this.total = 0; // 0 = jeszcze nieznany
    this.body = null;
    this.received = 0;
    this.done = false;
    this.error = null;
    this.type = 'video/mp4';
    this.photo = false;
    this.images = [];
    this.source = null; // { url, headers } - do osobnego pobrania kawalka
    this.fetching = false;
    this.usedAt = Date.now();
    this.events = new EventEmitter();
    this.events.setMaxListeners(0);
    this.controller = new AbortController();
  }

  progress() {
    this.events.emit('progress');
  }

  /** Czeka, az bedzie co najmniej [bytes] bajtow (albo koniec pliku). */
  async until(bytes) {
    while (!this.error && !this.done && !(this.total && this.received >= Math.min(bytes, this.total))) {
      await once(this.events, 'progress');
    }
    if (this.error && !(this.total && this.received >= Math.min(bytes, this.total))) throw this.error;
  }
}

export class MediaServer {
  constructor(rendererDir, resolveStream, cacheDir) {
    this.rendererDir = rendererDir;
    this.resolveStream = resolveStream;
    this.cacheDir = cacheDir;
    fs.mkdirSync(cacheDir, { recursive: true });
    this.token = crypto.randomBytes(16).toString('hex');
    this.clips = new Map(); // trackId -> Clip
    this.queue = new EventEmitter();
    this.queue.setMaxListeners(0);
  }

  async start() {
    this.server = http.createServer((req, res) => this.handle(req, res).catch((e) => {
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(String(e?.message ?? e));
      } else {
        res.destroy();
      }
    }));
    await new Promise((ok) => this.server.listen(0, '127.0.0.1', ok));
    this.origin = `http://localhost:${this.server.address().port}`;
    return this.origin;
  }

  mediaUrl(trackId) {
    return `${this.origin}/media/${this.token}/${encodeURIComponent(trackId)}`;
  }

  // ---------- dysk ----------

  diskName(trackId) {
    return path.join(this.cacheDir, crypto.createHash('sha1').update(trackId).digest('hex'));
  }

  /** Czy klip jest juz caly na dysku; dla wpisow ze zdjeciami i tak trzeba zajrzec do TikToka po zdjecia. */
  cachedOnDisk(trackId) {
    try {
      const meta = JSON.parse(fs.readFileSync(`${this.diskName(trackId)}.json`, 'utf8'));
      return fs.existsSync(`${this.diskName(trackId)}.bin`) ? meta : null;
    } catch {
      return null;
    }
  }

  async saveToDisk(trackId, clip) {
    const base = this.diskName(trackId);
    try {
      await fs.promises.writeFile(`${base}.bin.tmp`, clip.body);
      await fs.promises.rename(`${base}.bin.tmp`, `${base}.bin`);
      // Adresy zdjec sa podpisane i wygasaja (x-expires) - zapisujemy je razem z data waznosci.
      const expires = clip.images.map((u) => Number(u.match(/[?&]x-expires=(\d+)/)?.[1] ?? 0) * 1000);
      const imagesValidUntil = expires.length && expires.every(Boolean) ? Math.min(...expires) : 0;
      await fs.promises.writeFile(`${base}.json`, JSON.stringify({ type: clip.type, photo: clip.photo, images: clip.images, imagesValidUntil }));
      await this.trimDisk();
    } catch {}
  }

  /** Najdawniej ogladane klipy wylatuja, gdy na dysku jest ich za duzo. */
  async trimDisk() {
    const files = [];
    for (const name of await fs.promises.readdir(this.cacheDir)) {
      if (!name.endsWith('.bin')) continue;
      const stat = await fs.promises.stat(path.join(this.cacheDir, name)).catch(() => null);
      if (stat) files.push({ name, size: stat.size, at: stat.mtimeMs });
    }
    let total = files.reduce((sum, f) => sum + f.size, 0);
    for (const f of files.sort((a, b) => a.at - b.at)) {
      if (total <= DISK_LIMIT) break;
      const base = path.join(this.cacheDir, f.name.slice(0, -4));
      await fs.promises.rm(`${base}.bin`, { force: true });
      await fs.promises.rm(`${base}.json`, { force: true });
      total -= f.size;
    }
  }

  // ---------- pobieranie ----------

  forget(trackId) {
    const clip = this.clips.get(trackId);
    clip?.controller.abort();
    this.clips.delete(trackId);
    const base = this.diskName(trackId);
    fs.rmSync(`${base}.bin`, { force: true });
    fs.rmSync(`${base}.json`, { force: true });
  }

  /**
   * Zaczyna (albo podaje juz trwajace) pobieranie. Film na ekranie ma
   * pierwszenstwo: sasiedzi pobierani "na zapas" czekaja, az skonczy sie
   * pobieranie tego, co gra - inaczej zabieraliby mu i tak wolne lacze.
   */
  load(trackId, { background = false } = {}) {
    let clip = this.clips.get(trackId);
    if (clip && !clip.error) {
      clip.usedAt = Date.now();
      if (!background && !clip.foreground) {
        clip.foreground = true;
        this.dropOthers(trackId);
        this.queue.emit('change');
      }
      return clip;
    }
    clip = new Clip(!background);
    this.clips.set(trackId, clip);
    if (!background) this.dropOthers(trackId);
    this.download(trackId, clip).catch((e) => {
      clip.error = e?.name === 'AbortError' || e?.name === 'TimeoutError' ? new Error('Pobieranie z TikToka się zerwało.') : e;
      clip.progress();
      this.queue.emit('change');
    });
    return clip;
  }

  /**
   * Nowy film na ekranie: niedokonczone pobierania filmow, ktore juz
   * przewinieto, przerywamy - inaczej zabieraja lacze temu, co gra (TikTok i tak
   * daje malo). Sasiedzi czekajacy w kolejce zostaja i rusza po nim.
   */
  dropOthers(currentId) {
    for (const [id, c] of this.clips) {
      if (id === currentId || c.done || c.error) continue;
      c.foreground = false;
      if (c.fetching) {
        c.controller.abort();
        this.clips.delete(id);
      }
    }
  }

  busyWithForeground() {
    for (const c of this.clips.values()) if (c.foreground && !c.done && !c.error) return true;
    return false;
  }

  async download(trackId, clip) {
    const base = this.diskName(trackId);
    const meta = this.cachedOnDisk(trackId);
    if (meta) {
      const body = await fs.promises.readFile(`${base}.bin`).catch(() => null);
      if (body) {
        fs.promises.utimes(`${base}.bin`, new Date(), new Date()).catch(() => {});
        Object.assign(clip, { body, total: body.length, received: body.length, done: true, type: meta.type, photo: meta.photo });
        clip.progress();
        this.evictMemory();
        return;
      }
    }

    const stream = await this.resolveStream(trackId);
    clip.photo = !!stream.images?.length;
    clip.images = stream.images ?? [];
    clip.source = { url: stream.url, headers: stream.headers };
    while (!clip.foreground && this.busyWithForeground()) await once(this.queue, 'change');

    clip.fetching = true;
    const r = await fetch(stream.url, { headers: stream.headers, signal: clip.controller.signal });
    if (!r.ok) throw new Error(`Źródło odpowiedziało ${r.status}`);
    clip.type = r.headers.get('content-type') || 'video/mp4';
    const length = Number(r.headers.get('content-length') || 0);
    const parts = [];
    if (length > 0) {
      clip.total = length;
      clip.body = Buffer.allocUnsafe(length);
    }

    let stall = setTimeout(() => clip.controller.abort(), STALL_MS);
    try {
      for await (const chunk of r.body) {
        clearTimeout(stall);
        stall = setTimeout(() => clip.controller.abort(), STALL_MS);
        if (clip.body && clip.received + chunk.length <= clip.total) {
          clip.body.set(chunk, clip.received);
        } else {
          parts.push(Buffer.from(chunk));
        }
        clip.received += chunk.length;
        clip.progress();
      }
    } finally {
      clearTimeout(stall);
    }

    // Bez dlugosci w naglowkach (albo dluzszy plik niz zapowiadano) - skladamy na koncu.
    if (parts.length) {
      clip.body = Buffer.concat([clip.body ? clip.body.subarray(0, clip.total) : Buffer.alloc(0), ...parts]);
    } else if (clip.body && clip.received < clip.total) {
      throw new Error('Pobieranie z TikToka się urwało.');
    }
    clip.total = clip.body.length;
    clip.received = clip.total;
    clip.done = true;
    clip.progress();
    this.queue.emit('change');
    this.evictMemory();
    this.saveToDisk(trackId, clip);
  }

  /**
   * Czeka tylko, az pobieranie ruszy (i nie skonczylo sie bledem) - dalej
   * odtwarzacz sam bierze dane w miare, jak przychodza.
   */
  async ready(trackId) {
    const clip = this.load(trackId);
    await clip.until(1);
    return clip;
  }

  evictMemory() {
    let bytes = 0;
    const done = [...this.clips.entries()].filter(([, c]) => c.done).sort((a, b) => b[1].usedAt - a[1].usedAt);
    for (const [id, c] of done) {
      bytes += c.body.length;
      if (bytes > MEMORY_LIMIT) this.clips.delete(id);
    }
  }

  // ---------- serwer ----------

  async handle(req, res) {
    const url = new URL(req.url, this.origin);
    const parts = url.pathname.split('/').filter(Boolean);

    if (parts[0] === 'media') {
      // Token w adresie - inne programy na tym komputerze nie maja tu wstepu.
      if (parts[1] !== this.token) return res.writeHead(403).end();
      return this.serveMedia(decodeURIComponent(parts[2] ?? ''), req, res);
    }

    const rel = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    const file = path.normalize(path.join(this.rendererDir, rel));
    if (!file.startsWith(this.rendererDir) || !fs.existsSync(file)) return res.writeHead(404).end();
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  }

  /** Odpowiada od razu tym, co juz jest, i dosyla reszte, gdy przyjdzie. */
  async serveMedia(trackId, req, res) {
    const clip = this.load(trackId);
    await clip.until(1);
    if (!clip.total) await clip.until(Infinity); // dlugosc nieznana, dopoki plik nie dojdzie
    const total = clip.total;

    const range = req.headers.range?.match(/bytes=(\d*)-(\d*)/);
    let start = 0;
    let end = total - 1;
    if (range) {
      start = range[1] ? Number(range[1]) : Math.max(0, total - Number(range[2]));
      end = range[1] && range[2] ? Math.min(Number(range[2]), total - 1) : total - 1;
      if (start >= total) return res.writeHead(416, { 'Content-Range': `bytes */${total}` }).end();
      res.writeHead(206, {
        'Content-Type': clip.type,
        'Content-Length': end - start + 1,
        'Content-Range': `bytes ${start}-${end}/${total}`,
        'Accept-Ranges': 'bytes',
      });
    } else {
      res.writeHead(200, { 'Content-Type': clip.type, 'Content-Length': total, 'Accept-Ranges': 'bytes' });
    }

    let closed = false;
    res.on('close', () => {
      closed = true;
      clip.progress(); // obudz czekajacych na dane - odtwarzacz juz nie chce tej odpowiedzi
    });
    let pos = start;
    if (!clip.done && clip.source && start > clip.received + JUMP_BYTES) {
      pos = await this.pipeFromSource(clip, start, end, res, () => closed);
    }
    while (pos <= end && !closed) {
      await clip.until(pos + 1);
      if (closed) break;
      const available = Math.min(clip.received, end + 1);
      if (available <= pos) continue;
      // Kawalek z gotowej czesci bufora - ta czesc juz sie nie zmieni.
      const piece = clip.body.subarray(pos, available);
      pos = available;
      if (!(await write(res, piece))) break;
    }
    if (!closed) res.end();
  }

  /** Kawalek prosto z TikToka; zwraca, dokad doszedl (reszte dosle bufor). */
  async pipeFromSource(clip, start, end, res, isClosed) {
    const controller = new AbortController();
    res.once('close', () => controller.abort());
    let pos = start;
    try {
      const r = await fetch(clip.source.url, {
        headers: { ...clip.source.headers, Range: `bytes=${start}-${end}` },
        signal: controller.signal,
      });
      if (r.status !== 206) {
        controller.abort();
        return pos;
      }
      for await (const chunk of r.body) {
        if (isClosed()) break;
        const piece = Buffer.from(chunk).subarray(0, end + 1 - pos);
        pos += piece.length;
        if (!(await write(res, piece))) break;
        // Glowne pobieranie dogonilo - reszte bierzemy juz z bufora.
        if (clip.received > pos || pos > end) break;
      }
    } catch {}
    controller.abort();
    return pos;
  }
}

/** Zapis z poszanowaniem predkosci odbiorcy; false, gdy polaczenie zamknieto. */
function write(res, data) {
  if (res.destroyed) return Promise.resolve(false);
  if (res.write(data)) return Promise.resolve(true);
  return new Promise((resolve) => {
    const done = (ok) => {
      res.off('drain', onDrain);
      res.off('close', onClose);
      resolve(ok);
    };
    const onDrain = () => done(true);
    const onClose = () => done(false);
    res.on('drain', onDrain);
    res.on('close', onClose);
  });
}
