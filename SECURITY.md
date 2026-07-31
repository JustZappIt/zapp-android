# Security Policy

Zapp is a cryptocurrency wallet. We take security reports seriously and
appreciate responsible disclosure.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report Zapp-specific security issues privately via GitHub's
["Report a vulnerability"](https://github.com/JustZappIt/android-zapp/security/advisories/new)
(Security advisories) on this repository.

For vulnerabilities in upstream Zcash / Zodl / the Zcash Android SDK, please
follow the upstream
[Responsible Disclosure guidelines](https://github.com/zodl-inc/zodl-project/blob/master/responsible_disclosure.md).
See also [docs/responsible_disclosure.md](docs/responsible_disclosure.md).

## Scope

In scope: the Zapp app code in this repository, including the wallet, P2P
messaging integration, and on/off-ramp flows.

Out of scope: third-party services (p2p.me, lightwalletd operators), the
upstream Zcash Android SDK, and the Bare/Hyperswarm runtime. Please report those
to their respective maintainers.

## Known limitations

We review this fork for security issues and track the results privately. The
list below covers every finding we currently rate as high severity that is
still open, described so you can judge the risk before trusting the app with
funds or with sensitive conversations. We are deliberately not publishing file
locations or reproduction steps while the fixes are outstanding. All of these
are known, tracked, and being worked on.

- **Deleting a chat identity does not erase everything.** Key material and
  message history can survive on disk after the delete, so conversations you
  believe are gone may still be recoverable. Threat model: someone with
  physical access to the unlocked device or to a device backup.
- **The offramp does not route over Tor.** Enabling Tor in settings does not
  currently cover the offramp's HTTP traffic, so offramp activity and the IP
  address behind it stay visible to your network operator and to the offramp
  service. Threat model: remote network observer, no device access needed.
  This is a privacy exposure, not a loss-of-funds issue.
- **A reverted offramp transaction can be shown as successful.** A smart
  account operation that failed on chain can still be reported to the app as a
  success, so the status you see may not match what settled. Threat model: no
  attacker required, this is a correctness bug. Confirm on chain before
  treating an offramp payment as final.
- **The PIN lockout is weaker than the PIN.** The failed-attempt counter is
  stored with less protection than the PIN itself and is evaluated against
  wall-clock time, so the delay that is supposed to slow down repeated guessing
  can be defeated. Threat model: someone in physical possession of the device.
- **Biometric unlock is not bound to a keystore operation.** The biometric
  prompt gates the screen rather than being cryptographically tied to a
  keystore-backed operation, which weakens it against an attacker who already
  controls the device. Threat model: physical possession plus a compromised or
  rooted device. This one is inherited from upstream and is not specific to
  this fork.

Reports that rediscover one of the above are still welcome, and reports of
anything not on this list are especially welcome.

## Handling of secrets

This repository contains **no production** secrets, keys, or credentials. All
sensitive build inputs are read from `local.properties` (git-ignored) or CI
secrets. See [docs/RELEASE.md](docs/RELEASE.md) for the release-build preflight.

The one private key committed to the tree is a throwaway testnet key in
`DevOfframpAccountProvider`, used only when `OFFRAMP_USE_DEV_KEY=true`. That flag
ships `false` and must never be set for a release build: it would derive every
user's offramp account from this shared key. The key is public by construction
and holds nothing of value.
