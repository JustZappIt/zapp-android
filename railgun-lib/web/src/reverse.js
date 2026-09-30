import { NETWORK_CONFIG, NetworkName } from '@railgun-community/shared-models';
import {
  gasEstimateForUnprovenCrossContractCalls,
  generateCrossContractCallsProof,
  populateProvedCrossContractCalls,
} from '@railgun-community/wallet';
import { getAddress } from 'ethers';
import { badRequest } from './errors.js';
import { fundingAmounts } from './reverse-amounts.js';
import { gasDetails, progress, sign, spend } from './transact.js';
import { TXID_VERSION, session } from './wallet.js';

/** What unshielding enough to leave `amount` in escrow costs, at the fee the engine loaded. */
export function reverseCost({ amount, railgun }) {
  const { network, fees } = session();
  if (network !== NetworkName.EthereumSepolia) throw badRequest('reverse funding is testnet only');
  const proxy = NETWORK_CONFIG[network].proxyContract;
  if (getAddress(railgun) !== getAddress(proxy)) throw badRequest(`the engine's Railgun contract is ${proxy}`);
  return fundingAmounts(BigInt(amount), BigInt(fees.unshield));
}

export const prepareReverse = (params, emit) =>
  spend(async () => {
    const { network, walletId, encryptionKey, address } = session();
    const { token, calls } = params;
    const cost = reverseCost(params);
    const unshield = [{ tokenAddress: token, amount: BigInt(cost.debit) }];
    const shield = [{ tokenAddress: token, recipientAddress: address }];
    const transactions = calls.map(({ to, data }) => ({ to, data, value: 0n }));
    if (transactions.length !== 2) throw badRequest('expected approval and escrow open');
    // Public testnet gas sends force requireSuccess across the entire Relay Adapt call.
    const { gasEstimate } = await gasEstimateForUnprovenCrossContractCalls(
      TXID_VERSION, network, walletId, encryptionKey, unshield, [], shield, [], transactions,
      await gasDetails(0n), undefined, true, undefined,
    );
    await generateCrossContractCallsProof(
      TXID_VERSION, network, walletId, encryptionKey, unshield, [], shield, [], transactions,
      undefined, true, undefined, undefined, progress(emit),
    );
    const { transaction } = await populateProvedCrossContractCalls(
      TXID_VERSION, network, walletId, unshield, [], shield, [], transactions,
      undefined, true, undefined, await gasDetails(gasEstimate),
    );
    const { raw, txHash } = await sign(transaction);
    return { raw, txId: txHash, cost };
  });
