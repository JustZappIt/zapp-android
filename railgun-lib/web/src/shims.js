// Injected into every module by esbuild: the Node globals Railgun's engine and its deps expect.
import { Buffer } from 'buffer';
import process from 'process';

// The shim's nextTick waits on setTimeout, which the browser clamps; streams in the AES and
// LevelDB paths tick often enough during a sync for that to add up.
process.nextTick = (fn, ...args) => queueMicrotask(() => fn(...args));

globalThis.Buffer ??= Buffer;
globalThis.process ??= process;

export { Buffer, process };
