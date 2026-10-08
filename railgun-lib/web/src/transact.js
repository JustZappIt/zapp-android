// Proves sends and withdrawals for a broadcaster to send: it pays their gas for a fee note to its own 0zk address, so
// no public account of the wallet's appears on chain. The page sends nothing.
import { NETWORK_CONFIG, getEVMGasTypeForTransaction } from '@railgun-community/shared-models';
import {
  generateTransferProof,
  generateUnshieldProof,
  getFallbackProviderForNetwork,
  populateProvedTransfer,
  populateProvedUnshield,
} from '@railgun-community/wallet';
import { ABIRailgunSmartWallet } from '@railgun-community/engine';
import { Interface, getAddress } from 'ethers';
import { badRequest } from './errors.js';
import { TXID_VERSION, session } from './wallet.js';

const SEND_WITH_PUBLIC_WALLET = false;
// Only copied into the populated transaction: the broadcaster sets its own gas.
const GAS_ESTIMATE = 1_000_000n;
const RAILGUN = new Interface(ABIRailgunSmartWallet);

let spends = Promise.resolve();

/** One spend at a time, so none proves over the notes another is spending. */
export function spend(action) {
  const result = spends.then(action);
  spends = result.catch(() => {});
  return result;
}

/** Proves a private send of `amount` of `token` to `to`, with `broadcaster`'s fee note first. */
export const transfer = ({ to, token, amount, broadcaster }, emit) =>
  spend(async () => {
    const { network, walletId, encryptionKey } = session();
    const recipients = [{ tokenAddress: token, amount: BigInt(amount), recipientAddress: to }];
    const { fee, minGasPrice, gasDetails } = await relaying(network, broadcaster);
    const proofMs = await timed(() =>
      generateTransferProof(
        TXID_VERSION, network, walletId, encryptionKey, false, undefined, recipients, [], fee, SEND_WITH_PUBLIC_WALLET,
        minGasPrice, progress(emit),
      ),
    );
    const { transaction } = await populateProvedTransfer(
      TXID_VERSION, network, walletId, false, undefined, recipients, [], fee, SEND_WITH_PUBLIC_WALLET, minGasPrice,
      gasDetails,
    );
    return { ...relayed(transaction, broadcaster), proofMs };
  });

/** Proves a withdrawal to the public address `to`, with `broadcaster`'s fee note first. */
export const unshield = ({ to, token, amount, broadcaster }, emit) =>
  spend(async () => {
    const { network, walletId, encryptionKey } = session();
    const recipients = [{ tokenAddress: token, amount: BigInt(amount), recipientAddress: to }];
    const { fee, minGasPrice, gasDetails } = await relaying(network, broadcaster);
    const proofMs = await timed(() =>
      generateUnshieldProof(
        TXID_VERSION, network, walletId, encryptionKey, recipients, [], fee, SEND_WITH_PUBLIC_WALLET, minGasPrice,
        progress(emit),
      ),
    );
    const { transaction } = await populateProvedUnshield(
      TXID_VERSION, network, walletId, recipients, [], fee, SEND_WITH_PUBLIC_WALLET, minGasPrice, gasDetails,
    );
    return { ...relayed(transaction, broadcaster), proofMs };
  });

// The proof binds its minimum gas price: the network's now, which the broadcaster pays at least, within its cap.
async function relaying(network, { chainId, railgunProxy, railgunAddress, token, fee, maxGasPrice }) {
  const { chain, proxyContract } = NETWORK_CONFIG[network];
  if (chainId !== chain.id || getAddress(railgunProxy) !== getAddress(proxyContract)) {
    throw badRequest(`the broadcaster is not for ${network}'s Railgun contract`);
  }
  const { gasPrice } = await getFallbackProviderForNetwork(network).getFeeData();
  if (gasPrice == null || gasPrice > BigInt(maxGasPrice)) {
    throw new Error("the network's gas price is above the broadcaster's cap");
  }
  const evmGasType = getEVMGasTypeForTransaction(network, SEND_WITH_PUBLIC_WALLET);
  return {
    fee: { tokenAddress: token, amount: BigInt(fee), recipientAddress: railgunAddress },
    minGasPrice: gasPrice,
    gasDetails: { evmGasType, gasEstimate: GAS_ESTIMATE, gasPrice },
  };
}

/** The broadcaster's request as proved, and the nullifiers each of its Railgun transactions spends, to settle it by. */
function relayed({ to, data, value }, { chainId, railgunProxy }) {
  if (getAddress(to) !== getAddress(railgunProxy) || (value ?? 0n) !== 0n) {
    throw badRequest('not a plain transact call');
  }
  const [transactions] = RAILGUN.decodeFunctionData('transact', data);
  const spent = transactions.map(({ nullifiers, boundParams }) => ({
    tree: Number(boundParams.treeNumber),
    nullifiers: [...nullifiers],
  }));
  return { chainId, to, data, value: '0', spends: spent };
}

/** The prover counts to 100; the host reads progress from 0 to 1. */
export const progress = (emit) => (value, status) =>
  emit('proof', { progress: value / 100, status: String(status ?? '') });

async function timed(action) {
  const started = performance.now();
  await action();
  return Math.round(performance.now() - started);
}
