// Zapis plikow, ktory sprawdza sam siebie.
//
// Loopa potrafila przez kilka dni "zapisywac" biblioteke, a plik na dysku stal
// w miejscu - bez zadnego bledu. Teraz kazdy zapis jest odczytywany z powrotem;
// gdy na dysku jest co innego, zapis idzie jeszcze raz inna droga, a problem
// trafia do loopa-errors.log obok biblioteki.

import fs from 'node:fs';
import path from 'node:path';

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

export function logError(file, message) {
  try {
    const log = path.join(path.dirname(file), 'loopa-errors.log');
    const stat = fs.statSync(log, { throwIfNoEntry: false });
    if (stat && stat.size > 512 * 1024) fs.rmSync(log, { force: true }); // nie rosnie bez konca
    fs.appendFileSync(log, `${new Date().toISOString()} ${path.basename(file)}: ${message}\n`);
  } catch {}
}

const onDisk = (file, text) => {
  try {
    return fs.readFileSync(file, 'utf8') === text;
  } catch {
    return false;
  }
};

/**
 * Zapis przez plik tymczasowy (przerwany zapis nie psuje pliku), potem
 * sprawdzenie. Gdy sie nie zgadza - zapis wprost. Rzuca dopiero, gdy nic nie dziala.
 */
export function writeFileChecked(file, text) {
  const tmp = `${file}.tmp`;
  let lastError = null;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      fs.writeFileSync(tmp, text);
      fs.renameSync(tmp, file);
      if (onDisk(file, text)) return;
      lastError = new Error('po zapisie na dysku jest inna tresc');
    } catch (e) {
      lastError = e;
    }
    // Windows potrafi chwilowo zablokowac plik (antywirus, kopia zapasowa).
    sleep(80);
  }
  logError(file, `zapis przez plik tymczasowy nie wyszedl (${lastError?.message}) - zapisuje wprost`);
  try {
    fs.writeFileSync(file, text);
    fs.rmSync(tmp, { force: true });
    if (onDisk(file, text)) return;
    lastError = new Error('po zapisie wprost na dysku dalej jest inna tresc');
  } catch (e) {
    lastError = e;
  }
  logError(file, `ZAPIS NIEUDANY: ${lastError?.message}`);
  throw lastError;
}

export { onDisk };
