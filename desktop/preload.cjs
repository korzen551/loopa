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
  openExternal: invoke('open:external'),
  onLibraryChanged: (fn) => ipcRenderer.on('library:changed', () => fn()),
  onSpotifyProgress: (fn) => ipcRenderer.on('spotify:progress', (_e, p) => fn(p)),
});
