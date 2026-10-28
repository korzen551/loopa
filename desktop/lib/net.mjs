// Wspolne zapytania HTTP. Przegladarkowy User-Agent, bo TikTok i Spotify
// inaczej odpowiadaja stronami dla botow.

export const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

export const PAGE_HEADERS = {
  'User-Agent': DESKTOP_UA,
  Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'pl-PL,pl;q=0.9,en;q=0.8',
};

export class ResolveError extends Error {}

/** Tekst strony + koncowy adres po przekierowaniach + ciasteczka. */
export async function getText(url, headers = {}, timeoutMs = 20000) {
  const res = await fetch(url, {
    headers: { 'User-Agent': DESKTOP_UA, ...headers },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await res.text();
  const cookies = (res.headers.getSetCookie?.() ?? [])
    .map((c) => c.split(';')[0])
    .filter((c) => c.includes('='))
    .join('; ');
  return { text, status: res.status, ok: res.ok, finalUrl: res.url, cookies };
}

export async function getJson(url, headers = {}) {
  const r = await getText(url, headers);
  if (!r.ok) return null;
  try {
    return JSON.parse(r.text);
  } catch {
    return null;
  }
}
