# P2P transport notes

Zapp chat has no message server. Each conversation is a set of append-only Hypercore logs, one
writable log per participant, replicated directly between devices over Hyperswarm and HyperDHT
inside a BareKit JS worklet that runs in the app process. The engine lives in the sibling
`zappMessaging` repo and is consumed as the `:zappmessaging` Gradle subproject. Two devices that
can reach each other exchange blocks over a direct UDP connection and nothing else is involved.
Two devices that cannot, either because NAT traversal fails or because one of them is simply not
running, need a third party that is always online and always reachable. That is the blind peer: a
public-IP node that stores encrypted Hypercore blocks indexed by discovery key, cannot read them,
and hands them over when the other side asks. It gives the transport two things it otherwise
lacks, asynchronous delivery to an offline recipient and a relay path when holepunching fails.
Offline delivery through the blind peer has been verified end to end on real hardware: the sender
queued messages while the receiver was force-stopped, the blind peer's storage grew, and the
messages appeared when the receiver relaunched.

For how a notification gets raised once blocks land on the blind peer, see
[`NOTIFICATIONS.md`](NOTIFICATIONS.md). For server-side deployment, see
[`deployment/blind-push/README.md`](deployment/blind-push/README.md).

## How a device reaches the blind peer

```
[device]                                         [blind peer, public IP]
   |                                                        |
   | 1. swarm.dht looks the blind peer's pubkey up on the   |
   |    bootstrap nodes (node1/2/3.hyperdht.org, plus any   |
   |    custom bootstrap the build ships)                   |
   | 2. bootstrap returns the address recorded the last     |
   |    time the blind peer talked to them                  |
   | 3. device sends UDP to that address, Noise handshake   |
   v                                                        v
        cellular CGNAT or Wi-Fi NAT  ->  internet  ->  cloud firewall
```

The blind peer is identified by its z32 public key, never by an IP. Clients look the key up and
connect to whatever address the DHT has for it. That is why one key works for every user, provided
the peer is reachable from arbitrary networks, which in practice means a public IP.

Failure points along that path, in the order they actually bite:

- The device's NAT is symmetric (CGNAT, most cellular, phone hotspots). Outgoing UDP gets a
  per-destination mapping, HyperDHT cannot predict it, holepunching fails.
- The relay host is behind a consumer NAT. Bootstrap nodes record a mapping that nothing else can
  use, so fresh clients find an address that drops their packets.
- The relay host default-routes through a VPN. Bootstrap nodes then record the VPN provider's exit
  address, clients send UDP there, and the provider has no rule pointing back at the tunnel client.
  Always disable the VPN before starting a relay for testing.

## Configuration wiring

Four build properties reach the worklet. Each resolves `local.properties`, then the Gradle property
(`-P` or `gradle.properties`), then the environment variable, then empty. Empty means the feature
is simply off, which is the pre-wiring behaviour.

| Property | Effect when set |
|---|---|
| `BLIND_PEER_KEYS` | Comma-separated z32 keys. `BlindMirror` registers cores with each of them. Empty disables blind mirroring entirely. |
| `BLIND_PEER_BOOTSTRAP` | `host:port` appended to the default Holepunch bootstrap list, so a fresh install's routing table already contains a node that knows the blind peer's announce record. |
| `BLIND_PEER_ADDRESS` | `host:port` passed separately so HyperDHT can try the server immediately while keeping normal lookup fallback. |
| `ZAPP_MESSAGING_LOG_LEVEL` | `debug` turns on verbose worklet diagnostics, including keypair dumps and the DHT probes below. Ships as blank or `info`. |

```
gradle.properties / local.properties
  -> zappMessaging/android/build.gradle.kts reads at configuration time
  -> BuildConfig.BLIND_PEER_KEYS (and siblings)
  -> BareWorkletManager appends --blind-peer-keys= / --bootstrap-nodes=
     / --blind-peer-address= / --log-level= to the worklet argv
  -> core/lib/config.js parses Bare.argv on require
  -> p2p-manager.js builds the Hyperswarm, blind-mirror.js builds the BlindMirror
```

