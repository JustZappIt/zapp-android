# FCM background chat notifications

Status: implementation and automated verification in progress. Production Firebase credentials and the physical-device end-to-end gate are intentionally not part of this repository.

This is the architecture for the `store` and `internal` distributions. The `foss` distribution has no Firebase dependency and uses the self-hosted ntfy doorbell in [NOTIFICATIONS.md](NOTIFICATIONS.md) instead. [README.md](README.md) routes between the two, and [P2P-TRANSPORT-NOTES.md](P2P-TRANSPORT-NOTES.md) covers the transport underneath both.

## Trust and data flow

```text
sender's encrypted per-conversation Hypercore
  -> blind-peering.sendNotification(core, exact appended index)
  -> blind-peer creates a proof-backed blind-push payload
  -> blind-push-gateway sends high-priority topic FCM
  -> Android posts the existing generic private notification
  -> notification tap opens Zapp and normal Hypercore sync obtains the message
```

FCM is a doorbell, not message transport or proof of delivery. Messages, media, history, receipts, and authoritative state remain in encrypted Hypercores. `FirebaseMessagingService` does not start Bare, Hyperswarm, replication, a foreground service, or an ntfy connection.

For a direct conversation, the JavaScript engine derives the inbound FCM topic from the remote writer Hypercore's random discovery key. The sender uses the corresponding local writer core. Kotlin receives complete topic snapshots over IPC and never derives topics itself. Topics are not derived from a username, identity public key, contact, deterministic conversation ID, or wallet data.

The gateway's Android data envelope contains only its configured generic `title` and `body` plus a base64 blind-push payload. Android validates the sender topic, exact field set, field sizes, topic format, and payload encoding, then ignores all payload display text. The locally packaged `ChatNotifier` supplies the visible generic text.

FCM can observe the app installation, opaque topic membership, and timing. It does not receive Zapp identities, contact or conversation names, message/media plaintext, wallet data, or conversation encryption keys. Topic possession is a notification capability, but it does not permit a subscriber to decrypt or forge a Hypercore message.

## Subscription and notification policy

- `store` and `internal`: enabling background alerts subscribes to ready direct-chat topics. Disabling either chat notifications or background alerts unsubscribes every known topic.
- `foss`: the same background-alert preference controls the existing explicitly opt-in ntfy foreground service. FOSS contains no Firebase implementation or dependency.
- The existing preference key is retained. An existing opt-in deterministically becomes FCM topic delivery on Google builds and remains ntfy delivery on FOSS; the default remains off.
- The previous subscribed set and topic-to-conversation bindings are persisted. Reconciliation adds missing topics and removes stale topics after startup, identity restoration, remote-core exchange, conversation removal, settings changes, and token refresh.
- Full topics are never logged. A removed topic loses its local routing binding before unsubscribe is attempted, so a delayed stale doorbell is ignored.
- Unknown well-formed topics are ignored. Malformed envelopes are ignored. Neither case starts the messaging engine.
- Notification permission denial, app-level notification disablement, channel disablement, blocked writers, and an actively viewed foreground conversation suppress the alert.
- Notifications use the conversation ID as their Android tag, so repeats for one conversation collapse and taps route to the known conversation.

Groups are not supported. The snapshot explicitly reports `supportsGroups=false`, group topics are not subscribed, and group sends do not request push. Supporting groups requires distribution and rotation of every writer capability plus tested revocation on membership changes.

## Firebase Android registrations

FOSS application IDs must not be registered for this feature. Register all shipped `store` and `internal` IDs used by the chosen build matrix:

| Network / distribution | Release | Debug |
|---|---|---|
| Mainnet store | `xyz.justzappit.zapp` | `xyz.justzappit.zapp.debug` |
| Testnet store | `xyz.justzappit.zapp.testnet` | `xyz.justzappit.zapp.testnet.debug` |
| Mainnet internal | `xyz.justzappit.zapp.internal` | `xyz.justzappit.zapp.internal.debug` |
| Testnet internal | `xyz.justzappit.zapp.testnet.internal` | `xyz.justzappit.zapp.testnet.internal.debug` |

