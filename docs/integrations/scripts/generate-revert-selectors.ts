/**
 * Generates `offramp-lib/.../orchestrator/KnownContractErrors.kt` from
 * `p2pdotme-sdk/src/contracts/errors.ts` + `contracts/error-messages.ts`. Keeps our
 * selector → (code, English-message) table byte-aligned with the @p2pdotme/sdk we ship against.
 *
 * The SDK is the single source of truth. `contracts/errors.ts` cleanly separates the error
 * *code* (selector → `contractErrors.<Key>` → "SCREAMING_SNAKE"); `contracts/error-messages.ts`
 * carries the English copy. We emit them as one Kotlin map `Map<Selector4, Entry(name, message)>`
 * because the orchestrator always wants both at the same selector probe.
 *
 * Pure regex parse — no bun/node import-path gymnastics. Re-run whenever the SDK adds new custom
 * errors:
 *
 *   bun /path/to/zodl-android/docs/integrations/scripts/generate-revert-selectors.ts \
 *     /path/to/p2pdotme-sdk \
 *     > /path/to/zodl-android/offramp-lib/src/jvmMain/kotlin/xyz/justzappit/offramp/orchestrator/KnownContractErrors.kt
 *
 * Diff the committed Kotlin file in your PR — the count of selectors should
 * monotonically grow with each SDK release; a drop signals upstream removed
 * an error you may still want to handle.
 */
import { readFileSync } from "node:fs";

const DEFAULT_PATH = "../p2pdotme-sdk";
const sdkPath = process.argv[2] ?? DEFAULT_PATH;
const errorsTsPath = `${sdkPath}/src/contracts/errors.ts`;
const messagesTsPath = `${sdkPath}/src/contracts/error-messages.ts`;

const errorsSrc = readFileSync(errorsTsPath, "utf8");
const messagesSrc = readFileSync(messagesTsPath, "utf8");

// 1. Parse `contractErrors` — maps Kotlin-side constant key → snake_string error name.
//    Lines look like:   NotAdmin: "NOT_ADMIN",
//                       BuyOrderAmountExceedsLimit: "BUY_ORDER_AMOUNT_EXCEEDS_LIMIT",
const constToName = new Map<string, string>();
const contractErrorsBlock = errorsSrc.match(/export const contractErrors\s*=\s*{([\s\S]*?)};/);
if (!contractErrorsBlock) {
	throw new Error(`Could not locate contractErrors block in ${errorsTsPath}`);
}
const constLineRegex = /^\s*([A-Za-z][A-Za-z0-9]*)\s*:\s*"([A-Z0-9_]+)",?\s*$/gm;
{
	const body = contractErrorsBlock[1];
	let m: RegExpExecArray | null;
	while ((m = constLineRegex.exec(body))) {
		const [, key, snakeName] = m;
		constToName.set(key, snakeName);
	}
}

// 2. Parse `hexContractErrors` — selector → contractErrors.<constKey>.
// The SDK types this literal (`: Record<string, ContractErrorCode>`); allow an optional annotation.
const hexBlock = errorsSrc.match(/export const hexContractErrors\s*(?::[^=]+)?=\s*{([\s\S]*?)};/);
if (!hexBlock) {
	throw new Error(`Could not locate hexContractErrors block in ${errorsTsPath}`);
}
const hexLineRegex =
	/^\s*"(0x[a-fA-F0-9]{8})"\s*:\s*contractErrors\.([A-Za-z][A-Za-z0-9]*),?\s*$/gm;
const selectorToName = new Map<string, string>();
{
	const body = hexBlock[1];
	let m: RegExpExecArray | null;
	while ((m = hexLineRegex.exec(body))) {
		const [, selector, constKey] = m;
		const sdkName = constToName.get(constKey);
		if (!sdkName) {
			throw new Error(
				`hexContractErrors references unknown constant key '${constKey}' for ${selector}`,
			);
		}
		// Lowercase the selector hex for consistency with our Selector4.fromHex output.
		selectorToName.set(selector.toLowerCase(), sdkName);
	}
}

if (selectorToName.size === 0) {
	throw new Error(`Parsed 0 selectors from ${errorsTsPath} — regex needs updating`);
}

