# Oracle VPS blind-push deployment

These files prepare a manual, reproducible deployment. They do not authorize or perform a live VPS change.

Pinned components:

- Runtime: Node.js 22 or newer
- Android sender: `blind-peering` 2.4.1
- Oracle relay: `blind-peer` 3.12.2
- Connection relay: `blind-relay` 1.6.1
- Oracle gateway: `blind-push-gateway` 0.3.0
- External transport: Firebase Cloud Messaging

The gateway and blind peer are separate systemd processes. The blind-peer
wrapper also serves HyperDHT's `blind-relay` Protomux protocol and the bootstrap
invite mailbox on the same Noise connections, so two firewalled peers can
exchange invites and core keys. The Firebase service account stays on the VPS and
outside git. The existing ntfy/Caddy deployment stays installed during rollout.

`vendor/` holds the relay and mailbox modules, copied verbatim from
zappMessaging so the VPS can install this directory with `npm ci` alone. They are
generated, not authored: change the originals in zappMessaging and run
`node scripts/vendor-blind-push-server.js`. CI fails the PR when they drift from
the commit pinned in `.zapp-deps`, or when the shared dependency versions in the
two `package.json` files disagree.

Run `npm audit --omit=dev` on every deployment review. As of 2026-07-29,
the pinned lockfile reports seven moderate and five high transitive advisories,
with no critical advisories. The high findings are in the Firebase/Google
dependency tree; adding `blind-relay` does not introduce another resolved
package. Treat this pre-1.0 gateway as integration-only until those findings,
its resolved dependency tree, and credential scope have passed production
security review.

## Install without enabling traffic

Run as an authorized VPS administrator from a reviewed copy of this directory:

```bash
node --version # must report v22 or newer
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin zapp-push
sudo install -d -o root -g root -m 0755 /opt/zapp-blind-push
sudo install -d -o root -g zapp-push -m 0750 /etc/zapp-blind-push
sudo install -d -o zapp-push -g zapp-push -m 0700 /var/lib/zapp-blind-push-gateway
sudo install -d -o zapp-push -g zapp-push -m 0700 /var/lib/zapp-blind-peer
sudo install -o root -g root -m 0644 package.json package-lock.json blind-peer-server.js /opt/zapp-blind-push/
sudo install -d -o root -g root -m 0755 /opt/zapp-blind-push/vendor
sudo install -o root -g root -m 0644 vendor/*.js /opt/zapp-blind-push/vendor/
cd /opt/zapp-blind-push
sudo npm ci --omit=dev
sudo install -o root -g root -m 0644 zapp-blind-push-gateway.service /etc/systemd/system/
sudo install -o root -g root -m 0644 zapp-blind-peer.service /etc/systemd/system/
```

Copy the Firebase Admin service-account JSON through an approved secret channel, then restrict it:

```bash
sudo install -o zapp-push -g zapp-push -m 0600 /secure/staging/service-account.json /etc/zapp-blind-push/firebase-service-account.json
sudo install -o root -g zapp-push -m 0640 gateway.json.example /etc/zapp-blind-push/gateway.json
sudo systemctl daemon-reload
sudo systemctl enable --now zapp-blind-push-gateway
sudo journalctl -u zapp-blind-push-gateway -n 50 --no-pager
```

The gateway public key is stable in `/var/lib/zapp-blind-push-gateway`. Put that key in a copy of `blind-peer.json.example`; do not put credentials, app identities, topics, or payloads in that file or the journal.

## Scheduled blind-peer switch

The old and new blind-peer processes must never share the same storage concurrently. Keep the ntfy and Caddy services installed and running. During an approved maintenance window:

```bash
sudo systemctl stop blind-peer
sudo tar -C /var/lib -czf /root/zapp-blind-peer-pre-fcm.tgz zapp-blind-peer
sudo install -o root -g zapp-push -m 0640 blind-peer.json.example /etc/zapp-blind-push/blind-peer.json
sudo systemctl enable --now zapp-blind-peer
sudo journalctl -u zapp-blind-peer -n 50 --no-pager
```

