/**
 * One-shot fixture generator for the Kotlin ECIES port.
 *
 * Re-run only if the SDK's ECIES wire format changes. The output is frozen as
 * Kotlin constants in evm-lib's EciesTest.kt.
 *
 * Usage:
 *   1. Ensure p2pdotme-sdk is cloned and `bun install`-ed.
 *   2. Copy this file into p2pdotme-sdk/scripts/ (the @noble/* imports resolve
 *      from that node_modules tree; relative paths assume scripts/ alongside src/).
 *   3. From the SDK directory:  bun run scripts/generate-ecies-fixture.ts
 *   4. Copy the four ciphertextHex strings back into EciesTest.kt's SDK_FIXTURES.
 *
 * Output is JSON on stdout: privateKeyHex, publicKeyHex, fixtures[].
 */
import { secp256k1 } from "@noble/curves/secp256k1";
import {
	cipherParse,
	cipherStringify,
	decryptWithPrivateKey,
	encryptWithPublicKey,
} from "../src/orders/crypto/ecies";

function bytesToHex(b: Uint8Array): string {
	let h = "";
	for (const x of b) h += x.toString(16).padStart(2, "0");
	return h;
}

// Fixed private key — deterministic across runs.
// 32 bytes of 0x01 — invalid as a "real" wallet key, fine as a test vector.
const privBytes = new Uint8Array(32).fill(0x01);
const privHex = `0x${bytesToHex(privBytes)}`;
const pubBytesUncompressed = secp256k1.getPublicKey(privBytes, false);
// eth-crypto stores publicKey without the 04 prefix
const pubHex = bytesToHex(pubBytesUncompressed).slice(2);

const fixtures: Array<{ label: string; plaintext: string }> = [
	{ label: "simple_ascii", plaintext: "hello world" },
	{ label: "upi_payload", plaintext: '{"message":"merchant@upi","signature":"0xdeadbeef"}' },
	{ label: "unicode", plaintext: "Prashant — éèê — 你好" },
	{ label: "empty", plaintext: "" },
];

const results: Array<{
	label: string;
	plaintext: string;
	ciphertextHex: string;
}> = [];

for (const f of fixtures) {
	const encrypted = await encryptWithPublicKey(pubHex, f.plaintext);
	const cipherHex = cipherStringify(encrypted);

	const reparsed = cipherParse(cipherHex);
	const decrypted = await decryptWithPrivateKey(privHex, reparsed);
	if (decrypted !== f.plaintext) {
		throw new Error(`Self-test failed for fixture ${f.label}`);
	}

	results.push({ label: f.label, plaintext: f.plaintext, ciphertextHex: cipherHex });
}

console.log(
	JSON.stringify(
		{
			privateKeyHex: privHex,
			publicKeyHex: pubHex,
			fixtures: results,
		},
		null,
		2,
	),
);
