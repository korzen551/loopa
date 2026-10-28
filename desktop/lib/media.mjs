// Lokalny serwer na 127.0.0.1: podaje okno aplikacji i pliki z TikToka.
//
// Okno ladujemy przez http://localhost, a nie z pliku - osadzony odtwarzacz
// YouTube'a odmawia dzialania na stronie bez zwyklego adresu (blad 153),
// a na adresie 127.0.0.1 tez (blad 150). Na localhost dziala.
//
// Filmy TikToka wymagaja naglowkow (Referer, ciasteczka), ktorych element
// <video> sam nie wysle, wiec idzie to przez ten serwer. Klip jest pobierany
// w calosci do pamieci: TikTok to kilka MB, a dwa odtwarzacze robiace petle
// bez przerwy czytaja go potem wiele razy bez siegania do sieci.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml' };
const CACHE_LIMIT = 400 * 1024 * 1024;

export class MediaServer {
  constructor(rendererDir, resolveStream) {
    this.rendererDir = rendererDir;
    this.resolveStream = resolveStream;
    this.token = crypto.randomBytes(16).toString('hex');
    this.cache = new Map(); // trackId -> { body, type, usedAt }
    this.pending = new Map();
    this.cacheBytes = 0;
  }

  async start() {
    this.server = http.createServer((req, res) => this.handle(req, res).catch((e) => {
      if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(String(e?.message ?? e));
    }));
    await new Promise((ok) => this.server.listen(0, '127.0.0.1', ok));
    this.origin = `http://localhost:${this.server.address().port}`;
    return this.origin;
  }

  mediaUrl(trackId) {
    return `${this.origin}/media/${this.token}/${encodeURIComponent(trackId)}`;
  }

  forget(trackId) {
    const e = this.cache.get(trackId);
    if (e) this.cacheBytes -= e.body.length;
    this.cache.delete(trackId);
  }

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

  async load(trackId) {
    const hit = this.cache.get(trackId);
    if (hit) {
      hit.usedAt = Date.now();
      return hit;
    }
    if (this.pending.has(trackId)) return this.pending.get(trackId);
    const job = (async () => {
      const stream = await this.resolveStream(trackId);
      const r = await fetch(stream.url, { headers: stream.headers, signal: AbortSignal.timeout(60000) });
      if (!r.ok) throw new Error(`Źródło odpowiedziało ${r.status}`);
      const body = Buffer.from(await r.arrayBuffer());
      const entry = { body, type: r.headers.get('content-type') || 'video/mp4', usedAt: Date.now() };
      this.cache.set(trackId, entry);
      this.cacheBytes += body.length;
      this.evict();
      return entry;
    })().finally(() => this.pending.delete(trackId));
    this.pending.set(trackId, job);
    return job;
  }

  evict() {
    const entries = [...this.cache.entries()].sort((a, b) => a[1].usedAt - b[1].usedAt);
    while (this.cacheBytes > CACHE_LIMIT && entries.length > 1) {
      const [id] = entries.shift();
      this.forget(id);
    }
  }

  async serveMedia(trackId, req, res) {
    const { body, type } = await this.load(trackId);
    const total = body.length;
    const range = req.headers.range?.match(/bytes=(\d*)-(\d*)/);
    if (!range) {
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': total, 'Accept-Ranges': 'bytes' });
      return res.end(body);
    }
    const start = range[1] ? Number(range[1]) : Math.max(0, total - Number(range[2]));
    const end = range[1] && range[2] ? Math.min(Number(range[2]), total - 1) : total - 1;
    if (start >= total) return res.writeHead(416, { 'Content-Range': `bytes */${total}` }).end();
    res.writeHead(206, {
      'Content-Type': type,
      'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${total}`,
      'Accept-Ranges': 'bytes',
    });
    res.end(body.subarray(start, end + 1));
  }
}
