// Shield, private send and withdraw, paid for and sent by a gas account: a funded Ethereum account
// that stands in for a Railgun broadcaster while testnets have none. Testnets only, because sending
// from it ties its public address to every transaction.
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
import { TXID_VERSION, session } from './wallet.js';

const SEND_WITH_PUBLIC_WALLET = true;

let gasWallet;

function requireGasWallet() {
  if (gasWallet === undefined) throw new Error('no gas account is set');
  return gasWallet;
}

export async function setGasAccount({ privateKey }) {
  const { network } = session();
  if (network !== NetworkName.EthereumSepolia) throw new Error('a gas account is for testnets only');
  gasWallet = new Wallet(privateKey, getFallbackProviderForNetwork(network));
  return gasAccount();
}

export async function gasAccount() {
  const account = requireGasWallet();
  const balance = await account.provider.getBalance(account.address);
  return { address: account.address, balance: balance.toString() };
}

/** Wraps `amount` wei of the gas account's ETH and shields it to `to`, the open wallet by default. */
export async function shield({ amount, to }) {
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
  return { txHash: await send(transaction) };
}

export async function transfer({ to, token, amount }, emit) {
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
  return { txHash: await send(transaction), proofMs };
}

/** Withdraws to a public address, `to`. */
export async function unshield({ to, token, amount }, emit) {
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
  return { txHash: await send(transaction), proofMs };
}

async function send(transaction) {
  const response = await requireGasWallet().sendTransaction(transaction);
  const receipt = await response.wait();
  if (receipt?.status !== 1) throw new Error(`transaction ${response.hash} failed`);
  return response.hash;
}

async function gasDetails(gasEstimate) {
  const { network } = session();
  const fees = await requireGasWallet().provider.getFeeData();
  const evmGasType = getEVMGasTypeForTransaction(network, SEND_WITH_PUBLIC_WALLET);
  return evmGasType === EVMGasType.Type2
    ? { evmGasType, gasEstimate, maxFeePerGas: fees.maxFeePerGas, maxPriorityFeePerGas: fees.maxPriorityFeePerGas }
    : { evmGasType, gasEstimate, gasPrice: fees.gasPrice };
}

const progress = (emit) => (value, status) => emit('proof', { progress: value, status: String(status ?? '') });

async function timed(action) {
  const started = performance.now();
  await action();
  return Math.round(performance.now() - started);
}
