// Sends Sepolia ETH from the account RAILGUN_DEV_GAS_KEY in local.properties names, to top up the
// gas account of a phone's debug build (the Railgun screen shows its address).
//   node dev/fund.mjs <address> <eth>
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { JsonRpcProvider, Wallet, formatEther, getAddress, parseEther } from 'ethers';

const RPC_URL = process.env.RAILGUN_CHECK_RPC ?? 'https://ethereum-sepolia-rpc.publicnode.com';

const [to, eth] = process.argv.slice(2);
if (!to || !eth) throw new Error('usage: node dev/fund.mjs <address> <eth>');
const properties = await readFile(path.resolve('../../local.properties'), 'utf8');
const key = properties.match(/^\s*RAILGUN_DEV_GAS_KEY\s*=\s*(\S+)/m)?.[1];
if (!key) throw new Error('set RAILGUN_DEV_GAS_KEY in local.properties');

const provider = new JsonRpcProvider(RPC_URL);
const from = new Wallet(key, provider);
const response = await from.sendTransaction({ to: getAddress(to), value: parseEther(eth) });
await response.wait();
console.log(`sent ${eth} ETH to ${to}: https://sepolia.etherscan.io/tx/${response.hash}`);
console.log(`${to} now holds ${formatEther(await provider.getBalance(to))} ETH`);
