// YouTube na komputerze gra przez oficjalny odtwarzacz osadzony w oknie, wiec
// tutaj potrzebne sa tylko metadane (oEmbed) i wyszukiwanie (dla Spotify).
// Wyszukiwanie to to samo zapytanie JSON, ktore robi strona youtube.com.

import { DESKTOP_UA, getJson } from './net.mjs';

export async function youtubeMetadata(link) {
  const o = await getJson(
    `https://www.youtube.com/oembed?format=json&url=${encodeURIComponent(link.url)}`,
  ).catch(() => null);
  return {
    title: o?.title || `YouTube ${link.sourceId}`,
    author: o?.author_name || null,
    thumbnailUrl: `https://i.ytimg.com/vi/${link.sourceId}/hqdefault.jpg`,
    durationMs: 0, // odtwarzacz poda dlugosc przy pierwszym odtworzeniu
    // Szerokosc/wysokosc z oEmbed mowia, czy film jest poziomy, czy pionowy (Shorts).
    aspect: o?.width && o?.height ? Number(o.width) / Number(o.height) : 16 / 9,
    url: link.url,
    sourceId: link.sourceId,
  };
}

const VIDEOS_ONLY = 'EgIQAQ%3D%3D';

function* walk(node) {
  if (Array.isArray(node)) {
    for (const v of node) yield* walk(v);
  } else if (node && typeof node === 'object') {
    if (node.videoRenderer) yield node.videoRenderer;
    for (const v of Object.values(node)) yield* walk(v);
  }
}

function parseLength(text) {
  if (!text) return 0;
  return text.split(':').reduce((acc, part) => acc * 60 + Number(part || 0), 0);
}

/** Wyniki wyszukiwania filmow: id, tytul, kanal, dlugosc w sekundach. */
export async function searchYouTube(query) {
  const res = await fetch('https://www.youtube.com/youtubei/v1/search?prettyPrint=false', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': DESKTOP_UA },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB', clientVersion: '2.20251020.01.00', hl: 'pl', gl: 'PL' } },
      query,
      params: VIDEOS_ONLY,
    }),
    signal: AbortSignal.timeout(20000),
  });
  if (!res.ok) throw new Error(`YouTube odpowiedział ${res.status}`);
  const data = await res.json();
  const out = [];
  for (const v of walk(data)) {
    const seconds = parseLength(v.lengthText?.simpleText);
    if (!v.videoId || !seconds) continue; // transmisje na zywo nie maja dlugosci
    const badges = (v.ownerBadges ?? []).map((b) => b.metadataBadgeRenderer?.style ?? '');
    out.push({
      videoId: v.videoId,
      title: v.title?.runs?.map((r) => r.text).join('') ?? '',
      channel: v.ownerText?.runs?.[0]?.text ?? '',
      durationSec: seconds,
      verified: badges.some((b) => b.includes('VERIFIED')),
      thumbnailUrl: `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`,
    });
    if (out.length >= 12) break;
  }
  return out;
}