The keys and addresses are public values and are committed. Verify a hop landed by grepping
`Relay through blind peer` and `Bootstrap nodes: N` in the device's `p2p-diag.log`, and
`BlindMirror ready with N blind peer(s)` in `blind-mirror-diag.log`.

## relayThrough: static form, not function form

`p2p-manager.js` passes `relayThrough` as a static key buffer. Hyperswarm then relays only when it
has a reason to: `peerInfo.forceRelaying` after a holepunch error code, or `swarm.dht.randomized`.
That is the correct production behaviour, because direct connections are lower latency than relayed
ones.

The function form (`relayThrough: () => relayKey`) forces every connection through the relay. It
exists in the history of this transport because during early debugging the static form never fired:
devices reported `firewalled=true addr=none bootstrapped=true`, which leaves `dht.randomized` false,
and the connect attempts did not always produce a relay-eligible error code. Use the function form
only as a temporary debugging device, and never ship it.

## Identity keypair vs ephemeral DHT keypair

Hyperswarm constructs its DHT without passing its own `keyPair`, so `dht.defaultKeyPair` is a fresh
random keypair generated at app start. Anything connecting through `dht.connect()` without an
explicit keypair therefore presents that ephemeral key at the Noise layer, not the user's identity.
This is why blind peer logs used to show a different z32 for the same phone on every app boot, and
why the blind peer's `--trusted-peer` allowlist could not be populated with identity keys.

`BlindMirror` now sets `this._peering.keyPair = this.swarm.keyPair` before any blind peer client is
created, so the blind peer sees the identity public key. That is what makes identity-scoped features
work: `--trusted-peer` matches real identities, announces are not downgraded, and the push referrer
(see [`NOTIFICATIONS.md`](NOTIFICATIONS.md)) can address a recipient by identity.

## Reading the on-device diagnostics

All paths are inside the app's private files dir, reachable with `run-as <package>`.

| Path | Content |
|---|---|
| `files/zappmessaging/diag.log` | Worklet startup, module load, identity init. |
| `files/zappmessaging/p2p-diag.log` | Hyperswarm state, the 10s `SWARM` dump, relay and bootstrap lines, `joinConversation`, `Direct send`. |
| `files/zappmessaging/blind-mirror-diag.log` | `BlindMirror ready`, `Registered local core`, `Registered remote core`, the `STATE` dump, the `PROBE` lines. |
| `files/zappmessaging/hypercore-diag.log` | `Corestore ready`, per-conversation core keys, `Appended ... newLen=`. |
| `cache/zappmessaging_diag.log` | IPC bridge events, NDJSON traffic. |

The `SWARM` line is emitted every 10 seconds:

```
SWARM conns=0 peers=0 addr=none localAddr=... dhtReady=true bootstrapped=true
      firewalled=true port=49737 convs=1 globalConns=0 inviteTopics=0
```

| Field | Healthy | Meaning when it is not |
|---|---|---|
| `port` | `>0` | UDX socket bound. `0` means the native addon failed to load, usually a jniLibs ABI mismatch. |
| `dhtReady` | `true` | `dht.ready()` resolved. |
| `bootstrapped` | `true` | The DHT got initial routing info from the bootstrap nodes. |
| `firewalled` | `false` preferred | The DHT's STUN-like detection thinks it can accept incoming UDP. `true` is common on mobile and is not by itself fatal. |
| `addr` | `<ip>:<port>` | DHT-detected reachable address. `none` means detection never settled. |
| `conns` | matches expected peers | `swarm.connections.size`. Blind peer connections do NOT increment this. |
| `convs` | one per joined conversation | Incremented by `joinConversation`. |
| `inviteTopics` | one per pending invite | Topics joined for invite delivery. |

