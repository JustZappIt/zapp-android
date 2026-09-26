import { RailgunWallet } from '@railgun-community/engine';
import { MerkletreeScanStatus, NETWORK_CONFIG, NetworkName, TXIDVersion } from '@railgun-community/shared-models';
import {
  createRailgunWallet,
  generatePOIsForWallet,
  getProver,
  getSerializedERC20Balances,
  loadProvider,
  loadWalletByID,
  refreshBalances,
  refreshReceivePOIsForWallet,
  setLoggers,
  setOnTXIDMerkletreeScanCallback,
  setOnUTXOMerkletreeScanCallback,
  startRailgunEngine,
  walletForID,
} from '@railgun-community/wallet';
import LevelJS from 'level-js';
import { groth16 } from 'snarkjs';
import { artifactStore } from './artifacts.js';

export const TXID_VERSION = TXIDVersion.V2_PoseidonMerkle;
const NETWORKS = { sepolia: NetworkName.EthereumSepolia, mainnet: NetworkName.Ethereum };
const WALLET_SOURCE = 'zapp';
const POLLING_INTERVAL_MS = 15_000;
const NOTES_SCAN_ATTEMPTS = 20;
const NOTES_SCAN_RETRY_MS = 3_000;
// A notes scan reports 3% as it starts (1% first on a full rescan); one the engine skipped reports nothing.
const NOTES_SCAN_START = 0.03;

let network;
let wallet;
let notesScan;
let notesScans = Promise.resolve();

const chain = () => NETWORK_CONFIG[network].chain;

/** The open wallet, for the calls that spend from it. */
export function session() {
  if (wallet === undefined) throw new Error('no wallet is open');
  return { network, ...wallet };
}

export async function start({ network: name, rpcUrls, poiNodeUrls, debug = false }, emit) {
  if (network !== undefined) throw new Error('the engine is already running');
  const networkName = NETWORKS[name];
  if (networkName === undefined) throw new Error(`unknown network ${name}`);
  if (!rpcUrls?.length) throw new Error('no RPC URLs');

  if (debug) setLoggers((message) => emit('log', String(message)), (error) => emit('log', String(error?.stack ?? error)));
  await startRailgunEngine(WALLET_SOURCE, new LevelJS(`railgun-${name}`), debug, artifactStore, false, false, poiNodeUrls);
  getProver().setSnarkJSGroth16(groth16);
  setOnUTXOMerkletreeScanCallback(({ scanStatus, progress }) => {
    if (notesScan !== undefined) {
      if (scanStatus === MerkletreeScanStatus.Updated && progress <= NOTES_SCAN_START) notesScan.started = true;
      if (scanStatus === MerkletreeScanStatus.Incomplete) notesScan.incomplete = true;
    }
    emit('scan', { tree: 'utxo', status: scanStatus, progress });
  });
  setOnTXIDMerkletreeScanCallback(({ scanStatus, progress }) => emit('scan', { tree: 'txid', status: scanStatus, progress }));
  network = networkName;
  const providers = rpcUrls.map((url, index) => ({ provider: url, priority: index + 1, weight: 2 }));
  await loadProvider({ chainId: chain().id, providers }, network, POLLING_INTERVAL_MS);
}

/**
 * Opens the wallet `mnemonic` derives at index 0, the one Railway opens too. Its id is a hash of
 * the seed, so a stored wallet loads; a missing one, or one this key can't decrypt, is created.
 */
export async function openWallet({ encryptionKey, mnemonic, creationBlock }) {
  if (network === undefined) throw new Error('the engine is not running');
  const id = RailgunWallet.generateID(mnemonic, 0);
  let info;
  try {
    info = await loadWalletByID(encryptionKey, id, false);
  } catch {
    const creationBlocks = creationBlock === undefined ? undefined : { [network]: creationBlock };
    info = await createRailgunWallet(encryptionKey, mnemonic, creationBlocks, 0);
  }
  wallet = { walletId: info.id, encryptionKey, address: info.railgunAddress };
  return { address: info.railgunAddress };
}

/**
 * Scans the pool up to the chain's tip, asks the screening nodes about received notes, and proves
 * the spent ones innocent: a sender submits those proofs, and until then the change is unspendable.
 */
export async function refresh() {
  const { walletId } = session();
  await scanNotes(walletId);
  await refreshReceivePOIsForWallet(TXID_VERSION, network, walletId);
  await generatePOIsForWallet(network, walletId);
  return balances();
}

/** One notes scan at a time, so each can tell whether the engine ran it. */
function scanNotes(walletId) {
  const scan = notesScans.then(() => scanNotesNow(walletId));
  notesScans = scan.catch(() => {});
  return scan;
}

/**
 * The engine skips a notes scan without a word while another one runs or before the network has
 * loaded, which leaves the balances stale; so try until one starts, and fail on one that breaks off.
 */
async function scanNotesNow(walletId) {
  for (let attempt = 0; attempt < NOTES_SCAN_ATTEMPTS; attempt += 1) {
    const scan = { started: false, incomplete: false };
    notesScan = scan;
    try {
      await refreshBalances(chain(), [walletId]);
    } finally {
      notesScan = undefined;
    }
    if (scan.incomplete) throw new Error('the notes scan broke off, so the balances may be stale');
    if (scan.started) return;
    await new Promise((resolve) => setTimeout(resolve, NOTES_SCAN_RETRY_MS));
  }
  throw new Error('no notes scan started for a minute: one may be stuck, restart the app');
}

/** Balances by screening status: `Spendable`, `ShieldPending`, `ShieldBlocked`, … */
export async function balances() {
  const byBucket = await walletForID(session().walletId).getTokenBalancesByBucket(TXID_VERSION, chain());
  return Object.fromEntries(
    Object.entries(byBucket).map(([bucket, tokens]) => [
      bucket,
      getSerializedERC20Balances(tokens).map(({ tokenAddress, amount }) => ({ token: tokenAddress, amount: amount.toString() })),
    ]),
  );
}
