import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { test } from 'node:test';
import { newDepositNote, openDepositNote } from '../src/deposit-note.js';

const require = createRequire(import.meta.url);
// The engine's key derivation, which its package doesn't export, by path as check.mjs loads it.
const dist = path.dirname(require.resolve('@railgun-community/engine'));
const load = (module) => require(path.join(dist, module));
const { deriveNodes, WalletNode } = load('key-derivation/wallet-node');
const { initPoseidonPromise } = load('utils/poseidon');
const { initCurve25519Promise } = load('utils/scalar-multiply');

const ALICE = 'test test test test test test test test test test test junk';
const BOB = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
const WORD = /^0x[0-9a-f]{64}$/;

/** Wallet 0's keys as the engine derives them from `mnemonic`, as openWallet makes the wallet. */
async function wallet(mnemonic) {
  await Promise.all([initPoseidonPromise, initCurve25519Promise]);
  const nodes = deriveNodes(mnemonic, 0);
  const viewing = await nodes.viewing.getViewingKeyPair();
  const nullifyingKey = await nodes.viewing.getNullifyingKey();
  const masterPublicKey = WalletNode.getMasterPublicKey(nodes.spending.getSpendingKeyPair().pubkey, nullifyingKey);
  return { masterPublicKey, viewingPublicKey: viewing.pubkey, viewingPrivateKey: viewing.privateKey };
}

// `word` with its hex digit at `at` changed, the last by default.
const altered = (word, at = word.length - 1) => `${word.slice(0, at)}${word[at] === '0' ? '1' : '0'}${word.slice(at + 1)}`;

test('a note opens with its wallet\'s viewing key, and gives out its five words alone', async () => {
  const alice = await wallet(ALICE);
  const note = await newDepositNote(alice);

  assert.deepEqual(Object.keys(note).sort(), ['encryptedBundle', 'npk', 'shieldKey']);
  assert.equal(note.encryptedBundle.length, 3);
  for (const word of [note.npk, ...note.encryptedBundle, note.shieldKey]) assert.match(word, WORD);
  await openDepositNote(note, alice);
});

test('every note is new: its random and shield key are drawn each time', async () => {
  const alice = await wallet(ALICE);
  const [first, second] = [await newDepositNote(alice), await newDepositNote(alice)];

  assert.notEqual(first.npk, second.npk);
  assert.notEqual(first.shieldKey, second.shieldKey);
});

test('another wallet can\'t open it, and a note to another wallet is refused', async () => {
  const [alice, bob] = [await wallet(ALICE), await wallet(BOB)];
  const note = await newDepositNote(alice);

  await assert.rejects(openDepositNote(note, bob), /doesn't open/);
  // Alice's viewing key opens it, but its npk commits to Alice's master key, so Bob could never spend it.
  await assert.rejects(openDepositNote(note, { ...alice, masterPublicKey: bob.masterPublicKey }), /npk/);
});

// The receiver reads the random from the first word (its IV and tag) and the first half of the second, with the
// shield key; the rest hides the receiver's viewing key from everyone but the shield key's holder.
test('a note changed anywhere its receiver reads is refused', async () => {
  const alice = await wallet(ALICE);
  const note = await newDepositNote(alice);
  const [ivAndTag, random, receiver] = note.encryptedBundle;

  await assert.rejects(openDepositNote({ ...note, npk: altered(note.npk) }, alice), /npk/);
  await assert.rejects(openDepositNote({ ...note, encryptedBundle: [altered(ivAndTag, 2), random, receiver] }, alice), /open/);
  await assert.rejects(openDepositNote({ ...note, encryptedBundle: [altered(ivAndTag), random, receiver] }, alice), /open/);
  await assert.rejects(openDepositNote({ ...note, encryptedBundle: [ivAndTag, altered(random, 2), receiver] }, alice), /open/);
  await assert.rejects(openDepositNote({ ...note, shieldKey: altered(note.shieldKey) }, alice), /open/);
});

test('anything but five 32-byte words is refused', async () => {
  const alice = await wallet(ALICE);
  const note = await newDepositNote(alice);

  for (const broken of [
    { ...note, encryptedBundle: note.encryptedBundle.slice(0, 2) },
    { ...note, npk: note.npk.slice(0, -2) },
    { ...note, shieldKey: undefined },
    { ...note, npk: `${note.npk.slice(0, -1)}g` },
  ]) {
    await assert.rejects(openDepositNote(broken, alice), /five 32-byte words/);
  }
});
