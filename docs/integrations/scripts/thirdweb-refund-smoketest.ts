#!/usr/bin/env bun
/**
 * Base Sepolia thirdweb smoketest: refunds an expired PAY order via
 * `autoCancelExpiredOrders([id])` through the same bundler + paymaster the Android app uses.
 *
 * Why this script exists:
 *  - The Android app's "Cancel & refund" button hit a paymaster 500 because `cancelOrder` reverts
 *    NotAuthorized for PAY orders in PLACED state (verified on-chain). The bundler returns 500
 *    for any UserOp whose simulation reverts.
 *  - The actual refund path for PAY-PLACED-expired is `autoCancelExpiredOrders`, which is
 *    permissionless and DOES simulate cleanly.
 *  - This script exercises the bundler + paymaster end-to-end with that op, proving thirdweb
 *    wiring works generally (and isolates earlier 500s to the cancelOrder-specific revert).
 *  - As a side effect, it actually cancels the order so the Android app's poll observes
 *    CANCELLED status and routes to the Cancelled screen.
 *
 * Run from inside a `p2pdotme-sdk` clone (where viem + @noble/* are installed):
 *   OFFRAMP_OWNER_MNEMONIC="word1 word2 ... word24" \
 *   OFFRAMP_ORDER_ID=216 \
 *   THIRDWEB_CLIENT_ID=<YOUR_THIRDWEB_CLIENT_ID> \
 *   bun /path/to/zodl-android/docs/integrations/scripts/thirdweb-refund-smoketest.ts
 *
 * Owner derivation matches the Android app (BIP-39 → BIP-32 m/44'/60'/0'/0/0). Smart account
 * address is computed via `AccountFactory.getAddress(owner)` — same as the app.
 */

import {
	createPublicClient,
	encodeAbiParameters,
	encodeFunctionData,
	http,
	keccak256,
	parseAbi,
	type Hex,
} from "viem";
import { mnemonicToAccount } from "viem/accounts";
import { baseSepolia } from "viem/chains";

const RPC_URL = process.env.OFFRAMP_RPC_URL ?? "https://sepolia.base.org";
const DIAMOND = "0xeb0BB8E3c014D915D9B2df03aBB130a1Fb44beb9" as Hex;
const USDC = "0x4095fE4f1E636f11A95820BA2bB87F335Bd1040d" as Hex;
const ENTRY_POINT = "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789" as Hex;
const ACCOUNT_FACTORY = "0x85e23b94e7F5E9cC1fF78BCe78cfb15B81f0DF00" as Hex;
const CHAIN = baseSepolia;
const BUNDLER_URL = `https://${CHAIN.id}.bundler.thirdweb.com/v2`;
const GAS_BUFFER_PCT = 15n;
const RECEIPT_POLL_INTERVAL_MS = 2000;
const RECEIPT_POLL_ATTEMPTS = 90; // 3 min @ 2 s

const need = (n: string): string => {
	const v = process.env[n];
	if (!v) {
		console.error(`Missing env var: ${n}`);
		process.exit(1);
	}
	return v;
};

const MNEMONIC = need("OFFRAMP_OWNER_MNEMONIC");
const ORDER_ID = BigInt(process.env.OFFRAMP_ORDER_ID ?? "216");
const CLIENT_ID = need("THIRDWEB_CLIENT_ID");

