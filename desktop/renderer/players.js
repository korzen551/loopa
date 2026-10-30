// Odtwarzacze. Wspolny interfejs:
//   load(...), play(), pause(), toggle(), seek(ms), setRate(x), setVolume(v, muted),
//   position(), duration(), playing(), destroy()
// i zdarzenia przez callbacki: onReady, onCycle(liczba), onEnded, onError(msg), onState.

const MIN_SEGMENT = 0.6;

/** Punkty petli przyciete do filmu; wszystko w sekundach. */
export function segment(loop, durationSec) {
  const dur = durationSec || 0;
  let end = loop.endMs > 0 ? Math.min(loop.endMs / 1000, dur || Infinity) : dur;
  const ceiling = Math.max(0, (dur || end) - MIN_SEGMENT);
  let start = Math.min(Math.max(0, loop.startMs / 1000), ceiling);
  let loopStart = Math.min(Math.max(0, loop.loopStartMs / 1000), ceiling);
  if (end - loopStart < MIN_SEGMENT) loopStart = 0;
  if (end - start < MIN_SEGMENT) start = 0;
  if (end - loopStart < MIN_SEGMENT) end = dur;
  return { start, loopStart, end };
}

/**
 * Pliki z TikToka. Petla bez przerwy na dwoch elementach <video>: gdy jeden gra,
 * drugi czeka juz ustawiony na punkcie powrotu z gotowa pierwsza klatka.
 * Tuz przed koncem segmentu drugi rusza, pierwszy znika i sam ustawia sie na
 * kolejne powtorzenie. Przewiniecie (zmiana currentTime) zawsze dawalo dziure.
 */
export class NativePlayer {
  constructor(host, events) {
    this.events = events;
    this.cycles = 0;
    this.loop = null;
    this.els = [0, 1].map(() => {
      const v = document.createElement('video');
      v.className = 'media';
      v.preload = 'auto';
      v.playsInline = true;
      v.disableRemotePlayback = true;
      host.appendChild(v);
      return v;
    });
    [this.active, this.standby] = this.els;
    this.standby.classList.add('hidden');
    this.raf = 0;
    this.destroyed = false;
  }

  load(url, loop, autoplay) {
    this.loop = loop;
    let ready = 0;
    for (const v of this.els) {
      v.src = url;
      v.addEventListener('loadedmetadata', () => {
        const seg = this.seg();
        v.currentTime = v === this.active ? seg.start : seg.loopStart;
        if (++ready === 2) {
          this.events.onReady?.(this.duration());
          if (autoplay) this.play();
        }
      }, { once: true });
      v.addEventListener('error', () => this.events.onError?.('Nie udało się odtworzyć tego pliku.'), { once: true });
    }
    for (const v of this.els) {
      v.addEventListener('play', () => this.events.onState?.());
      v.addEventListener('pause', () => this.events.onState?.());
      v.addEventListener('ended', () => v === this.active && this.check());
    }
    // Klatki obrazu (rAF) daja najdokladniejszy moment przejscia, ale Windows je
    // wstrzymuje, gdy okno jest zminimalizowane - wtedy petla stawala na koncu.
    // Zegar dziala zawsze, wiec petla leci dalej takze w tle.
    const tick = () => {
      if (this.destroyed) return;
      this.check();
      this.raf = requestAnimationFrame(tick);
    };
    this.raf = requestAnimationFrame(tick);
    this.timer = setInterval(() => this.check(), 20);
  }

  seg() {
    return segment(this.loop, this.active.duration);
  }

  setLoop(loop, restart) {
    this.loop = loop;
    const seg = this.seg();
    this.standby.currentTime = seg.loopStart;
    if (restart) {
      this.active.currentTime = seg.start;
      this.cycles = 0;
      this.play();
    }
  }

  check() {
    const a = this.active;
    if (this.stopped) return;
    // Po naturalnym koncu element jest "wstrzymany" - to wlasnie moment na petle.
    if ((a.paused && !a.ended) || !a.duration) return;
    const seg = this.seg();
    // Rezerwa ~2 klatek: rAF przychodzi co ~16 ms, a koniec ma byc koncem.
    if (a.currentTime < seg.end - 0.035 && !a.ended) return;
    this.cycles += 1;
    if (this.events.onCycle?.(this.cycles) === 'stop') {
      this.stopped = true;
      a.pause();
      this.events.onEnded?.();
      return;
    }
    this.swap(seg);
  }

