import { ArtifactStore } from '@railgun-community/wallet';

const DB_NAME = 'railgun-artifacts';
const STORE = 'files';

let opened;

function database() {
  opened ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return opened;
}

async function run(mode, action) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const request = action(db.transaction(STORE, mode).objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/** Circuit artifacts, downloaded and hash-checked by the SDK, kept in their own IndexedDB. */
export const artifactStore = new ArtifactStore(
  async (path) => {
    const item = await run('readonly', (store) => store.get(path));
    if (item === undefined) throw new Error(`missing artifact ${path}`);
    return typeof item === 'string' ? item : Buffer.from(item);
  },
  (_dir, path, item) =>
    run('readwrite', (store) => store.put(typeof item === 'string' ? item : new Uint8Array(item), path)),
  async (path) => (await run('readonly', (store) => store.count(path))) > 0,
);