async function main() {
const owner = mnemonicToAccount(MNEMONIC, { addressIndex: 0 });
console.log(`Owner EOA:     ${owner.address}`);

const publicClient = createPublicClient({ chain: CHAIN, transport: http(RPC_URL) });

// ── Compute the smart account address (factory.getAddress(owner)) ─────────────────────────
const factoryAbi = parseAbi([
	"function getAddress(address admin, bytes calldata data) view returns (address)",
]);
const smartAccount = (await publicClient.readContract({
	address: ACCOUNT_FACTORY,
	abi: factoryAbi,
	functionName: "getAddress",
	args: [owner.address, "0x"],
})) as Hex;
console.log(`Smart account: ${smartAccount}`);

// ── Sanity reads ──────────────────────────────────────────────────────────────────────────
const usdcAbi = parseAbi(["function balanceOf(address) view returns (uint256)"]);
const balance = await publicClient.readContract({
	address: USDC,
	abi: usdcAbi,
	functionName: "balanceOf",
	args: [smartAccount],
});
console.log(`USDC balance:  ${Number(balance) / 1e6} USDC`);

const diamondAbi = parseAbi([
	"function isOrderExpired(uint256) view returns (bool)",
	"function autoCancelExpiredOrders(uint256[])",
]);
const expired = await publicClient.readContract({
	address: DIAMOND,
	abi: diamondAbi,
	functionName: "isOrderExpired",
	args: [ORDER_ID],
});
console.log(`Order #${ORDER_ID} expired? ${expired}`);
if (!expired) {
	console.error("Order is not expired — autoCancel would revert. Bail.");
	process.exit(1);
}

// ── Build the userOp callData: smartAccount.execute(diamond, 0, autoCancelExpiredOrders([id])) ─
const innerCalldata = encodeFunctionData({
	abi: diamondAbi,
	functionName: "autoCancelExpiredOrders",
	args: [[ORDER_ID]],
});
const executeAbi = parseAbi(["function execute(address,uint256,bytes)"]);
const callData = encodeFunctionData({
	abi: executeAbi,
	functionName: "execute",
	args: [DIAMOND, 0n, innerCalldata],
});

// ── Resolve nonce + initCode ──────────────────────────────────────────────────────────────
const entryPointAbi = parseAbi(["function getNonce(address,uint192) view returns (uint256)"]);
const nonce = await publicClient.readContract({
	address: ENTRY_POINT,
	abi: entryPointAbi,
	functionName: "getNonce",
	args: [smartAccount, 0n],
});
const code = await publicClient.getCode({ address: smartAccount });
const initCode: Hex = code && code !== "0x" ? "0x" : await buildInitCode(owner.address);
console.log(`Nonce:         ${nonce}  (deployed=${code && code !== "0x"})`);

async function buildInitCode(admin: Hex): Promise<Hex> {
	// AccountFactory.createAccount(admin, data) — same as Erc4337Submitter's initCode helper.
	const createAccountData = encodeFunctionData({
		abi: parseAbi(["function createAccount(address,bytes)"]),
		functionName: "createAccount",
		args: [admin, "0x"],
	});
	return (ACCOUNT_FACTORY + createAccountData.slice(2)) as Hex;
}

// ── Bundler JSON-RPC helper ───────────────────────────────────────────────────────────────
let rpcId = 1;
async function rpc(method: string, params: unknown[]): Promise<any> {
	const body = JSON.stringify({ jsonrpc: "2.0", id: rpcId++, method, params });
	process.stdout.write(`  REQ ${method}\n`);
	const res = await fetch(BUNDLER_URL, {
		method: "POST",
		headers: { "Content-Type": "application/json", "X-Client-Id": CLIENT_ID },
		body,
	});
	const text = await res.text();
	process.stdout.write(`  RES ${method} status=${res.status} body=${text.slice(0, 600)}\n`);
	if (!res.ok) throw new Error(`${method} HTTP ${res.status}: ${text}`);
	const data = JSON.parse(text);
	if (data.error) throw new Error(`${method} error: ${JSON.stringify(data.error)}`);
	return data.result;
}

// ── UserOp v0.6 RPC sequence ──────────────────────────────────────────────────────────────
console.log("\n── thirdweb_getUserOperationGasPrice ──");
const gasPrice = await rpc("thirdweb_getUserOperationGasPrice", []);

// Dummy ECDSA signature for gas estimation (matches the Erc4337Submitter dummy: a real ECDSA
// sig over an arbitrary hash, so recover() doesn't revert during estimation).
const DUMMY_SIG: Hex = await owner.sign({ hash: keccak256(new TextEncoder().encode("estimate")) });

const draft = {
	sender: smartAccount,
	nonce: hex(nonce),
	initCode,
	callData,
	callGasLimit: "0x0",
	verificationGasLimit: "0x0",
	preVerificationGas: "0x0",
	maxFeePerGas: gasPrice.maxFeePerGas as Hex,
	maxPriorityFeePerGas: gasPrice.maxPriorityFeePerGas as Hex,
	paymasterAndData: "0x" as Hex,
	signature: DUMMY_SIG,
};

console.log("\n── pm_getPaymasterStubData ──");
const stub = await rpc("pm_getPaymasterStubData", [draft, ENTRY_POINT, hex(BigInt(CHAIN.id))]);
const stubbed = { ...draft, paymasterAndData: stub.paymasterAndData as Hex };

console.log("\n── eth_estimateUserOperationGas ──");
const estimate = await rpc("eth_estimateUserOperationGas", [stubbed, ENTRY_POINT]);
const buffered = (h: Hex): Hex => hex((BigInt(h) * (100n + GAS_BUFFER_PCT)) / 100n);
const withGas = {
	...stubbed,
	callGasLimit: buffered(estimate.callGasLimit),
	verificationGasLimit: buffered(estimate.verificationGasLimit),
	preVerificationGas: buffered(estimate.preVerificationGas),
};

console.log("\n── pm_sponsorUserOperation ──");
const sponsor = await rpc("pm_sponsorUserOperation", [withGas, ENTRY_POINT, hex(BigInt(CHAIN.id))]);
const sponsored = { ...withGas, paymasterAndData: sponsor.paymasterAndData as Hex };

// ── Sign userOpHash with EIP-191 prefix (matches Erc4337Submitter.signOwner) ─────────────
const userOpHash = computeUserOpHash(sponsored, ENTRY_POINT, BigInt(CHAIN.id));
console.log(`\nuserOpHash:    ${userOpHash}`);
const signature = await owner.signMessage({ message: { raw: userOpHash } });
const signed = { ...sponsored, signature };

console.log("\n── eth_sendUserOperation ──");
const opHash = (await rpc("eth_sendUserOperation", [signed, ENTRY_POINT])) as Hex;
console.log(`\nSubmitted userOp: ${opHash}`);
console.log(`Explorer:         https://sepolia.basescan.org/tx/${opHash}`);

console.log("\n── polling for receipt ──");
let receipt: any = null;
for (let i = 0; i < RECEIPT_POLL_ATTEMPTS; i++) {
	const r = await rpc("eth_getUserOperationReceipt", [opHash]);
	if (r && r.success !== undefined) {
		receipt = r;
		break;
	}
	await new Promise((resolve) => setTimeout(resolve, RECEIPT_POLL_INTERVAL_MS));
}
if (!receipt) {
	console.error("Timed out waiting for receipt. The op may still confirm — check explorer.");
	process.exit(1);
}
console.log(`\nReceipt success: ${receipt.success}`);
console.log(`Inner tx:        ${receipt.receipt?.transactionHash}`);
console.log(`Actual gas:      ${BigInt(receipt.actualGasUsed)} units`);
console.log(`Actual cost:     ${Number(BigInt(receipt.actualGasCost)) / 1e18} ETH (paid by paymaster)`);

// ── Verify the on-chain effect ────────────────────────────────────────────────────────────
console.log("\n── post-state ──");
const postExpired = await publicClient.readContract({
	address: DIAMOND,
	abi: diamondAbi,
	functionName: "isOrderExpired",
	args: [ORDER_ID],
});
console.log(`Order #${ORDER_ID} isOrderExpired now? ${postExpired}  (false = order is no longer in an expirable state)`);
console.log(
	`\nIf you have the Android app open with this order, the WaitingForMerchantAcceptance poll`,
);
console.log(`will observe status=CANCELLED within ~3 s and route to the Cancelled screen.`);
} // end async main

