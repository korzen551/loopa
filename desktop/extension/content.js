// Dodaje "Loopa" do menu "Udostepnij na" TikToka, obok "Copy" i "WhatsApp".
//
// Klikniecie uzywa wlasnego "Copy" TikToka - on wie najlepiej, jaki link ma
// ten film - a potem prosi Loopa, zeby wziela go ze schowka i pokazala
// okienko "Zapisz w Loopa".

(() => {
  const ICON = chrome.runtime.getURL('icon128.png');

  function toast(text) {
    const el = document.createElement('div');
    el.textContent = text;
    Object.assign(el.style, {
      position: 'fixed', left: '50%', bottom: '32px', transform: 'translateX(-50%)', zIndex: 2147483647,
      background: '#f2f2f2', color: '#111', font: '600 14px system-ui, sans-serif', padding: '10px 18px',
      borderRadius: '999px', boxShadow: '0 6px 24px rgba(0,0,0,.35)',
    });
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2600);
  }

  function addButton(group) {
    if (group.querySelector('[data-e2e="share-loopa"]')) return;
    const copy = group.querySelector('[data-e2e="share-copy"]');
    if (!copy) return;

    const loopa = copy.cloneNode(true);
    loopa.setAttribute('data-e2e', 'share-loopa');
    // Rozmiar bierzemy z prawdziwej ikony "Copy" (TikTok ustawia go stylami).
    const copySvg = copy.querySelector('svg');
    const size = copySvg ? `${Math.round(copySvg.getBoundingClientRect().width) || 48}px` : '48px';
    const iconBox = loopa.querySelector('svg')?.parentElement;
    if (iconBox) {
      iconBox.innerHTML = '';
      const img = document.createElement('img');
      img.src = ICON;
      img.alt = '';
      Object.assign(img.style, { width: size, height: size, borderRadius: '50%', display: 'block' });
      iconBox.appendChild(img);
    }
    const label = loopa.querySelector('p');
    if (label) label.textContent = 'Loopa';

    loopa.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      (copy.querySelector('[tabindex]') || copy).click();
      // Chwila na to, zeby TikTok wlozyl link do schowka.
      setTimeout(() => {
        chrome.runtime.sendMessage({ type: 'loopa-share', payload: { fromClipboard: true, page: location.href } }, (res) => {
          toast(res?.ok ? 'Wysłano do Loopa' : 'Loopa nie jest włączona na tym komputerze');
        });
      }, 350);
    }, true);

    copy.parentElement.insertBefore(loopa, copy);
  }

  const scan = () => document.querySelectorAll('[data-e2e="share-group"]').forEach(addButton);
  new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
  scan();
})();