`conns=0` next to a working blind peer is expected, not a bug. `BlindPeering` calls
`swarm.dht.connect()` directly through `BlindPeerClient`, which never fires the swarm's public
`connection` event, so the blind peer session lives outside `swarm.connections`. The
`STATE ... connected= channel= cores=` line in `blind-mirror-diag.log` is the authoritative view of
whether a blind peer session is up right now.

## Probing the DHT correctly

With `ZAPP_MESSAGING_LOG_LEVEL=debug`, `BlindMirror` runs a probe every 20 seconds and logs
`PROBE dht rtNodes=`, `PROBE findPeer(...) responders=`, `PROBE lookup(...) responders=` and
`PROBE ping(host:port)`.

One trap is worth stating explicitly, because it produced a whole session of false conclusions. The
connect path in `hyperdht/lib/connect.js` derives its DHT target as `hash(publicKey)`, and the
server announces under `hash(publicKey)` too. A probe written as
`dht.findPeer(rawPublicKey, { hash: false })` therefore queries a completely different coordinate in
the keyspace and returns zero responders on every device, on every network, forever. Let `findPeer`
hash the key (the default) so the probe walks the same coordinate the connect path uses. Any
`responders=0` reading taken with `hash: false` means nothing.

`PROBE ping` timing out against the blind peer's `host:port` is also not by itself a fault. A cloud
firewall may allow the DHT's holepunched flows while dropping arbitrary unsolicited inbound UDP, in
which case a custom bootstrap entry still works because HyperDHT walks through the node rather than
pinging it directly.

## Known transport failure modes

**Residential Wi-Fi degrades the DHT over time.** A device bootstraps cleanly (`rtNodes` in the
hundreds, `connected=true`) and then, hours later, sits at `rtNodes=0 online=false` with repeated
`PEER_NOT_FOUND`. The cause is the router expiring the UDP NAT mapping after a period of low
traffic. Once the mapping is gone the device falls off the DHT and cannot refresh its routing
table. This is not device-specific: it was originally misdiagnosed as one phone being broken, and
the same phone was later observed healthy and then degraded within a single session. Emulators do
not show it because QEMU's userspace NAT is far more permissive, which makes an emulator a
misleading test target for reachability work. Restarting the app re-bootstraps a healthy DHT.
Durable fixes, none of them implemented yet: a UDP keepalive to the blind peer on the order of 20
seconds, LAN peer discovery as a DHT-independent fallback, or a sticky channel to the blind peer
that does not depend on the UDP mapping surviving.

**Double NAT kills the DHT outright.** A device tethered to another phone's hotspot sends UDP
through the hotspot NAT and then the carrier NAT. DHT request and reply pairs do not survive that,
so the routing table never populates from any bootstrap node, including a custom one, and every
probe returns zero. The giveaway is that ICMP to a bootstrap host succeeds while every UDP probe
fails. There is no code fix. Consumer setups with a guest network or a mesh repeater can produce
the same shape while both devices look like they are on one SSID, so check the actual topology
before blaming a device.

**Client isolation blocks device-to-device LAN sync.** Many access points refuse to forward traffic
between clients ("AP isolation", "client isolation", "privacy separator"), and Android's built-in
hotspot does this by default. Both devices then show `conns=0 peers=0 addr=none` even on the same
subnet, and no code change can help, because the frames never reach layer 2 on the other side. Most
guest networks and most public Wi-Fi have it on.

## What the blind peer cannot fix

The blind peer mirrors conversations that already exist. It cannot bootstrap new ones. Invite
delivery still runs over direct swarm sockets in `sendInvite`, and the `__core_keys` exchange that
teaches two peers each other's Hypercore keys happens over a live direct connection in
`handleConnection`. So if two devices have never reached each other directly, the blind peer can
hold both their encrypted cores and neither side will know which core key to ask for. First contact
still needs holepunchable or same-LAN reachability at least once. After that the pair can separate
and stay in sync through the blind peer indefinitely. Lifting invite and core-key exchange onto a
Hypercore-based metadata channel is the real fix and has not been done.

