// TikTok - te same zasady co TikTokResolver.kt w apce na Androida:
// metadane z oEmbed (a dla wpisow ze zdjeciami ze strony wpisu), adres pliku
// z JSON-a osadzonego w stronie filmu.

import { DESKTOP_UA, PAGE_HEADERS, ResolveError, getJson, getText } from './net.mjs';
import { tiktokMusicId } from './links.mjs';

const UNIVERSAL = /<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>([\s\S]*?)<\/script>/;
const SIGI = /<script[^>]+id="SIGI_STATE"[^>]*>([\s\S]*?)<\/script>/;
const ID_IN_URL = /\/(?:video|photo)\/(\d{6,})/;
const FRONTITY = /<script[^>]+id="__FRONTITY_CONNECT_STATE__"[^>]*>([\s\S]*?)<\/script>/;

async function canonical(link) {
  let url = link.url.replace('/photo/', '/video/');
  if (!link.needsRedirect && tiktokMusicId(url)) return url;
  if (link.needsRedirect || !url.includes('/video/')) {
    const r = await getText(url, PAGE_HEADERS).catch(() => null);
    if (r?.finalUrl) url = r.finalUrl.split('?')[0];
  }
  return url.replace('/photo/', '/video/');
}

async function itemStruct(pageUrl) {
  const page = await getText(pageUrl, PAGE_HEADERS);
  const realId = pageUrl.match(ID_IN_URL)?.[1];
  let m = page.text.match(UNIVERSAL);
  if (m) {
    try {
      const it = JSON.parse(m[1]).__DEFAULT_SCOPE__['webapp.video-detail'].itemInfo.itemStruct;
      if (it) return { item: it, cookies: page.cookies };
    } catch {}
  }
  m = page.text.match(SIGI);
  if (m) {
    try {
      const modules = JSON.parse(m[1]).ItemModule;
      const key = realId && modules[realId] ? realId : Object.keys(modules)[0];
      if (key) return { item: modules[key], cookies: page.cookies };
    } catch {}
  }
  return { item: null, cookies: page.cookies };
}

function mediaHeaders(cookies) {
  return {
    'User-Agent': DESKTOP_UA,
    Referer: 'https://www.tiktok.com/',
    Accept: '*/*',
    'Accept-Encoding': 'identity;q=1, *;q=0',
    ...(cookies ? { Cookie: cookies } : {}),
  };
}

// ---------- dzwieki (tiktok.com/music/...) ----------

/**
 * Strona dzwieku nie zawiera pliku audio, ale jej wersja do osadzania podaje
 * filmy, ktore go uzywaja. Kazdy z nich niesie w swoich danych ten sam dzwiek
 * (music.playUrl) - bierzemy go z pierwszego, ktory sie otworzy.
 */
async function sound(musicId) {
  let state = null;
  for (let attempt = 0; attempt < 3 && !state; attempt++) {
    const page = await getText(`https://www.tiktok.com/embed/music/${musicId}`, PAGE_HEADERS).catch(() => null);
    const raw = page?.text.match(FRONTITY)?.[1];
    try {
      state = Object.values(JSON.parse(raw).source.data).find((d) => d?.videoList) ?? null;
    } catch {}
  }
  if (!state) throw new ResolveError('Nie znalazłem tego dźwięku na TikToku.');
  for (const v of (state.videoList ?? []).slice(0, 5)) {
    const { item, cookies } = await itemStruct(`https://www.tiktok.com/@${v.authorUniqueId}/video/${v.id}`).catch(() => ({}));
    const music = item?.music;
    if (String(music?.id) === musicId && music.playUrl?.startsWith('http')) {
      return { music, cookies, cover: state.embedInfo?.coverUrl || null, artist: state.embedInfo?.artist || null };
    }
  }
  throw new ResolveError('Nie udało się pobrać tego dźwięku z TikToka.');
}

