// Dopasowanie utworu ze Spotify do filmu na YouTube - ten sam algorytm co
// YouTubeMatcher.kt w apce na Androida. Najmocniejszy sygnal to dlugosc
// nagrania co do sekundy, do tego tytul, wykonawca, pierwszenstwo kanalu
// wykonawcy i kara za inne wersje (live, cover, sped up...).

import { searchYouTube } from './youtube.mjs';

const ACCEPTABLE = 0.62;
const PARALLEL = 4;

const normalize = (text) =>
  text
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .replace(/ł/g, 'l')
    .replace(/&/g, ' and ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();

const tokens = (text) => new Set(normalize(text).split(' ').filter(Boolean));

const VARIANT_WORDS = [
  'live', 'cover', 'karaoke', 'instrumental', 'remix', 'sped up', 'speed up', 'slowed',
  'reverb', 'nightcore', '8d', 'acoustic', 'piano', 'reaction', 'tutorial', 'lesson',
  'mashup', 'parody', 'bass boosted', 'hour', 'hours', 'loop', 'extended', 'concert',
  'tribute', 'fan made', 'fanmade', 'chipmunk', 'reversed', 'lyrics translation',
  'clean', 'censored', 'cenzura', 'na zywo', 'koncert',
].map((w) => ` ${normalize(w)} `);

const SUFFIX = /\s+-\s+(.*)$/;
const FEATURING = /[([]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]/gi;

const variantsIn = (text) => {
  const padded = ` ${normalize(text)} `;
  return new Set(VARIANT_WORDS.filter((w) => padded.includes(w)));
};

export function coreTitle(title) {
  const withoutFeat = title.replace(FEATURING, ' ');
  const core = withoutFeat.replace(SUFFIX, '').trim();
  return core || withoutFeat.trim();
}

export function queryFor(song) {
  const artists = song.artists.slice(0, 2).join(' ');
  const suffix = song.title.replace(FEATURING, ' ').match(SUFFIX)?.[1] ?? '';
  const version = variantsIn(suffix).size ? suffix : '';
  return `${artists} ${coreTitle(song.title)} ${version}`.replace(/\s+/g, ' ').trim();
}

function rate(song, c, rank) {
  const candidateTokens = tokens(c.title);
  const everywhere = new Set([...candidateTokens, ...tokens(c.channel)]);
  let core = tokens(coreTitle(song.title));
  if (!core.size) core = tokens(song.title);
  if (!core.size) return null;
  const titleScore = [...core].filter((t) => candidateTokens.has(t)).length / core.size;

  const hits = song.artists.map((a) => {
    const parts = [...tokens(a)];
    return parts.length > 0 && parts.every((p) => everywhere.has(p));
  });
  const artistScore = !hits.length
    ? 0.5
    : hits.length === 1
      ? (hits[0] ? 1 : 0)
      : (hits[0] ? 0.7 : 0) + (0.3 * hits.slice(1).filter(Boolean).length) / (hits.length - 1);

  let durationScore = 0.5;
  if (song.durationMs > 0) {
    const d = Math.abs(c.durationSec - song.durationMs / 1000);
    durationScore = d <= 2.5 ? 1 : d <= 4.5 ? 0.9 : d <= 8 ? 0.7 : d <= 15 ? 0.45 : d <= 30 ? 0.2 : d <= 60 ? 0.05 : 0;
  }

  if (titleScore < 0.5) return null;
  if (artistScore === 0 && !(titleScore === 1 && durationScore >= 0.9)) return null;

  const sv = variantsIn(song.title);
  const cv = variantsIn(c.title);
  const mismatched = [...cv].filter((v) => !sv.has(v)).length + [...sv].filter((v) => !cv.has(v)).length;
  const penalty = Math.min(mismatched * 0.3, 0.6);

  const channel = normalize(c.channel);
  const primary = normalize(song.artists[0] ?? '').replace(/ /g, '');
  const artistChannel = primary.length >= 3 && channel.replace(/ /g, '').includes(primary);
  const topic = channel.endsWith(' topic');
  const vevo = channel.includes('vevo');
  let bonus = 0;
  if (artistChannel) bonus += 0.07;
  if (topic) bonus += 0.05;
  if (vevo) bonus += 0.04;
  if ((artistChannel || topic || vevo || c.verified) && ` ${normalize(c.title)} `.includes(' official ')) bonus += 0.03;
  if (c.verified) bonus += 0.01;
  bonus += [0.03, 0.02, 0.01][rank] ?? 0;

  const total = 0.4 * titleScore + 0.25 * artistScore + 0.35 * durationScore + bonus - penalty;
  return total >= ACCEPTABLE ? total : null;
}

export async function findOnYouTube(song) {
  const candidates = await searchYouTube(queryFor(song));
  let best = null;
  candidates.forEach((c, rank) => {
    const score = rate(song, c, rank);
    if (score != null && (!best || score > best.score)) best = { ...c, score };
  });
  return best;
}

/** Cala lista, kilka utworow naraz - wiecej to proszenie sie o blokade. */
export async function findAll(songs, onEach) {
  const results = new Array(songs.length).fill(null);
  let next = 0;
  async function worker() {
    while (next < songs.length) {
      const i = next++;
      let match = null;
      for (let attempt = 0; attempt < 2 && !match; attempt++) {
        try {
          match = await findOnYouTube(songs[i]);
          break;
        } catch {
          /* jedna ponowna proba na chwilowy blad sieci */
        }
      }
      results[i] = match;
      onEach?.(i, match);
    }
  }
  await Promise.all(Array.from({ length: PARALLEL }, worker));
  return results;
}
