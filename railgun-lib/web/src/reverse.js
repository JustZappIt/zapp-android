import { fundingAmounts } from './reverse-amounts.js';
import { Contract, keccak256 } from 'ethers';
import {
  gasEstimateForUnprovenCrossContractCalls,
  generateCrossContractCallsProof,
  populateProvedCrossContractCalls,
} from '@railgun-community/wallet';
import { NetworkName } from '@railgun-community/shared-models';
import { session, TXID_VERSION } from './wallet.js';
import { requireGasWallet, gasDetails } from './transact.js';

export async function reverseCost({ amount, railgun }) {
  if (session().network !== NetworkName.EthereumSepolia) throw new Error('reverse funding is testnet only');
  const proxy = new Contract(railgun, ['function unshieldFee() view returns (uint256)'], requireGasWallet().provider);
  const basisPoints = await proxy.unshieldFee();
  return { ...fundingAmounts(BigInt(amount), basisPoints), unshieldFeeBasisPoints: Number(basisPoints) };
}

export async function prepareReverse(params, emit) {
  const { network, walletId, encryptionKey, address } = session();
  const { token, calls } = params;
  const cost = await reverseCost(params);
  const unshield = [{ tokenAddress: token, amount: BigInt(cost.debit) }];
  const shield = [{ tokenAddress: token, recipientAddress: address }];
  const transactions = calls.map(({ to, data }) => ({ to, data, value: 0n }));
  if (transactions.length !== 2) throw new Error('expected approval and escrow open');
  // Public testnet gas sends force requireSuccess across the entire Relay Adapt call.
  const { gasEstimate } = await gasEstimateForUnprovenCrossContractCalls(
    TXID_VERSION, network, walletId, encryptionKey, unshield, [], shield, [], transactions,
    await gasDetails(0n), undefined, true, undefined,
  );
  await generateCrossContractCallsProof(
    TXID_VERSION, network, walletId, encryptionKey, unshield, [], shield, [], transactions,
    undefined, true, undefined, undefined,
    (progress, status) => emit('proof', { progress, status: String(status ?? '') }),
  );
  const { transaction } = await populateProvedCrossContractCalls(
    TXID_VERSION, network, walletId, unshield, [], shield, [], transactions,
    undefined, true, undefined, await gasDetails(gasEstimate),
  );
  const account = requireGasWallet();
  const populated = await account.populateTransaction(transaction);
  const raw = await account.signTransaction(populated);
  return { raw, txId: keccak256(raw), cost };
}
