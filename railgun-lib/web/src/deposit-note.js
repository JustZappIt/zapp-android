// Shield notes for zecSwap's Deposits contract to pay into this wallet. The contract shields its vault's token and
// balance itself, so a deposit commits to five words of the note alone: `npk` and its ciphertext's three
// `encryptedBundle` words and `shieldKey`. Railgun takes any ciphertext, and a note its receiver can't open is lost, so
// each note is opened again as the wallet's own scan would before it leaves the page.
import { ByteLength, ByteUtils, ShieldNote, ShieldNoteERC20 } from '@railgun-community/engine';
import { getSharedSymmetricKey } from './engine-keys.js';

const RANDOM_BYTES = 16;
const SHIELD_KEY_BYTES = 32;
const BUNDLE_WORDS = 3;
const WORD = /^0x[0-9a-f]{64}$/;
// Stand-ins: the contract shields its own token and the value it holds, and neither reaches the five words.
const ANY_TOKEN = `0x${'00'.repeat(20)}`;
const ANY_VALUE = 0n;

const randomBytes = (length) => globalThis.crypto.getRandomValues(new Uint8Array(length));

/**
 * A fresh note to the wallet with `masterPublicKey` and `viewingPublicKey`, built by Railgun's engine from a random and
 * a shield key drawn here: `{npk, encryptedBundle, shieldKey}`, each word `0x` hex. Neither the random nor the shield
 * key leaves this function, so a note can't be built again.
 */
export async function newDepositNote({ masterPublicKey, viewingPublicKey }) {
  const random = ByteUtils.hexlify(randomBytes(RANDOM_BYTES));
  const shieldPrivateKey = randomBytes(SHIELD_KEY_BYTES);
  try {
    const note = new ShieldNoteERC20(masterPublicKey, random, ANY_VALUE, ANY_TOKEN);
    const { preimage, ciphertext } = await note.serialize(shieldPrivateKey, viewingPublicKey);
    return words({ npk: preimage.npk, encryptedBundle: ciphertext.encryptedBundle, shieldKey: ciphertext.shieldKey });
  } finally {
    shieldPrivateKey.fill(0);
  }
}

/**
 * Opens `note` as its receiver's scan does: the random its bundle hides, decrypted with `viewingPrivateKey` and the
 * note's `shieldKey`, must give its `npk` with `masterPublicKey`. Throws otherwise, since a deposit to it would be lost.
 */
export async function openDepositNote(note, { masterPublicKey, viewingPrivateKey }) {
  const { npk, encryptedBundle, shieldKey } = words(note);
  let random;
  try {
    const sharedKey = await getSharedSymmetricKey(viewingPrivateKey, ByteUtils.hexStringToBytes(shieldKey));
    if (sharedKey === undefined) throw new Error('no shared key');
    random = ShieldNote.decryptRandom(encryptedBundle, sharedKey);
  } catch {
    throw new Error("the deposit note doesn't open with the wallet's viewing key");
  }
  const notePublicKey = ByteUtils.nToHex(ShieldNote.getNotePublicKey(masterPublicKey, random), ByteLength.UINT_256, true);
  if (notePublicKey !== npk) throw new Error("the deposit note's npk isn't the wallet's");
}

/** `note`'s five words as the contract takes them, lowercase `0x` hex; it throws on anything else. */
function words({ npk, encryptedBundle, shieldKey }) {
  const bundle = Array.isArray(encryptedBundle) ? encryptedBundle : [];
  const all = [npk, ...bundle, shieldKey].map((word) => (typeof word === 'string' ? word.toLowerCase() : ''));
  if (bundle.length !== BUNDLE_WORDS || !all.every((word) => WORD.test(word))) {
    throw new Error('a deposit note is five 32-byte words');
  }
  return { npk: all[0], encryptedBundle: all.slice(1, 1 + BUNDLE_WORDS), shieldKey: all[1 + BUNDLE_WORDS] };
}