## Operating a blind peer

- Use Ubuntu 22.04 or newer. Enterprise distributions (RHEL, Rocky, Oracle Linux) ship
  `GLIBCXX_3.4.29` while the Holepunch native prebuilds require `GLIBCXX_3.4.30` or newer. A
  `gcc-toolset` package does not fix this, it ships headers rather than a runtime `libstdc++`.
  Patching around it is a dead end.
- On a 1 GB instance, add swap before installing. The dependency install OOMs without it.
- The peer's public key is derived from `<storage>/IDENTITY`. Back that file up. Deleting the
  storage directory rotates the key and breaks every client until they ship a build with the new
  key.
- Always bound storage. The peer accepts core registrations from anyone, so without a cap anyone can
  fill the disk. Note that the CLI parses the value with `parseInt`, so `10gb` silently becomes 10
  and the peer starts garbage-collecting almost immediately. Pass a plain number.
- Open the peer's UDP port in both layers, the cloud firewall and the host firewall, and check rule
  ordering. An ACCEPT rule placed after a catch-all REJECT does nothing.
- A single peer is a single point of failure. The Holepunch reference design ships a small fleet of
  well-known keys in the client and lets `BlindPeering` pick the closest few. `BLIND_PEER_KEYS`
  already accepts a comma-separated list for that reason.

**Is exposing the peer's UDP port to the internet dangerous?** Not meaningfully, with the usual
caveats. It is UDP, so most commodity scanning and exploit tooling does not apply. The protocol
requires a Noise handshake, so an unauthenticated scanner gets a rejection rather than a session.
The peer cannot read what it stores, since cores are encrypted and indexed by discovery key. The
realistic attack is storage exhaustion, which is what the storage cap is for. Beyond that: open only
the one port inbound, run the peer as a non-root user under systemd, and disable SSH password
authentication.

## Glossary

| Term | Meaning here |
|---|---|
| Hyperswarm | Peer discovery and connection layer. `swarm.join(topic)` announces and looks up peers on a topic. |
| HyperDHT | The DHT under Hyperswarm. Peer lookup, NAT traversal, and the relay-through mechanism. |
| Bare | Holepunch's stripped-down JS runtime for mobile (BareKit). The worklet is JS packed by `bare-pack`. |
| Blind peer | A node that stores encrypted Hypercore blocks for offline delivery. Cannot read them, only stores blocks indexed by discovery key. |
| BlindPeering | The client library that registers Hypercores with blind peers and replicates to them. |
| Hypercore | Append-only signed log. Each conversation has one writable log per participant. |
| Discovery key | `crypto.discoveryKey(corePublicKey)`. The DHT lookup key for a core, derived but not invertible, so lookups do not leak the core's identity. |
| Holepunching | UDP NAT traversal coordinated by HyperDHT through the bootstrap nodes. Works for cone NATs, fails for symmetric ones. |
| Cone NAT | Maps an internal address and port to one external port regardless of destination, so incoming traffic from any source on that port is delivered. |
| Symmetric NAT | Maps differently per destination. Mappings cannot be predicted, so holepunching fails. CGNAT and most cellular behave this way. |
| CGNAT | Carrier-grade NAT. One public IP shared across many subscribers. Almost always symmetric. |
| Hairpin NAT | The router rewrites a packet sent from inside to its own public address back to the correct internal client. Many consumer routers and phone hotspots do not. |
| `relayThrough` | Hyperswarm option naming a pubkey to relay through. Static value relays only on holepunch failure, function value relays always. |
| z32 | Holepunch's base32 alphabet. A 52-character encoding of a 32-byte public key, used for blind peer keys and peer identities. |
