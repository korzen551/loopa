// Spotify bez konta i klucza API - z publicznej strony podgladu do osadzania
// (open.spotify.com/embed/...), tak jak SpotifyClient w apce na Androida.
// Podglad playlisty pokazuje najwyzej 100 utworow.

import { PAGE_HEADERS, ResolveError, getText } from './net.mjs';
import { parseSpotifyLink, spotifyShortLink } from './links.mjs';

export const PREVIEW_LIMIT = 100;
const NEXT_DATA = /<script[^>]+id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/;

export async function resolveSpotify(text) {
  const direct = parseSpotifyLink(text);
  if (direct) return direct;
  const short = spotifyShortLink(text);
  if (!short) return null;
  const r = await getText(short, PAGE_HEADERS);
  return parseSpotifyLink(r.finalUrl) ?? parseSpotifyLink(r.text);
}

async function entity(link) {
  const r = await getText(`https://open.spotify.com/embed/${link.kind}/${link.id}`, PAGE_HEADERS);
  if (!r.ok) throw new ResolveError(`Spotify odrzucił zapytanie (kod ${r.status}). Spróbuj za chwilę.`);
  const raw = r.text.match(NEXT_DATA)?.[1];
  if (!raw) throw new ResolveError('Spotify zmienił stronę podglądu i nie umiem jej odczytać.');
  const e = JSON.parse(raw)?.props?.pageProps?.state?.data?.entity;
  if (!e) {
    throw new ResolveError(
      link.kind === 'playlist'
        ? 'Spotify nie pokazuje tej playlisty — może jest prywatna albo usunięta.'
        : 'Spotify nie pokazuje tego utworu.',
    );
  }
  return e;
}

/**
 * Wykonawcy w podgladzie playlisty sa sklejeni przecinkiem i TWARDA spacja.
 * Zwykly przecinek bywa czescia nazwy ("Tyler, The Creator").
 */
const splitArtists = (s) => (s ?? '').split(', ').map((a) => a.trim()).filter(Boolean);

export async function spotifySong(id) {
  const e = await entity({ kind: 'track', id });
  return {
    title: e.name || e.title,
    artists: (e.artists ?? []).map((a) => a.name).filter(Boolean),
    durationMs: e.duration ?? 0,
  };
}

export async function spotifyCollection(link) {
  const e = await entity(link);
  const songs = (e.trackList ?? [])
    .filter((t) => !t.uri || t.uri.startsWith('spotify:track:') || t.uri.startsWith('spotify:local:'))
    .filter((t) => t.title)
    .map((t) => ({ title: t.title, artists: splitArtists(t.subtitle), durationMs: t.duration ?? 0 }));
  const covers = e.coverArt?.sources ?? e.visualIdentity?.image ?? [];
  const cover = [...covers].sort((a, b) => (b.maxWidth ?? b.width ?? 0) - (a.maxWidth ?? a.width ?? 0))[0]?.url;
  return {
    kind: link.kind,
    name: e.name || e.title || 'Spotify',
    owner: e.subtitle || null,
    coverUrl: cover ?? null,
    songs,
    mayBeTruncated: link.kind === 'playlist' && songs.length >= PREVIEW_LIMIT,
  };
}
