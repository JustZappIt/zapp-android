// GENERATED FILE — DO NOT EDIT.
//
// Vendored verbatim from zappMessaging server/blind-relay.js
// Source commit: 7efb2692c805f4aff303ef3aac7c0b0ff834131c (pinned in .zapp-deps)
//
// Edit the original in zappMessaging, then regenerate:
//   node scripts/vendor-blind-push-server.js

const BlindRelay = require('blind-relay')

/**
 * Add TURN-like UDX relaying to the same Hyperswarm identity used by a
 * blind-peer server. Stock blind-peer only pairs its own `blind-peer`
 * Protomux protocol; HyperDHT's relayThrough option needs a server for the
 * separate `blind-relay` protocol on every incoming Noise stream.
 */
function attachBlindRelay (blindPeer, opts = {}) {
  if (!blindPeer || !blindPeer.swarm || !blindPeer.swarm.dht) {
    throw new Error('attachBlindRelay: blind peer must be ready')
  }

  const log = opts.log || (() => {})
  const createServer = opts.createServer ||
    ((serverOptions) => new BlindRelay.Server(serverOptions))
  const relayServer = createServer({
    createStream: (streamOptions) => blindPeer.swarm.dht.createRawStream(streamOptions)
  })

  const onConnection = (connection) => {
    const session = relayServer.accept(connection, {
      id: connection.remotePublicKey
    })
    session.on('error', (error) => {
      log('Blind relay session error: ' + (error.message || error))
    })
  }

  blindPeer.swarm.on('connection', onConnection)

  return {
    server: relayServer,
    async close () {
      blindPeer.swarm.removeListener('connection', onConnection)
      await relayServer.close()
    }
  }
}

module.exports = { attachBlindRelay }
