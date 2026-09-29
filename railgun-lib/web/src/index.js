// The host hands the page one MessagePort, then sends it JSON requests {id, method, params} and
// reads back {id, result} or {id, error}, plus events {event, data} (scan progress, logs).
import { reverseCost, prepareReverse } from './reverse.js';
import * as transact from './transact.js';
import * as wallet from './wallet.js';

const INIT = 'zapp-railgun-init';
const handlers = {
  reverseCost,
  prepareReverse,
  start: wallet.start,
  openWallet: wallet.openWallet,
  refresh: wallet.refresh,
  balances: wallet.balances,
  setGasAccount: transact.setGasAccount,
  gasAccount: transact.gasAccount,
  shield: transact.shield,
  transfer: transact.transfer,
  unshield: transact.unshield,
};

let port;

function emit(event, data) {
  port.postMessage(JSON.stringify({ event, data }));
}

async function dispatch(raw) {
  let request;
  try {
    request = JSON.parse(raw);
  } catch {
    return;
  }
  const { id, method, params } = request;
  try {
    if (!Object.hasOwn(handlers, method)) throw new Error(`unknown method ${method}`);
    const result = await handlers[method](params ?? {}, emit);
    port.postMessage(JSON.stringify({ id, result: result ?? null }));
  } catch (error) {
    port.postMessage(JSON.stringify({ id, error: error?.message ?? String(error) }));
  }
}

// The page loads nothing but its own files, so the only other window that can post here is the
// host, and only its first port is taken.
window.addEventListener('message', (event) => {
  if (port !== undefined || event.data !== INIT || event.ports.length !== 1) return;
  [port] = event.ports;
  port.onmessage = ({ data }) => dispatch(data);
  emit('ready', null);
});