async function musicMetadata(pageUrl, musicId) {
  const s = await sound(musicId);
  const title = s.music.title || 'Dźwięk z TikToka';
  const author = s.music.authorName || s.artist;
  return {
    title: `♬ ${title}${author ? ` – ${author}` : ''}`,
    author,
    thumbnailUrl: s.music.coverLarge || s.cover,
    durationMs: (s.music.duration ?? 0) * 1000,
    url: pageUrl,
    sourceId: `music_${musicId}`,
  };
}

export async function tiktokMetadata(link) {
  const pageUrl = await canonical(link);
  const musicId = tiktokMusicId(pageUrl);
  if (musicId) return musicMetadata(pageUrl, musicId);
  const realId = pageUrl.match(ID_IN_URL)?.[1] ?? link.sourceId;

  // Dla wpisow ze zdjeciami oEmbed oddaje sam szkielet bez tytulu i miniatury.
  const o = await getJson(`https://www.tiktok.com/oembed?url=${encodeURIComponent(pageUrl)}`).catch(() => null);
  if (o?.thumbnail_url) {
    return {
      title: o.title || `TikTok ${realId}`,
      author: o.author_name || null,
      thumbnailUrl: o.thumbnail_url,
      durationMs: 0,
      url: pageUrl,
      sourceId: realId,
    };
  }

  const { item } = await itemStruct(pageUrl).catch(() => ({ item: null }));
  const photo = item?.imagePost;
  return {
    title: item?.desc || `TikTok ${realId}`,
    author: item?.author?.nickname || item?.author?.uniqueId || null,
    thumbnailUrl: item?.video?.cover || photo?.cover?.imageURL?.urlList?.[0] || null,
    durationMs: ((photo ? item?.music?.duration : item?.video?.duration) ?? 0) * 1000,
    url: pageUrl,
    sourceId: realId,
  };
}

/**
 * Adres pliku do odtworzenia. Wpis ze zdjeciami nie ma wideo - wtedy url to
 * muzyka, a obraz to lista zdjec.
 */
export async function tiktokStream(link) {
  if (!link) throw new ResolveError('Tego linku Loopa nie umie odtworzyć.');
  const pageUrl = await canonical(link);
  const expiresAt = Date.now() + 20 * 60 * 1000;

  // Sam dzwiek - gra muzyka, a na ekranie jego okladka (jak wpis ze zdjeciami).
  const musicId = tiktokMusicId(pageUrl);
  if (musicId) {
    const s = await sound(musicId);
    const cover = s.music.coverLarge || s.cover;
    return { url: s.music.playUrl, headers: mediaHeaders(s.cookies), images: cover ? [cover] : [], durationMs: (s.music.duration ?? 0) * 1000, expiresAt };
  }

  const { item, cookies } = await itemStruct(pageUrl);
  if (!item) throw new ResolveError('Nie znalazłem danych tego wpisu na TikToku — może został usunięty albo jest prywatny.');
  const headers = mediaHeaders(cookies);

  const images = (item.imagePost?.images ?? [])
    .map((img) => img?.imageURL?.urlList?.[0])
    .filter((u) => u?.startsWith('http'));
  if (images.length) {
    const audio = item.music?.playUrl;
    if (!audio?.startsWith('http')) throw new ResolveError('Ten wpis ze zdjęciami nie ma dźwięku.');
    return { url: audio, headers, images, durationMs: (item.music?.duration ?? 0) * 1000, expiresAt };
  }

  const video = item.video ?? {};
  const url =
    [video.playAddr, video.downloadAddr].find((u) => typeof u === 'string' && u.startsWith('http')) ??
    video.bitrateInfo?.map((b) => b?.PlayAddr?.UrlList?.[0]).find((u) => u?.startsWith('http'));
  if (!url) throw new ResolveError('Brak adresu wideo w danych TikToka.');
  return { url, headers, images: [], durationMs: (video.duration ?? 0) * 1000, expiresAt };
}
