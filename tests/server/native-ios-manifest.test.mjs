import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('iOS declares background reads and attention permissions through supported XcodeGen Info configuration', async () => {
  const spec = await readFile(new URL('../../apps/ios/project.yml', import.meta.url), 'utf8')
  assert.doesNotMatch(spec, /^\s*infoProperties:/m)
  const app = spec.split('\n  Pulse:\n')[1].split('\n  PulseTests:')[0]
  assert.match(app, /\n    info:\n      path: \.generated\/Pulse-Info.plist\n      properties:\n/)
  for (const key of ['UILaunchScreen', 'UIBackgroundModes', 'BGTaskSchedulerPermittedIdentifiers',
    'NSMicrophoneUsageDescription', 'NSCameraUsageDescription', 'NSFaceIDUsageDescription',
    'NSLocationWhenInUseUsageDescription', 'CFBundleURLTypes', 'UIApplicationShortcutItems']) {
    assert.match(app, new RegExp(`^        ${key}:`, 'm'))
  }
  assert.match(app, /CFBundleShortVersionString: "\$\(MARKETING_VERSION\)"/)
  assert.match(app, /MARKETING_VERSION: "0\.10\.0"/)
  assert.match(app, /CURRENT_PROJECT_VERSION: "1"/)
  const entry = await readFile(new URL('../../apps/ios/Pulse/PulseApp.swift', import.meta.url), 'utf8')
  const identifier = entry.match(/static let outboxTaskID = "([^"]+)"/)[1]
  assert.ok(app.includes(`BGTaskSchedulerPermittedIdentifiers: ["${identifier}"]`))
  const contract = await readFile(new URL('../../apps/ios/PulseTests/InfoPlistContractTests.swift', import.meta.url), 'utf8')
  assert.match(contract, /Bundle.main.object\(forInfoDictionaryKey: "UIBackgroundModes"\)/)
  assert.match(contract, /Bundle.main.object\(forInfoDictionaryKey: "UILaunchScreen"\)/)
})