  swap(seg) {
    const next = this.standby;
    if (next.readyState < 3) {
      // Zapas jeszcze niegotowy (np. dopiero co przewinieto) - zwykle przewiniecie.
      this.active.currentTime = seg.loopStart;
      this.active.play().catch(() => {});
      return;
    }
    next.playbackRate = this.active.playbackRate;
    next.volume = this.active.volume;
    next.muted = this.active.muted;
    next.play();
    next.classList.remove('hidden');
    this.active.pause();
    this.active.classList.add('hidden');
    this.standby = this.active;
    this.active = next;
    this.standby.currentTime = seg.loopStart;
  }

  play() {
    this.stopped = false;
    this.active.play().catch(() => {});
  }
  pause() {
    this.active.pause();
  }
  toggle() {
    this.playing() ? this.pause() : this.play();
  }
  playing() {
    return !this.active.paused;
  }
  seek(ms) {
    this.active.currentTime = ms / 1000;
  }
  setRate(x) {
    for (const v of this.els) v.playbackRate = x;
  }
  setVolume(volume, muted) {
    for (const v of this.els) {
      v.volume = volume;
      v.muted = muted;
    }
  }
  position() {
    return (this.active.currentTime || 0) * 1000;
  }
  duration() {
    return (this.active.duration || 0) * 1000;
  }
  destroy() {
    this.destroyed = true;
    cancelAnimationFrame(this.raf);
    clearInterval(this.timer);
    for (const v of this.els) {
      v.pause();
      v.removeAttribute('src');
      v.load();
      v.remove();
    }
  }
}

// ---------- YouTube ----------

let ytApi;
function youtubeApi() {
  ytApi ??= new Promise((resolve) => {
    window.onYouTubeIframeAPIReady = () => resolve(window.YT);
    const s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    document.head.appendChild(s);
  });
  return ytApi;
}

const YT_ERRORS = {
  2: 'YouTube nie rozpoznał tego filmu.',
  5: 'Ten film nie może grać w osadzonym odtwarzaczu.',
  100: 'Ten film został usunięty albo jest prywatny.',
  101: 'Właściciel nie pozwala odtwarzać tego filmu poza YouTube.',
  150: 'Właściciel nie pozwala odtwarzać tego filmu poza YouTube.',
  153: 'YouTube odrzucił odtwarzacz w oknie apki.',
};

/**
 * Oficjalny odtwarzacz YouTube'a. Petla przewija na punkt powrotu - przy
 * YouTube'ie inaczej sie nie da, wiec na samym przejsciu moze byc chwila ciszy.
 */
export class YouTubePlayer {
  constructor(host, events) {
    this.events = events;
    this.cycles = 0;
    this.host = document.createElement('div');
    this.host.className = 'media yt';
    host.appendChild(this.host);
    this.timer = 0;
    this.rate = 1;
  }

  async load(videoId, loop, autoplay) {
    this.loop = loop;
    const YT = await youtubeApi();
    if (this.destroyed) return;
    const slot = document.createElement('div');
    this.host.appendChild(slot);
    this.player = new YT.Player(slot, {
      videoId,
      width: '100%',
      height: '100%',
      playerVars: {
        autoplay: autoplay ? 1 : 0,
        controls: 0,
        disablekb: 1,
        fs: 0,
        rel: 0,
        playsinline: 1,
        iv_load_policy: 3,
        start: Math.floor((loop.startMs || 0) / 1000),
        origin: location.origin,
      },
      events: {
        onReady: () => {
          this.ready = true;
          this.applyVolume();
          this.player.setPlaybackRate(this.rate);
          const seg = this.seg();
          if (seg.start > 0) this.player.seekTo(seg.start, true);
          if (autoplay) this.player.playVideo();
          this.events.onReady?.(this.duration());
        },
        onStateChange: (e) => {
          this.events.onState?.();
          if (e.data === YT.PlayerState.ENDED) this.cycle();
        },
        onError: (e) => this.events.onError?.(YT_ERRORS[e.data] ?? `YouTube nie odtworzył tego filmu (kod ${e.data}).`),
      },
    });
    this.timer = setInterval(() => this.check(), 50);
  }

