// Rozpoznawanie linkow - te same zasady co w apce na Androida (LinkParser.kt).

const URL_IN_TEXT = /https?:\/\/\S+/g;

const YT_WATCH = /[?&]v=([A-Za-z0-9_-]{6,})/;
const YT_SHORT = /youtu\.be\/([A-Za-z0-9_-]{6,})/;
const YT_PATH = /youtube\.com\/(?:shorts|embed|live|v)\/([A-Za-z0-9_-]{6,})/;

const TT_FULL = /tiktok\.com\/@[^/]+\/(?:video|photo)\/(\d{6,})/;
const TT_BARE = /tiktok\.com\/v\/(\d{6,})/;
const TT_SHORT = /(?:vm|vt)\.tiktok\.com\/([A-Za-z0-9]+)/;
const TT_T = /tiktok\.com\/t\/([A-Za-z0-9]+)/;

const T_PARAM = /[?&](?:t|start|time_continue)=([0-9hms]+)/i;

const SPOTIFY_WEB = /open\.spotify\.com\/(?:[^/?#\s]+\/)*?(track|playlist|album)\/([A-Za-z0-9]{22})/i;
const SPOTIFY_URI = /spotify:(track|playlist|album):([A-Za-z0-9]{22})/i;
const SPOTIFY_SHORT = /https?:\/\/(?:spotify\.link|spoti\.fi)\/[A-Za-z0-9_-]+/i;

export const youtubeUrl = (id) => `https://www.youtube.com/watch?v=${id}`;

function stripQuery(url) {
  return url.split('#')[0].split('?')[0].replace(/\/+$/, '');
}

/**
 * Wpisy ze zdjeciami maja adres /photo/, ale TikTok pod nim nie oddaje danych
 * wpisu ani oEmbed - ten sam wpis pod /video/ dziala.
 */
function normalizeTikTok(url) {
  return stripQuery(url).replace('://m.tiktok.com', '://www.tiktok.com').replace('/photo/', '/video/');
}

function parseStartMs(url) {
  const m = url.match(T_PARAM);
  if (!m) return 0;
  const raw = m[1];
  if (/^\d+$/.test(raw)) return Number(raw) * 1000;
  const hms = raw.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/i);
  if (!hms) return 0;
  const [, h = 0, mi = 0, s = 0] = hms;
  return (Number(h) * 3600 + Number(mi) * 60 + Number(s)) * 1000;
}

function parseSingle(url) {
  const startMs = parseStartMs(url);
  let m;
  if ((m = url.match(YT_SHORT)) || (m = url.match(YT_PATH)) || (url.includes('youtube.com') && (m = url.match(YT_WATCH)))) {
    return { source: 'youtube', sourceId: m[1], url: youtubeUrl(m[1]), startMs };
  }
  if ((m = url.match(TT_FULL)) || (m = url.match(TT_BARE))) {
    return { source: 'tiktok', sourceId: m[1], url: normalizeTikTok(url), startMs };
  }
  if ((m = url.match(TT_SHORT)) || (m = url.match(TT_T))) {
    return { source: 'tiktok', sourceId: `short_${m[1]}`, url: stripQuery(url), startMs, needsRedirect: true };
  }
  return null;
}

/** Pierwszy obslugiwany link z dowolnego tekstu (TikTok wkleja caly opis). */
export function parseVideoLink(text) {
  if (!text?.trim()) return null;
  const candidates = (text.match(URL_IN_TEXT) ?? []).map((u) => u.replace(/[.,)\]"]+$/, ''));
  const urls = candidates.length ? candidates : [text.trim()];
  for (const u of urls) {
    const parsed = parseSingle(u);
    if (parsed) return parsed;
  }
  return null;
}

export const trackIdOf = (link) => `${link.source === 'youtube' ? 'yt' : 'tt'}:${link.sourceId}`;

export function parseSpotifyLink(text) {
  if (!text) return null;
  const m = text.match(SPOTIFY_WEB) ?? text.match(SPOTIFY_URI);
  return m ? { kind: m[1].toLowerCase(), id: m[2] } : null;
}

export function spotifyShortLink(text) {
  return text?.match(SPOTIFY_SHORT)?.[0] ?? null;
}
