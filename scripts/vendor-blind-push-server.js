#!/usr/bin/env node
'use strict'

/**
 * Copies the blind-peer server modules out of zappMessaging into the blind-push
 * deployment directory.
 *
 * The VPS installs that directory with `npm ci` and nothing else, so it cannot
 * reach a sibling repository at deploy time. The modules therefore have to be
 * present here, and the only safe way to have two copies of anything is for one
 * of them to be generated and checked.
 *
 *   node scripts/vendor-blind-push-server.js            regenerate
 *   node scripts/vendor-blind-push-server.js --check    fail on drift
 *
 * `--check` runs in CI. It fails when the vendored files no longer match the
 * zappMessaging commit pinned in .zapp-deps, which is what stops the deployed
 * server from quietly diverging from the client it has to interoperate with.
 */

const fs = require('fs')
const path = require('path')

const REPO_ROOT = path.resolve(__dirname, '..')
const DEPLOYMENT_DIR = path.join(REPO_ROOT, 'docs/notifications/deployment/blind-push')
const VENDOR_DIR = path.join(DEPLOYMENT_DIR, 'vendor')
const MODULES = ['blind-relay.js', 'invite-mailbox.js']

// Local checkouts keep zappMessaging beside this repo; CI checks it out into the
// workspace. Same two candidates settings.gradle.kts resolves.
const SOURCE_CANDIDATES = [
  path.resolve(REPO_ROOT, '../zappMessaging/server'),
  path.resolve(REPO_ROOT, 'zappMessaging/server')
]

function sourceDir () {
  const found = SOURCE_CANDIDATES.find(candidate => fs.existsSync(candidate))
  if (!found) {
    throw new Error(
      'zappMessaging/server not found. Looked in:\n  ' + SOURCE_CANDIDATES.join('\n  ')
    )
  }
  return found
}

/**
 * The zappMessaging commit this repo is built against. Recorded twice — once in
 * .zapp-deps and once as the workflow's ZAPP_MESSAGING_REF — so both are read
 * and required to agree. CI checks out the workflow's copy; the header records
 * .zapp-deps. A silent disagreement between them would surface here as
 * unexplained drift instead of as the bump that was only half applied.
 * @returns {string} 40-character commit hash
 */
function pinnedRef () {
  const deps = fs.readFileSync(path.join(REPO_ROOT, '.zapp-deps'), 'utf8')
  const match = /^zappMessaging=([0-9a-f]{40})$/m.exec(deps)
  if (!match) throw new Error('.zapp-deps has no zappMessaging commit')

  const workflow = fs.readFileSync(
    path.join(REPO_ROOT, '.github/workflows/pull-request.yml'), 'utf8'
  )
  const workflowMatch = /^\s*ZAPP_MESSAGING_REF:\s*([0-9a-f]{40})\s*$/m.exec(workflow)
  if (!workflowMatch) throw new Error('pull-request.yml has no ZAPP_MESSAGING_REF commit')
  if (workflowMatch[1] !== match[1]) {
    throw new Error(
      'zappMessaging pin disagrees between files:\n' +
      '  .zapp-deps:        ' + match[1] + '\n' +
      '  pull-request.yml:  ' + workflowMatch[1] + '\n' +
      'Set both to the same commit.'
    )
  }
  return match[1]
}

function render (moduleName, source, ref) {
  return [
    '// GENERATED FILE — DO NOT EDIT.',
    '//',
    '// Vendored verbatim from zappMessaging server/' + moduleName,
    '// Source commit: ' + ref + ' (pinned in .zapp-deps)',
    '//',
    '// Edit the original in zappMessaging, then regenerate:',
    '//   node scripts/vendor-blind-push-server.js',
    '',
    source.trimEnd(),
    ''
  ].join('\n')
}

/**
 * The vendored modules and their host both run in one process, so a version
 * skew between the two package.json files is a skew inside a single deployment.
 * A `blind-relay` mismatch is what shipped two different relay versions for the
 * same VPS, so shared dependencies are compared rather than commented about.
 * @param {string} from zappMessaging/server directory
 * @returns {Array<string>} human-readable mismatches
 */
function dependencyMismatches (from) {
  const read = file => JSON.parse(fs.readFileSync(file, 'utf8')).dependencies || {}
  const source = read(path.join(from, 'package.json'))
  const deployment = read(path.join(DEPLOYMENT_DIR, 'package.json'))
  return Object.keys(source)
    .filter(name => deployment[name] && deployment[name] !== source[name])
    .map(name => name + ': zappMessaging ' + source[name] + ' vs deployment ' + deployment[name])
}

function main () {
  const check = process.argv.includes('--check')
  const from = sourceDir()
  const ref = pinnedRef()
  const drifted = []

  fs.mkdirSync(VENDOR_DIR, { recursive: true })
  for (const moduleName of MODULES) {
    const expected = render(moduleName, fs.readFileSync(path.join(from, moduleName), 'utf8'), ref)
    const target = path.join(VENDOR_DIR, moduleName)
    if (check) {
      const actual = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : ''
      if (actual !== expected) drifted.push(moduleName)
    } else {
      fs.writeFileSync(target, expected)
      process.stdout.write('vendored ' + moduleName + '\n')
    }
  }

  const mismatches = dependencyMismatches(from)

  if (!check) {
    process.stdout.write('source: ' + from + '\npinned: ' + ref + '\n')
    for (const mismatch of mismatches) {
      process.stdout.write('dependency skew, fix by hand: ' + mismatch + '\n')
    }
    if (mismatches.length > 0) process.exitCode = 1
    return
  }

  if (drifted.length > 0) {
    process.stderr.write(
      'Vendored blind-push server files are out of date: ' + drifted.join(', ') + '\n' +
      'They no longer match zappMessaging@' + ref + '.\n' +
      'Run: node scripts/vendor-blind-push-server.js\n'
    )
  }
  for (const mismatch of mismatches) {
    process.stderr.write('Dependency skew between the two package.json files: ' + mismatch + '\n')
  }
  if (drifted.length > 0 || mismatches.length > 0) {
    process.exitCode = 1
    return
  }
  process.stdout.write('vendored blind-push server matches zappMessaging@' + ref + '\n')
}

try {
  main()
} catch (error) {
  process.stderr.write((error && error.message) + '\n')
  process.exitCode = 1
}
