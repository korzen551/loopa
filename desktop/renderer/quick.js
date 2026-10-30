// Okienko "Zapisz w Loopa" po skopiowaniu linku - odpowiednik polarkusza
// "Udostepnij" z telefonu. Samo znika, gdy nikt go nie dotknie.

const api = window.loopa;
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

let current = null;
let hideTimer = null;

function scheduleHide(ms = 25000) {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => api.quickClose(), ms);
}

// Najechanie mysza albo klikniecie wstrzymuje znikanie.
document.addEventListener('pointerenter', () => clearTimeout(hideTimer));
document.addEventListener('pointerleave', () => scheduleHide(8000));

async function show(text) {
  current = { text, preview: null };
  scheduleHide();
  $('#title').textContent = 'Wczytuję…';
  $('#sub').textContent = '';
  $('#thumb').style.backgroundImage = '';
  $('#list').innerHTML = '';
  $('#save').disabled = true;
  $('#save').textContent = 'Zapisz';

  const [preview, lib] = await Promise.all([api.previewLink(text).catch(() => null), api.library()]);
  if (current?.text !== text) return;
  current.preview = preview;

  if (!preview || preview.type === 'spotify') {
    $('#title').textContent = 'Link ze Spotify';
    $('#sub').textContent = 'Utwór, album albo playlista — otworzę go w Loopa i dopasuję na YouTube.';
    $('#save').textContent = 'Otwórz w Loopa';
    $('#save').disabled = false;
    return;
  }

  $('#title').textContent = preview.title;
  $('#sub').textContent = [preview.source === 'youtube' ? 'YouTube' : 'TikTok', preview.author, preview.exists ? 'już w bibliotece' : null]
    .filter(Boolean)
    .join(' · ');
  if (preview.thumbnailUrl) $('#thumb').style.backgroundImage = `url('${preview.thumbnailUrl}')`;
  $('#list').innerHTML = lib.playlists.length
    ? lib.playlists
        .map((p) => {
          const already = preview.inPlaylists.includes(p.id);
          return `<label class="check"><input type="checkbox" data-id="${p.id}" ${already ? 'checked disabled' : ''}><span class="grow">${esc(p.name)}${already ? ' <small>już tam jest</small>' : ''}</span></label>`;
        })
        .join('')
    : '<div class="note">Bez playlisty film trafi do „Wszystkie filmy”.</div>';
  $('#save').disabled = false;
}

$('#save').addEventListener('click', async () => {
  if (!current) return;
  clearTimeout(hideTimer);
  if (!current.preview || current.preview.type === 'spotify') {
    api.quickOpen(current.text);
    return;
  }
  $('#save').disabled = true;
  $('#save').textContent = 'Zapisuję…';
  try {
    const result = await api.addLink(current.text);
    if (result.type === 'track') {
      const ids = [...document.querySelectorAll('[data-id]:checked:not(:disabled)')].map((c) => c.dataset.id);
      for (const id of ids) await api.addToPlaylist(id, [result.track.id]);
      $('#save').textContent = 'Zapisano ✓';
    } else {
      $('#save').textContent = 'Nie udało się';
    }
  } catch {
    $('#save').textContent = 'Nie udało się';
  }
  scheduleHide(1200);
});

$('#skip').addEventListener('click', () => api.quickClose());
$('#close').addEventListener('click', () => api.quickClose());

api.onQuickLink(show);