  seg() {
    return segment(this.loop, this.ready ? this.player.getDuration() : 0);
  }

  setLoop(loop, restart) {
    this.loop = loop;
    if (restart && this.ready) {
      this.cycles = 0;
      this.player.seekTo(this.seg().start, true);
      this.player.playVideo();
    }
  }

  check() {
    if (!this.ready || !this.playing()) return;
    const seg = this.seg();
    if (seg.end && this.player.getCurrentTime() >= seg.end - 0.12) this.cycle();
  }

  cycle() {
    this.cycles += 1;
    if (this.events.onCycle?.(this.cycles) === 'stop') {
      this.player.pauseVideo();
      this.events.onEnded?.();
      return;
    }
    this.player.seekTo(this.seg().loopStart, true);
    this.player.playVideo();
  }

  play() {
    this.ready && this.player.playVideo();
  }
  pause() {
    this.ready && this.player.pauseVideo();
  }
  toggle() {
    this.playing() ? this.pause() : this.play();
  }
  playing() {
    return this.ready && this.player.getPlayerState() === 1;
  }
  seek(ms) {
    this.ready && this.player.seekTo(ms / 1000, true);
  }
  setRate(x) {
    this.rate = x;
    this.ready && this.player.setPlaybackRate(x);
  }
  setVolume(volume, muted) {
    this.volume = volume;
    this.muted = muted;
    this.applyVolume();
  }
  applyVolume() {
    if (!this.ready || this.volume == null) return;
    this.player.setVolume(Math.round(this.volume * 100));
    this.muted ? this.player.mute() : this.player.unMute();
  }
  position() {
    return this.ready ? this.player.getCurrentTime() * 1000 : 0;
  }
  duration() {
    return this.ready ? this.player.getDuration() * 1000 : 0;
  }
  destroy() {
    this.destroyed = true;
    clearInterval(this.timer);
    try {
      this.player?.destroy();
    } catch {}
    this.host.remove();
  }
}

// ---------- zdjecia ----------

/** Karuzela zdjec jak w TikToku: same przechodza co 3 s, strzalki i kropki. */
export class PhotoCarousel {
  constructor(host, images, isPlaying) {
    this.images = images;
    this.index = 0;
    this.isPlaying = isPlaying;
    this.el = document.createElement('div');
    this.el.className = 'carousel';
    this.el.innerHTML = `
      <div class="slides">${images.map((src) => `<img src="${src}" alt="" draggable="false">`).join('')}</div>
      ${images.length > 1 ? `
      <div class="carousel-nav">
        <button class="cnav prev" aria-label="Poprzednie zdjęcie">‹</button>
        <div class="dots">${images.length <= 12 ? images.map(() => '<i></i>').join('') : '<span class="count"></span>'}</div>
        <button class="cnav next" aria-label="Następne zdjęcie">›</button>
      </div>` : ''}`;
    host.appendChild(this.el);
    this.el.querySelector('.prev')?.addEventListener('click', (e) => { e.stopPropagation(); this.go(-1); });
    this.el.querySelector('.next')?.addEventListener('click', (e) => { e.stopPropagation(); this.go(1); });
    this.render();
    this.schedule();
  }

  go(delta) {
    const n = this.images.length;
    this.index = (this.index + delta + n) % n;
    this.render();
    this.schedule();
  }

  schedule() {
    clearTimeout(this.timer);
    if (this.images.length < 2) return;
    this.timer = setTimeout(() => {
      if (this.isPlaying()) this.go(1);
      else this.schedule();
    }, 3000);
  }

  render() {
    this.el.querySelector('.slides').style.transform = `translateX(-${this.index * 100}%)`;
    this.el.querySelectorAll('.dots i').forEach((d, i) => d.classList.toggle('on', i === this.index));
    const count = this.el.querySelector('.count');
    if (count) count.textContent = `${this.index + 1} / ${this.images.length}`;
  }

  destroy() {
    clearTimeout(this.timer);
    this.el.remove();
  }
}
