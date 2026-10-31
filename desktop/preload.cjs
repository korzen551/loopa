// Most miedzy oknem a procesem glownym - okno nie ma dostepu do Node.
const { contextBridge, ipcRenderer } = require('electron');

const invoke = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld('loopa', {
  library: invoke('library:get'),
  addLink: invoke('link:add'),
  spotifySong: invoke('spotify:song'),
  spotifyCollection: invoke('spotify:collection'),
  spotifySave: invoke('spotify:save'),
  prepare: invoke('media:prepare'),
  retry: invoke('media:retry'),
  updateTrack: invoke('track:update'),
  deleteTrack: invoke('track:delete'),
  createPlaylist: invoke('playlist:create'),
  updatePlaylist: invoke('playlist:update'),
  deletePlaylist: invoke('playlist:delete'),
  addToPlaylist: invoke('playlist:add'),
  removeFromPlaylist: invoke('playlist:remove'),
  movePlaylistItem: invoke('playlist:move'),
  openExternal: invoke('open:external'),
  syncStatus: invoke('sync:status'),
  syncConnect: invoke('sync:connect'),
  syncDisconnect: invoke('sync:disconnect'),
  syncNow: invoke('sync:now'),
  previewLink: invoke('link:preview'),
  quickClose: invoke('quick:close'),
  quickOpen: invoke('quick:open'),
  openExtensionFolder: invoke('extension:open'),
  extensionPath: invoke('extension:path'),
  onLibraryChanged: (fn) => ipcRenderer.on('library:changed', () => fn()),
  onSpotifyProgress: (fn) => ipcRenderer.on('spotify:progress', (_e, p) => fn(p)),
  onSyncStatus: (fn) => ipcRenderer.on('sync:status', (_e, s) => fn(s)),
  onQuickLink: (fn) => ipcRenderer.on('quick:link', (_e, text) => fn(text)),
  onLinkOpen: (fn) => ipcRenderer.on('link:open', (_e, text) => fn(text)),
  onPause: (fn) => ipcRenderer.on('player:pause', () => fn()),
});
