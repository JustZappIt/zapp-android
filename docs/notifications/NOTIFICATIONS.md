# Google-free background notifications (`foss`)

How the `foss` distribution raises an Android notification when a chat message arrives while the
app is closed or backgrounded, without Google, without FCM, and without asking the user to
install a second app.

The `store` and `internal` distributions use contentless FCM topics instead, documented in
[FCM_NOTIFICATIONS.md](FCM_NOTIFICATIONS.md). The transport underneath both is documented in
[P2P-TRANSPORT-NOTES.md](P2P-TRANSPORT-NOTES.md). Start at [README.md](README.md) if you are not
sure which of the three you want.

## 1. The problem and the hard constraint

With no background push the app posts no Android notifications at all: you see messages only by
opening it. The messaging engine is a BareKit (Holepunch) JavaScript worklet running in the app
process. When the app is backgrounded the worklet's connections suspend and nothing is pulled
until you reopen the app.

The constraint for this distribution is that there must be no Google and no FCM, so the wake path
has to be P2P or self-hosted.

## 2. The core decision: embed it, no extra app

Waking a closed Android app without Google gets you three of these four properties, never all
four:

| | FCM (Google) | Separate distributor app | Embedded (our choice) | Polling |
|---|:--:|:--:|:--:|:--:|
| No Google | no | yes | yes | yes |
| No extra app to install | yes | no | yes | yes |
| No persistent "running" notification | yes | yes | no | yes |
| Instant, not on a 15 minute cycle | yes | yes | yes | no |

Zapp takes no Google, no extra app, and instant delivery, and pays for it with an opt-in "Zapp is
running" notification. This is the same trade-off every no-Google messenger makes: without FCM or
a separate distributor app, the app must keep a user-visible background component alive. The
messengers with cleaner UX here (Signal, Telegram, WhatsApp) avoid it only by using FCM. Keet,
which runs the same Holepunch stack Zapp does, ships the same persistent foreground service.

The standard UnifiedPush design puts a separate distributor app (the ntfy app) in the middle. We
rejected that. Zapp holds the ntfy connection itself, so there is nothing extra to install.

## 3. Architecture: the doorbell

The push carries no message content. It rings, and the app pulls the real, still encrypted
message over its own channel.

```
sender's message
  -> replicates to the blind peer (the self-hosted relay)
  -> blind peer reads the message's referrer, which identifies the recipient
  -> encrypted, contentless Web Push ping to that recipient's ntfy topic   [server]
ntfy delivers the ping
  -> Zapp's own foreground service (no extra app) is listening on the topic
  -> wakes the worklet -> pulls the real message -> posts a notification   [shared ChatNotifier]
```

The relay learns only that a core received new data and which identity to wake. ntfy sees an
encrypted ping to an opaque topic. Message content is decrypted only inside the app. The
persistent "Zapp is running" notification exists only because Zapp holds its own connection,
which is the price of not using Google, and it appears only after the user opts in.

When both ends are online, messages travel peer to peer and never touch the relay, so the
doorbell only matters when direct delivery fails because the recipient is suspended or offline.
That is also the only meaningful test condition for it.

## 4. Where the code lives

Shared contract, `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/push/`:

- `ChatPushBackend.kt`: the distribution-agnostic interface plus `ChatPushTopic`.
- `PushRegistrar.kt`: reconciles background-notification state after identity, conversation,
  core-key, setting, and token lifecycle changes, then delegates to the backend.

FOSS implementation, `ui-lib/src/foss/java/co/electriccoin/zcash/ui/common/push/`:

- `PushKeys.kt`: on-device EC P-256 keygen (`p256dh` and `auth`) plus a stable ntfy topic,
  persisted. The app never decrypts the ping, so the private key is discarded.
- `ChatWakeService.kt`: the `remoteMessaging` foreground service. It owns the persistent
  notification and a long-lived ntfy stream over `HttpURLConnection`, adding no dependency. On a
  ping it wakes the worklet, pulls, and notifies through the shared `ChatNotifier`.
- `ChatPushBackendImpl.kt`: registers the endpoint with the relay and starts or stops the service.
  It is a no-op when `NTFY_BASE_URL` is blank, so an unconfigured build simply has no doorbell.
- `ui-lib/src/foss/AndroidManifest.xml` declares the service, its `remoteMessaging` type, and the
  matching foreground-service permission.

Configuration and state:

- `NTFY_BASE_URL` is declared in `ui-lib/build.gradle.kts` and supplied from `gradle.properties`.
  It is the only push value compiled into `BuildConfig`.
- `IS_CHAT_NOTIFICATIONS_ENABLED` and `IS_CHAT_BACKGROUND_PUSH_ENABLED` in
  `StandardPreferenceKeys.kt` both default off. Background delivery is separate from local
  message notifications, so the persistent listener starts only after an explicit opt-in.