main().catch((e) => {
	console.error(e);
	process.exit(1);
});

// ── Helpers ───────────────────────────────────────────────────────────────────────────────
function hex(value: bigint): Hex {
	return `0x${value.toString(16)}` as Hex;
}

// ERC-4337 v0.6 userOpHash = keccak256(abi.encode(packUserOp(op), entryPoint, chainId))
function computeUserOpHash(op: typeof signed, entryPoint: Hex, chainId: bigint): Hex {
	const packed = encodeAbiParameters(
		[
			{ type: "address" },
			{ type: "uint256" },
			{ type: "bytes32" },
			{ type: "bytes32" },
			{ type: "uint256" },
			{ type: "uint256" },
			{ type: "uint256" },
			{ type: "uint256" },
			{ type: "uint256" },
			{ type: "bytes32" },
		],
		[
			op.sender as Hex,
			BigInt(op.nonce),
			keccak256(op.initCode as Hex),
			keccak256(op.callData as Hex),
			BigInt(op.callGasLimit),
			BigInt(op.verificationGasLimit),
			BigInt(op.preVerificationGas),
			BigInt(op.maxFeePerGas),
			BigInt(op.maxPriorityFeePerGas),
			keccak256(op.paymasterAndData as Hex),
		],
	);
	const opHash = keccak256(packed);
	return keccak256(
		encodeAbiParameters([{ type: "bytes32" }, { type: "address" }, { type: "uint256" }], [
			opHash,
			entryPoint,
			chainId,
		]),
	);
}
