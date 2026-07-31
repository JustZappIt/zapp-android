#!/usr/bin/env node
'use strict'

const fs = require('fs')
const path = require('path')
const BlindPeer = require('blind-peer')
const Id = require('hypercore-id-encoding')
// Vendored from zappMessaging; see vendor/ and scripts/vendor-blind-push-server.js.
// The mailbox protocol has to match the client exactly, so the implementation
// lives with the client and is copied here rather than reimplemented.
const { attachBlindRelay } = require('./vendor/blind-relay')
const { attachInviteMailbox } = require('./vendor/invite-mailbox')

const configPath = path.resolve(process.argv[2] || '/etc/zapp-blind-push/blind-peer.json')
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))

if (!path.isAbsolute(config.storage || '')) throw new Error('storage must be an absolute path')
if (!Array.isArray(config.pushGatewayKeys) || config.pushGatewayKeys.length === 0) {
  throw new Error('at least one pushGatewayKey is required')
}

const maxNotificationsPerMinute = positiveInteger(config.maxNotificationsPerMinute, 120)
let windowStartedAt = Date.now()
let notificationsInWindow = 0

const blindPeer = new BlindPeer(config.storage, {
  port: positiveInteger(config.port, 49737),
  maxBytes: positiveInteger(config.maxBytes, 10_000_000_000),
  trustedPubKeys: [],
  pushGatewayKeys: config.pushGatewayKeys.map(Id.decode)
})

// blind-peer 3.12.2 has no notification rate option. The exact package is
// pinned above, so this bounded wrapper can rate-limit the existing upstream
// notification handler without changing its protocol or payload.
const forwardNotification = blindPeer._onnotification.bind(blindPeer)
blindPeer._onnotification = async function (stream, request) {
  const now = Date.now()
  if (now - windowStartedAt >= 60_000) {
    windowStartedAt = now
    notificationsInWindow = 0
  }
  if (notificationsInWindow >= maxNotificationsPerMinute) {
    throw new Error('notification rate limit exceeded')
  }
  notificationsInWindow++
  return await forwardNotification(stream, request)
}

blindPeer.on('notification-sent', () => process.stdout.write('notification forwarded\n'))
blindPeer.on('notification-error', () => process.stderr.write('notification forwarding failed\n'))
blindPeer.on('flush-error', () => process.stderr.write('blind-peer flush failed\n'))

let closing = false
let connectionRelay = null
let inviteMailbox = null

async function close () {
  if (closing) return
  closing = true
  if (inviteMailbox) await inviteMailbox.close()
  if (connectionRelay) await connectionRelay.close()
  await blindPeer.close()
}

process.once('SIGINT', () => close().finally(() => process.exit(0)))
process.once('SIGTERM', () => close().finally(() => process.exit(0)))

async function main () {
  await blindPeer.ready()

  // Stock blind-peer serves core replication and push RPCs. HyperDHT's
  // relayThrough option needs the separate blind-relay Protomux protocol, and a
  // peer with no conversations yet needs somewhere to collect its first invite.
  // Both ride the same authenticated Noise connections under this one key.
  connectionRelay = attachBlindRelay(blindPeer, {
    log: (message) => process.stderr.write(message + '\n')
  })
  inviteMailbox = attachInviteMailbox(blindPeer, {
    directory: config.inviteMailboxDir || path.join(config.storage, 'invite-mailbox'),
    maxPerRecipient: positiveInteger(config.maxInvitesPerRecipient, 100),
    maxPerSender: positiveInteger(config.maxInvitesPerSender, 8),
    maxTotal: positiveInteger(config.maxInvitesTotal, 10000),
    maxBytesPerRecipient: positiveInteger(config.maxInviteBytesPerRecipient, 256 * 1024),
    maxHttpRequestsPerMinute: positiveInteger(config.maxInviteRequestsPerMinute, 600),
    log: (message) => process.stdout.write(message + '\n')
  })
  // Loopback only. Caddy publishes it, and must strip the path prefix — see
  // README.md; without that every mailbox request returns 404.
  await inviteMailbox.listenHttp(
    positiveInteger(config.inviteMailboxHttpPort, 49739),
    config.inviteMailboxHttpHost || '127.0.0.1'
  )

  await blindPeer.listen()
  process.stdout.write('blind-peer ready: ' + Id.normalize(blindPeer.publicKey) + '\n')
}

function positiveInteger (value, fallback) {
  const parsed = Number(value === undefined ? fallback : value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error('invalid positive integer config')
  return parsed
}

main().catch((error) => {
  process.stderr.write('blind-peer failed: ' + (error && error.code ? error.code : 'startup error') + '\n')
  process.exit(1)
})