The build already tolerates absent Firebase files. To enable a build type, supply its untracked `app/src/<buildType>/google-services.json` containing the matching client; a phone integration test only needs `app/src/debug/google-services.json`. Never commit these files or the gateway service-account JSON.

## Android semantics

- Backgrounding or swiping Zapp from recents does not normally prevent FCM data delivery.
- High-priority FCM can alert during device lock and Doze, subject to Android and Google delivery policy.
- Android force-stop disables delivery until the user launches Zapp again.
- Notification permission denial or channel disablement prevents visible alerts.
- A device without working Google Play services needs the FOSS/ntfy fallback; a Google build must not claim background delivery there.
- Receiving a doorbell only means a compatible writer core may have advanced. App-open Hypercore reconciliation remains authoritative.

## Reconciliation timing budget

Notification reconciliation emits process-local, sanitized milestones under the
`ChatNotificationTiming` marker. A trace contains only a phase name, elapsed
milliseconds, and its timing origin. It never contains topics, identities,
conversation IDs or names, payloads, keys, or message data.

The provisional healthy-network physical-device budget is:

| Interval | Budget |
|---|---:|
| Validated FCM receipt to local notification post | 100 ms |
| Notification tap to messaging SDK initialized | 1.5 s |
| Notification tap to first authentic target-conversation message | 10 s |

Initial swipe-away cold-start measurements on a OnePlus CPH2747 over Wi-Fi were
5.73 s and 7.15 s from tap to authentic message. SDK initialization completed in
0.72 s and 0.73 s respectively; the remaining 5.01 s and 6.43 s were spent in
shared-engine/network reconciliation. One clean send produced one validated FCM
callback, one local notification post in 9 ms, and no Bare, Hyperswarm, mirror,
ntfy listener, or foreground service activity before the tap.

These two samples establish an initial regression budget, not a completed
release matrix. Record cold and warm starts, swipe-away, lock/Doze, process
restart, different networks, and small and large conversation lists before
declaring the physical-device gate complete. A run outside the budget needs a
sanitized trace and either a fix or a focused shared-engine follow-up.

## Rollout and rollback

Keep the deployed ntfy, Caddy, old blind-peer wrapper, and its configuration during FCM validation. Start the push gateway first, record its stable public key, then schedule the blind-peer process change that supplies that key to upstream `blind-peer`. Do not run two blind-peer processes against the same storage concurrently.

Before the blind-peer switch, stop the old unit, make a storage/config backup, start the pinned FCM-capable unit, and verify its stable public key and replication. Rollback is: stop the new unit, restore the previous unit/config and storage only if migration damaged it, restart the old wrapper, and leave ntfy clients enabled. Do not remove ntfy until the physical-device matrix below passes and a rollback window has elapsed.

Manual VPS instructions and systemd templates are in `deployment/blind-push/README.md`.

## Physical-device release gate

1. Install the same compatible build on a sender and receiver and create a fresh direct conversation.
2. Verify remote core exchange and topic reconciliation without printing full topics.
3. Background or swipe away the receiver and confirm no Zapp foreground service or persistent running notification exists.
4. Lock the receiver and force Doze where practical.
5. Send one message and confirm, in order: sender append, blind-peer replication, notification request, gateway FCM success, and one generic Android alert.
6. Tap the alert and verify normal app-open sync obtains and authenticates exactly one Hypercore message.
7. Repeat after restart and practical token refresh/reinstall, with notifications disabled, with the sender blocked, while viewing the conversation, and after force-stop.

Production remains blocked until this end-to-end gate passes with real credentials. Current intentional limitations are direct chats only, no Google-free push without the persistent FOSS listener, normal Android force-stop behavior, and reliance on the configured blind-peer/gateway availability.
