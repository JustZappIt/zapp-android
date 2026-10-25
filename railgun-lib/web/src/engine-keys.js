// Railgun's engine keeps its key agreement behind its package's exports map, so this takes it from the file the
// engine's own scan runs, by path, as zecSwap's checks of its notes do (crates/zecswap-railgun/engine/engine.cjs).
export { getSharedSymmetricKey } from '../node_modules/@railgun-community/engine/dist/utils/keys-utils.js';