Engine, in the sibling `zappMessaging` repository consumed as the `:zappmessaging` subproject:

- The blind-mirror and P2P manager set a per-recipient `referrer` when registering cores, so the
  relay knows which identity to wake. Direct chats only; group cores carry no referrer.
- A dedicated identity-authenticated connection to the relay carries the
  `register-push-endpoint` RPC, exposed to Kotlin as `ZappMessagingSDK.registerPushEndpoint`.

## 5. What runs on the server

A single self-hosted blind peer alongside ntfy behind Caddy on one host. Caddy terminates TLS,
ntfy serves the topic stream, and the blind peer performs its normal relay duties plus the
doorbell: on core append or core activity it reads the referrer, debounces, and sends one
encrypted contentless Web Push per registered endpoint for that identity. The doorbell is opt-in
on the server too. Without its push configuration the same process is a plain relay, so it is
safe to deploy before enabling push.

Web Push VAPID keys are generated on the host and stay on the host. Nothing about them is
compiled into the app: the Android build learns only `NTFY_BASE_URL`, and the phone never
decrypts a ping, since receiving anything on the topic means "wake up".

Reproducible unit files and config templates are in
[deployment/blind-push/](deployment/blind-push).

## 6. What each party can see

- The relay sees encrypted Hypercore blocks it cannot read, plus the referrer identity it needs
  in order to know whom to wake, plus timing.
- ntfy sees an encrypted, contentless ping addressed to an opaque topic. The topic is generated on
  the device and is not derived from a username, identity key, contact, or wallet data.
- The device decrypts message content, and only inside the app.
- A doorbell means at most "a core may have advanced". It is not proof of delivery and it is not
  authoritative. Reconciliation over Hypercore when the app opens is what actually authenticates
  a message.

## 7. Findings worth keeping

- RFC 8291 encryption is mandatory even for an empty ping. UnifiedPush and ntfy reject
  unencrypted pushes on framing grounds, so the server encrypts the contentless ping even though
  nothing needs to decrypt it.
- Do not enable ntfy's `visitor-subscriber-rate-limiting`. It makes ntfy reject (`50701`) any
  push to a topic with no currently connected subscriber, which is exactly backwards for waking a
  dormant phone. With it disabled, ntfy caches and delivers on reconnect.
- The upstream `blind-peer` store writes a core's `referrer` on first registration and never
  merges an update into it. Zapp works around this in its own wrapper by persisting and
  re-registering referrers and keeping routing decisions at runtime. A durable library-level merge
  for rows written by older servers still belongs upstream.

## 8. Verification status

Proven link by link on a real Android phone and a live server:

| Link | Proven |
|---|---|
| App registers its endpoint with the relay | yes, the endpoint store holds the identity, topic and keys |
| Relay to ntfy delivers an `aes128gcm` Web Push | yes, delivered to a subscriber |
| `ChatWakeService` starts as a foreground service | yes, allowed from background, persistent notification shown |
| ntfy to the phone's foreground service | yes, the stream fired and ran the pull path |
| The service wakes the engine | yes |
| A backgrounded phone shows a chat notification | yes, via `ChatNotifier` |

Honest caveat: in the message test the phone was backgrounded for about 30 seconds rather than
genuinely suspended, so that message travelled directly between the two devices and the
notification came from the live worklet, not from the doorbell. Every doorbell link is proven
individually. What remains is one continuous run with the phone genuinely suspended for minutes
and a fresh conversation.

## 9. Limitations

- The doorbell depends on one operator-run host. If that host is down or unreachable, `foss`
  background notifications stop, and a message still arrives only when the app is next opened.
  This is a real centralisation point in an otherwise serverless design, and it is the honest
  cost of instant wake-ups without Google.
- Direct chats only. Group cores carry no referrer, so there is no group doorbell.
- Relay and DHT reachability degrade on some residential networks over time, which is a transport
  problem rather than a notification one. See [P2P-TRANSPORT-NOTES.md](P2P-TRANSPORT-NOTES.md).
- Android force-stop ends the service until the user launches the app again, and notification
  permission denial or channel disablement suppresses the alert.

## 10. Building and testing locally

- After editing the messaging JavaScript in `../zappMessaging`, run `npm run build:android` there
  before invoking Gradle, so the worklet bundle is rebuilt.
- Install with `./gradlew :app:installZcashtestnetFossDebug`. The package is
  `xyz.justzappit.zapp.testnet.foss.debug`.
- Use two devices on the same build, re-onboarding both, since a stale sender fails to
  interoperate silently. Only a physical phone is a meaningful wake target, because emulators do
  not suspend. Use the emulator as the sender.
- Set `NTFY_BASE_URL` to your own ntfy deployment to test the doorbell against a host you control.
