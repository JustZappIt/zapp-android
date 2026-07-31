/**
 * Emits known-good calldata for our Diamond + ERC-20 calls so the Kotlin
 * ABI encoder can be tested byte-for-byte against viem's `encodeFunctionData`.
 *
 * Re-run only when our argument shapes change. Output is frozen as Kotlin
 * constants in evm-lib's tests.
 *
 * Usage (script has no SDK-source imports; it only needs viem in the cwd's
 * node_modules):
 *   bun --cwd /path/to/p2pdotme-sdk run /path/to/generate-calldata-fixtures.ts
 */
import {
	encodeFunctionData,
	erc20Abi,
	keccak256,
	stringToHex,
	toBytes,
} from "viem";

// Minimal inlined fragments — kept in sync manually with
// p2pdotme-sdk/src/contracts/abis/{order-flow-facet,order-processor-facet}.ts.

const placeOrderAbi = [
	{
		type: "function",
		name: "placeOrder",
		stateMutability: "nonpayable",
		inputs: [
			{ name: "_pubKey", type: "string" },
			{ name: "_amount", type: "uint256" },
			{ name: "_recipientAddr", type: "address" },
			{ name: "_orderType", type: "uint8" },
			{ name: "_userUpi", type: "string" },
			{ name: "_userPubKey", type: "string" },
			{ name: "_currency", type: "bytes32" },
			{ name: "preferredPaymentChannelConfigId", type: "uint256" },
			{ name: "_circleId", type: "uint256" },
			{ name: "_fiatAmountLimit", type: "uint256" },
		],
		outputs: [],
	},
] as const;

const setSellOrderUpiAbi = [
	{
		type: "function",
		name: "setSellOrderUpi",
		stateMutability: "nonpayable",
		inputs: [
			{ name: "_orderId", type: "uint256" },
			{ name: "_userEncUpi", type: "string" },
			{ name: "_updatedAmount", type: "uint256" },
		],
		outputs: [],
	},
] as const;

const getOrdersByIdAbi = [
	{
		type: "function",
		name: "getOrdersById",
		stateMutability: "view",
		inputs: [{ name: "orderId", type: "uint256" }],
		outputs: [{ name: "", type: "tuple", components: [{ name: "x", type: "uint256" }] }],
	},
] as const;

const getPriceConfigAbi = [
	{
		type: "function",
		name: "getPriceConfig",
		stateMutability: "view",
		inputs: [{ name: "currency", type: "bytes32" }],
		outputs: [
			{
				name: "",
				type: "tuple",
				components: [
					{ name: "buyPrice", type: "uint256" },
					{ name: "sellPrice", type: "uint256" },
					{ name: "buyPriceOffset", type: "uint256" },
					{ name: "baseSpread", type: "uint256" },
				],
			},
		],
	},
] as const;

const getAssignableMerchantsFromCircleAbi = [
	{
		type: "function",
		name: "getAssignableMerchantsFromCircle",
		stateMutability: "view",
		inputs: [
			{ name: "circleId", type: "uint256" },
			{ name: "assignUpto", type: "uint256" },
			{ name: "currency", type: "bytes32" },
			{ name: "user", type: "address" },
			{ name: "usdtAmount", type: "uint256" },
			{ name: "fiatAmount", type: "uint256" },
			{ name: "orderType", type: "int256" },
			{ name: "preferredPCConfigId", type: "uint256" },
		],
		outputs: [{ name: "", type: "address[]" }],
	},
] as const;

const out: Record<string, string> = {};

out.approve = encodeFunctionData({
	abi: erc20Abi,
	functionName: "approve",
	args: [
		"0xce868398FDaDcA368EAc203222874D6888532aE2",
		1_000_000n,
	],
});

out.placeOrder = encodeFunctionData({
	abi: placeOrderAbi,
	functionName: "placeOrder",
	args: [
		"1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f70beaf8f588b541507fed6a642c5ab42dfdf8120a7f639de5122d47a69a8e8d1",
		5_000_000n,
		"0x000000000000000000000000000000000000dead",
		2,
		"",
		"",
		stringToHex("INR", { size: 32 }),
		0n,
		1n,
		0n,
	],
});

out.setSellOrderUpi = encodeFunctionData({
	abi: setSellOrderUpiAbi,
	functionName: "setSellOrderUpi",
	args: [42n, "a".repeat(170), 0n],
});

out.getOrdersById = encodeFunctionData({
	abi: getOrdersByIdAbi,
	functionName: "getOrdersById",
	args: [42n],
});

out.getPriceConfig = encodeFunctionData({
	abi: getPriceConfigAbi,
	functionName: "getPriceConfig",
	args: [stringToHex("INR", { size: 32 })],
});

out.getAssignableMerchantsFromCircle = encodeFunctionData({
	abi: getAssignableMerchantsFromCircleAbi,
	functionName: "getAssignableMerchantsFromCircle",
	args: [
		1n,
		3n,
		stringToHex("INR", { size: 32 }),
		"0x000000000000000000000000000000000000beef",
		5_000_000n,
		418_000_000n,
		2n,
		0n,
	],
});

const selector = (sig: string): string => keccak256(toBytes(sig)).slice(0, 10);

out._selector_approve = selector("approve(address,uint256)");
out._selector_placeOrder = selector(
	"placeOrder(string,uint256,address,uint8,string,string,bytes32,uint256,uint256,uint256)",
);
out._selector_setSellOrderUpi = selector("setSellOrderUpi(uint256,string,uint256)");
out._selector_getOrdersById = selector("getOrdersById(uint256)");
out._selector_getPriceConfig = selector("getPriceConfig(bytes32)");
out._selector_getAssignableMerchantsFromCircle = selector(
	"getAssignableMerchantsFromCircle(uint256,uint256,bytes32,address,uint256,uint256,int256,uint256)",
);

console.log(JSON.stringify(out, null, 2));