Confirm that the new process reports the same expected blind-peer public key,
accepts mirroring, opens a `blind-relay` channel, logs the invite mailbox HTTP
address, and forwards a test notification without logging a topic or payload. If
storage currently lives elsewhere, set the real absolute path in
`blind-peer.json`; do not silently start with an empty relay identity.

Open/retain the existing UDP DHT port in both the host firewall and Oracle security list. FCM uses outbound HTTPS from the gateway; no inbound Firebase port is required.

## Publishing the invite mailbox through Caddy

The mailbox binds to `127.0.0.1:49739` and Caddy publishes it. The app is
configured with `INVITE_MAILBOX_URL`, and posts to `<that URL>/put`, `/list` and
`/ack`.

**Caddy has to strip the path prefix.** The server matches the request path
against exactly `put`, `list` and `ack`. Forwarding `/zapp-invite/put` unstripped
makes every request 404; the client then falls back to the DHT mailbox and
firewalled peers fail exactly as they did before any of this existed, with no
error pointing at the proxy.

```caddyfile
handle_path /zapp-invite/* {
    reverse_proxy 127.0.0.1:49739
}
```

`handle_path` strips the matched prefix. Plain `handle` does not. Verify after
every Caddy change:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -X POST -H 'content-type: application/json' -d '{}' \
  https://ntfy.140.245.193.100.sslip.io/zapp-invite/put
```

400 means the route reached the mailbox and the empty body was rejected on its
merits, which is the healthy answer. 404 means the prefix is still being
forwarded and the mailbox is unreachable. Do not leave 49739 open in the host or
Oracle firewall; it is loopback-only by design.

### Mailbox settings in `blind-peer.json`

All optional; the defaults below are what ships.

| Key | Default | Purpose |
| --- | --- | --- |
| `inviteMailboxDir` | `<storage>/invite-mailbox` | One JSON file per recipient |
| `inviteMailboxHttpPort` | `49739` | Loopback HTTP listener |
| `inviteMailboxHttpHost` | `127.0.0.1` | Leave on loopback |
| `maxInvitesPerRecipient` | `100` | Ceiling per mailbox |
| `maxInvitesPerSender` | `8` | Stops one sender consuming a mailbox |
| `maxInvitesTotal` | `10000` | Ceiling across all mailboxes |
| `maxInviteBytesPerRecipient` | `262144` | Byte budget per mailbox |
| `maxInviteRequestsPerMinute` | `600` | Aggregate HTTP request ceiling |

Deposits are necessarily open: a sender cannot authenticate to the mailbox of
someone who has never been online. What bounds the damage is the 16 KB envelope
cap, the quotas above, paged listings that keep a flooded mailbox drainable
instead of unreadable, and the aggregate request ceiling. The per-recipient
budget counts bytes rather than entries, because the envelope cap has to hold a
group invite carrying every participant's key; bounding entries alone would let
one large invite cost the same as one small one. Envelope bodies are verified but
opaque; the server does learn which identity is writing to which, which is
routing metadata the mailbox cannot avoid.

The request ceiling is deliberately aggregate, not per client address: only the
reverse proxy sees a real client address, so per-address limiting belongs in
Caddy. Add it there before treating this endpoint as production infrastructure.
A determined attacker holding many keypairs can still keep a *known* identity's
mailbox at its quota; the guarantee is that the mailbox stays drainable and the
DHT mailbox stays available, not that flooding is impossible.

## Rollback

```bash
sudo systemctl disable --now zapp-blind-peer
sudo systemctl enable --now blind-peer
sudo journalctl -u blind-peer -n 50 --no-pager
```

Restore the backup only if the existing storage was damaged; normally both launchers use compatible `blind-peer` storage and rollback should reuse it. Leave `zapp-blind-push-gateway`, ntfy, and Caddy available until the device matrix is complete. Remove the gateway or ntfy only in a later separately approved change.

## Operational checks

- Credential and config permissions remain 0600/0640 and readable only by `zapp-push`.
- Journals contain no service-account contents, full FCM topics, blind-push payloads, Zapp identities, or message data.
- The global notification request cap is configured to an expected load and rate-limit failures are monitored.
- Gateway and blind-peer public keys remain stable across restart because their storage directories persist.
- The physical-device procedure in `../../FCM_NOTIFICATIONS.md` passes before production rollout is declared complete.
