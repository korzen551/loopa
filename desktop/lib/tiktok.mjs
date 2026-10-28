// TikTok - te same zasady co TikTokResolver.kt w apce na Androida:
// metadane z oEmbed (a dla wpisow ze zdjeciami ze strony wpisu), adres pliku
// z JSON-a osadzonego w stronie filmu.

import { DESKTOP_UA, PAGE_HEADERS, ResolveError, getJson, getText } from './net.mjs';

const UNIVERSAL = /<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>([\s\S]*?)<\/script>/;
const SIGI = /<script[^>]+id="SIGI_STATE"[^>]*>([\s\S]*?)<\/script>/;
const ID_IN_URL = /\/(?:video|photo)\/(\d{6,})/;

async function canonical(link) {
  let url = link.url.replace('/photo/', '/video/');
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

export async function tiktokMetadata(link) {
  const pageUrl = await canonical(link);
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
  const pageUrl = await canonical(link);
  const { item, cookies } = await itemStruct(pageUrl);
  if (!item) throw new ResolveError('Nie znalazłem danych tego wpisu na TikToku — może został usunięty albo jest prywatny.');

  const headers = {
    'User-Agent': DESKTOP_UA,
    Referer: 'https://www.tiktok.com/',
    Accept: '*/*',
    'Accept-Encoding': 'identity;q=1, *;q=0',
    ...(cookies ? { Cookie: cookies } : {}),
  };
  const expiresAt = Date.now() + 20 * 60 * 1000;

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
