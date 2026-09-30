// A funded gas account signs and sends in place of a broadcaster, linking each transaction to it: testnets only.
import { EVMGasType, NETWORK_CONFIG, NetworkName, getEVMGasTypeForTransaction } from '@railgun-community/shared-models';
import {
  gasEstimateForShieldBaseToken,
  gasEstimateForUnprovenTransfer,
  gasEstimateForUnprovenUnshield,
  generateTransferProof,
  generateUnshieldProof,
  getFallbackProviderForNetwork,
  getShieldPrivateKeySignatureMessage,
  populateProvedTransfer,
  populateProvedUnshield,
  populateShieldBaseToken,
} from '@railgun-community/wallet';
import { Wallet, keccak256 } from 'ethers';
import { badRequest } from './errors.js';
import { TXID_VERSION, session } from './wallet.js';

const SEND_WITH_PUBLIC_WALLET = true;

let gasWallet;
let spends = Promise.resolve();

export function requireGasWallet() {
  if (gasWallet === undefined) throw badRequest('no gas account is set');
  return gasWallet;
}

export async function setGasAccount({ privateKey }) {
  const { network } = session();
  if (network !== NetworkName.EthereumSepolia) throw badRequest('a gas account is for testnets only');
  gasWallet = new Wallet(privateKey, getFallbackProviderForNetwork(network));
  return gasAccount();
}

export async function gasAccount() {
  const account = requireGasWallet();
  const balance = await account.provider.getBalance(account.address);
  return { address: account.address, balance: balance.toString() };
}

/** One spend at a time, so none proves over the notes another is spending. */
export function spend(action) {
  const result = spends.then(action);
  spends = result.catch(() => {});
  return result;
}

/** Wraps `amount` wei of the gas account's ETH and shields it to `to`, the open wallet by default. */
export const shield = ({ amount, to }) =>
  spend(async () => {
    const { network, address } = session();
    const recipient = to ?? address;
    const account = requireGasWallet();
    const shieldPrivateKey = keccak256(await account.signMessage(getShieldPrivateKeySignatureMessage()));
    const wrapped = { tokenAddress: NETWORK_CONFIG[network].baseToken.wrappedAddress, amount: BigInt(amount) };
    const { gasEstimate } = await gasEstimateForShieldBaseToken(
      TXID_VERSION, network, recipient, shieldPrivateKey, wrapped, account.address,
    );
    const { transaction } = await populateShieldBaseToken(
      TXID_VERSION, network, recipient, shieldPrivateKey, wrapped, await gasDetails(gasEstimate),
    );
    return sign(transaction);
  });

export const transfer = ({ to, token, amount }, emit) =>
  spend(async () => {
    const { network, walletId, encryptionKey } = session();
    const recipients = [{ tokenAddress: token, amount: BigInt(amount), recipientAddress: to }];
    const { gasEstimate } = await gasEstimateForUnprovenTransfer(
      TXID_VERSION, network, walletId, encryptionKey, undefined, recipients, [], await gasDetails(0n), undefined,
      SEND_WITH_PUBLIC_WALLET,
    );
    const proofMs = await timed(() =>
      generateTransferProof(
        TXID_VERSION, network, walletId, encryptionKey, false, undefined, recipients, [], undefined,
        SEND_WITH_PUBLIC_WALLET, undefined, progress(emit),
      ),
    );
    const { transaction } = await populateProvedTransfer(
      TXID_VERSION, network, walletId, false, undefined, recipients, [], undefined, SEND_WITH_PUBLIC_WALLET, undefined,
      await gasDetails(gasEstimate),
    );
    return { ...(await sign(transaction)), proofMs };
  });

/** Withdraws to the public address `to`. */
export const unshield = ({ to, token, amount }, emit) =>
  spend(async () => {
    const { network, walletId, encryptionKey } = session();
    const recipients = [{ tokenAddress: token, amount: BigInt(amount), recipientAddress: to }];
    const { gasEstimate } = await gasEstimateForUnprovenUnshield(
      TXID_VERSION, network, walletId, encryptionKey, recipients, [], await gasDetails(0n), undefined,
      SEND_WITH_PUBLIC_WALLET,
    );
    const proofMs = await timed(() =>
      generateUnshieldProof(
        TXID_VERSION, network, walletId, encryptionKey, recipients, [], undefined, SEND_WITH_PUBLIC_WALLET, undefined,
        progress(emit),
      ),
    );
    const { transaction } = await populateProvedUnshield(
      TXID_VERSION, network, walletId, recipients, [], undefined, SEND_WITH_PUBLIC_WALLET, undefined,
      await gasDetails(gasEstimate),
    );
    return { ...(await sign(transaction)), proofMs };
  });

/**
 * Signs with the gas account and sends nothing: the host keeps the bytes, their hash, and the account's nonce
 * they spend before it sends them itself.
 */
export async function sign(transaction) {
  const account = requireGasWallet();
  const populated = await account.populateTransaction(transaction);
  const raw = await account.signTransaction(populated);
  return { raw, txHash: keccak256(raw), from: account.address, nonce: populated.nonce };
}

export async function gasDetails(gasEstimate) {
  const { network } = session();
  const fees = await requireGasWallet().provider.getFeeData();
  const evmGasType = getEVMGasTypeForTransaction(network, SEND_WITH_PUBLIC_WALLET);
  return evmGasType === EVMGasType.Type2
    ? { evmGasType, gasEstimate, maxFeePerGas: fees.maxFeePerGas, maxPriorityFeePerGas: fees.maxPriorityFeePerGas }
    : { evmGasType, gasEstimate, gasPrice: fees.gasPrice };
}

/** The prover counts to 100; the host reads progress from 0 to 1. */
export const progress = (emit) => (value, status) =>
  emit('proof', { progress: value / 100, status: String(status ?? '') });

async function timed(action) {
  const started = performance.now();
  await action();
  return Math.round(performance.now() - started);
}
