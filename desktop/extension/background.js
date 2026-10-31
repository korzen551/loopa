// Wysyla link do Loopa na tym komputerze. Loopa slucha tylko na 127.0.0.1
// i tylko pokazuje okienko "Zapisz w Loopa" - zapis zawsze zatwierdzasz sam.

const LOOPA = 'http://127.0.0.1:47913/share';
const PATTERNS = [
  '*://*.tiktok.com/*',
  '*://*.youtube.com/*',
  '*://youtu.be/*',
  '*://open.spotify.com/*',
  '*://spotify.link/*',
];

async function send(payload) {
  try {
    const res = await fetch(LOOPA, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    return res.ok;
  } catch {
    return false; // Loopa nie jest wlaczona
  }
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: 'loopa-link',
    title: 'Wyślij do Loopa',
    contexts: ['link'],
    targetUrlPatterns: PATTERNS,
  });
  chrome.contextMenus.create({
    id: 'loopa-page',
    title: 'Wyślij tę stronę do Loopa',
    contexts: ['page', 'video'],
    documentUrlPatterns: PATTERNS,
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  send({ url: info.linkUrl || info.pageUrl || tab?.url });
});

chrome.action.onClicked.addListener((tab) => {
  if (tab?.url) send({ url: tab.url });
});

// Przycisk "Loopa" w menu Udostepnij TikToka (content.js).
chrome.runtime.onMessage.addListener((message, _sender, reply) => {
  if (message?.type !== 'loopa-share') return false;
  send(message.payload).then((ok) => reply({ ok }));
  return true;
});