// 3. Parse `contractErrorMessages` — maps SCREAMING_SNAKE code → English string.
// Entries look like:   NOT_ADMIN: "You are not an admin",
// Some span two lines:  USER_HAS_NO_REPUTATION:\n    "Kindly do ...",
const messagesBlock = messagesSrc.match(
	/export const contractErrorMessages[^{]*{([\s\S]*?)\n};/,
);
if (!messagesBlock) {
	throw new Error(`Could not locate contractErrorMessages block in ${messagesTsPath}`);
}
// `\s*` after the colon also swallows the newline used for wrapped values.
const messageLineRegex = /^\s*([A-Z][A-Z0-9_]*)\s*:\s*"((?:[^"\\]|\\.)*)",?\s*$/gm;
const nameToMessage = new Map<string, string>();
{
	const body = messagesBlock[1];
	let m: RegExpExecArray | null;
	while ((m = messageLineRegex.exec(body))) {
		const [, code, message] = m;
		nameToMessage.set(code, message);
	}
}

if (nameToMessage.size === 0) {
	throw new Error(`Parsed 0 messages from ${messagesTsPath} — regex needs updating`);
}

// 4. Lockstep check — every selector must have a message.
for (const [selector, name] of selectorToName) {
	if (!nameToMessage.has(name)) {
		throw new Error(
			`Selector ${selector} maps to '${name}' but contractErrorMessages has no entry — SDK files drifted`,
		);
	}
}

// 5. Emit Kotlin. Stable ordering (sorted by selector) so re-runs produce zero diff
//    unless the underlying tables actually changed.
const stamp = new Date().toISOString().slice(0, 10);
const sorted = [...selectorToName.entries()].sort(([a], [b]) => a.localeCompare(b));

// Escape for a Kotlin double-quoted string literal.
const esc = (s: string) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\$/g, "\\$");

const lines: string[] = [];
lines.push("// GENERATED FILE — DO NOT EDIT.");
lines.push("// Source: p2pdotme-sdk/src/contracts/errors.ts + error-messages.ts.");
lines.push(`// Regenerate via docs/integrations/scripts/generate-revert-selectors.ts (run on ${stamp}).`);
lines.push("//");
lines.push(`// Selector count: ${selectorToName.size}`);
lines.push("");
lines.push("package xyz.justzappit.offramp.orchestrator");
lines.push("");
lines.push("import xyz.justzappit.evm.abi.Selector4");
lines.push("");
lines.push("/**");
lines.push(" * Wholesale port of the p2p.me Diamond's custom-error selector table. Every selector the");
lines.push(" * contract emits is mapped to its canonical SDK code (`contracts/errors.ts`) and English");
lines.push(" * fallback copy (`contracts/error-messages.ts`). Keeps us byte-aligned with the SDK; do not");
lines.push(" * edit by hand. See the generator header for regeneration.");
lines.push(" *");
lines.push(" * Curated PAY-flow reverts render localised `R.string.*` copy via [KnownRevertReason]; the");
lines.push(" * uncurated long tail falls back to the English [Entry.message] here.");
lines.push(" */");
lines.push("object KnownContractErrors {");
lines.push("    data class Entry(val name: String, val message: String)");
lines.push("");
lines.push("    private val ENTRIES: Map<Selector4, Entry> = mapOf(");
for (const [selector, name] of sorted) {
	const message = nameToMessage.get(name) as string;
	lines.push(`        Selector4.fromHex("${selector}") to Entry("${name}", "${esc(message)}"),`);
}
lines.push("    )");
lines.push("");
lines.push("    /** Full SDK entry (code + English message) for [selector], or null. */");
lines.push("    fun entryFor(selector: Selector4?): Entry? = selector?.let { ENTRIES[it] }");
lines.push("");
lines.push("    /** Canonical SDK code (`ORDER_EXPIRED`) for [selector], or null. */");
lines.push("    fun nameFor(selector: Selector4?): String? = entryFor(selector)?.name");
lines.push("");
lines.push("    /** English fallback message (`\"Order expired\"`) for [selector], or null. */");
lines.push("    fun messageFor(selector: Selector4?): String? = entryFor(selector)?.message");
lines.push("");
lines.push("    /** Total count of mapped selectors. Exposed for SDK-parity tests. */");
lines.push("    val size: Int get() = ENTRIES.size");
lines.push("}");
lines.push("");

process.stdout.write(lines.join("\n"));
