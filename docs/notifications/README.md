# Background chat notifications

Upstream Zcash wallets ship no chat, so they ship nothing here. Zapp's push is a doorbell, not a
mail carrier: the push payload carries no message content. It rings, the app wakes, and the app
pulls the still end-to-end-encrypted message over its own Hypercore channel. Nothing readable
ever passes through a push service.

There are two doorbells because there are two distributions.

| Distribution | Doorbell | Read |
|---|---|---|
| `store`, `internal` | Contentless FCM topic, no foreground service | [FCM_NOTIFICATIONS.md](FCM_NOTIFICATIONS.md) |
| `foss` | Self-hosted ntfy topic, Zapp's own foreground service, no Google dependency | [NOTIFICATIONS.md](NOTIFICATIONS.md) |

Both paths share the contract in `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/push/`
(`ChatPushBackend`, `PushRegistrar`), the same two preferences
(`IS_CHAT_NOTIFICATIONS_ENABLED` and `IS_CHAT_BACKGROUND_PUSH_ENABLED`, both default off), and
the same locally packaged notification text from `ChatNotifier`. The distribution-specific
implementations live in `ui-lib/src/google/java/.../push/` and `ui-lib/src/foss/java/.../push/`.

Also here:

- [P2P-TRANSPORT-NOTES.md](P2P-TRANSPORT-NOTES.md) is the transport reference for the layer
  underneath both doorbells: Hyperswarm, HyperDHT, and the blind peer that stores encrypted
  blocks for offline recipients. A doorbell can only ring for data that reached the relay, so
  when a notification never arrives, start there.
- [deployment/blind-push/](deployment/blind-push) holds the reproducible server unit files and
  config templates for the relay and the push gateway.
