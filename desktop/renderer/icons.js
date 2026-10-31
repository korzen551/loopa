// Ikony Material Symbols jako sciezki SVG - bez zewnetrznych czcionek.
const P = {
  home: 'M4 21V9l8-6 8 6v12h-6v-7h-4v7z',
  plus: 'M11 19v-6H5v-2h6V5h2v6h6v2h-6v6z',
  repeat: 'M7 22l-4-4 4-4 1.4 1.45L6.85 17H17v-4h2v6H6.85l1.55 1.55zM5 11V5h12.15L15.6 3.45 17 2l4 4-4 4-1.4-1.45L17.15 7H7v4z',
  playlistAdd: 'M3 16v-2h7v2zm0-4v-2h11v2zm0-4V6h11v2zm13 12v-4h-4v-2h4V10h2v4h4v2h-4v4z',
  open: 'M5 21q-.825 0-1.412-.587T3 19V5q0-.825.588-1.412T5 3h7v2H5v14h14v-7h2v7q0 .825-.587 1.413T19 21zm4.7-5.3-1.4-1.4L17.6 5H14V3h7v7h-2V6.4z',
  trash: 'M7 21q-.825 0-1.412-.587T5 19V6H4V4h5V3h6v1h5v2h-1v13q0 .825-.587 1.413T17 21zM17 6H7v13h10zM9 17h2V8H9zm4 0h2V8h-2z',
  up: 'M7.4 15.4 6 14l6-6 6 6-1.4 1.4-4.6-4.6z',
  down: 'M12 15.4 6 9.4 7.4 8l4.6 4.6L16.6 8 18 9.4z',
  play: 'M8 19V5l11 7z',
  volume: 'M14 20.725v-2.05q2.25-.65 3.625-2.5t1.375-4.2t-1.375-4.2T14 5.275v-2.05q3.1.7 5.05 3.138T21 11.975t-1.95 5.613T14 20.725M3 15V9h4l5-5v16l-5-5zm11 1V7.95q1.175.55 1.838 1.65T16.5 12q0 1.275-.663 2.363T14 16',
  mute: 'm19.8 22.6-3.025-3.025q-.625.4-1.325.688t-1.45.462v-2.05q.35-.125.688-.25t.637-.3L12 14.8V20l-5-5H3V9h3.2L1.4 4.2l1.4-1.4 18.4 18.4zm-.2-5.8-1.45-1.45q.425-.775.638-1.625t.212-1.75q0-2.35-1.375-4.2T14 5.275v-2.05q3.1.7 5.05 3.138T21 11.975q0 1.325-.363 2.55T19.6 16.8m-3.35-3.35L14 11.2V7.95q1.175.55 1.838 1.65T16.5 12q0 .375-.062.738t-.188.712M12 9.2 9.4 6.6 12 4z',
  edit: 'M5 19h1.425L16.2 9.225 14.775 7.8 5 17.575zm-2 2v-4.25L16.2 3.575q.3-.275.663-.425t.762-.15t.775.15t.65.45L20.425 5q.3.275.437.65T21 6.4q0 .4-.138.763t-.437.662L7.25 21zM15.475 8.525l-.7-.725L16.2 9.225z',
  feed: 'M6 22q-.825 0-1.412-.587T4 20V4q0-.825.588-1.412T6 2h12q.825 0 1.413.588T20 4v16q0 .825-.587 1.413T18 22zm0-2h12V4H6zm4-4.5 6-3.5-6-3.5z',
  list: 'M3 18v-2h4v2zm0-5v-2h4v2zm0-5V6h4v2zm6 10v-2h12v2zm0-5v-2h12v2zm0-5V6h12v2z',
};

export function icon(name) {
  return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${P[name] ?? ''}"/></svg>`;
}

/** Wstawia ikony we wszystkie elementy z atrybutem data-icon. */
export function hydrateIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach((el) => {
    if (!el.dataset.iconDone) {
      el.insertAdjacentHTML('afterbegin', icon(el.dataset.icon));
      el.dataset.iconDone = '1';
    }
  });
}
