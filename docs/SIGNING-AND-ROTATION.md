# Signing Exposure and Key Rotation — Android

Section 16 item 7 (OPEN — signing exposure and upgrade continuity). The
current state, honestly:

## What is exposed

- `apps/android/keystores/pulse-release.keystore` is COMMITTED to the
  repository, with its passwords inline in `apps/android/app/build.gradle.kts`
  ("GS-distribution model"). Any repository reader can sign APKs that the
  LiveUpdater and any picky ROM will accept as authentic Pulse builds.
- The repository's full history carries the keystore; removing it in a future
  commit does not un-publish it. The key must be treated as public FOREVER.

## What changed (R67-era)

`signingConfigs.release` now reads a protected key out-of-band first:

- `PULSE_KEYSTORE_FILE` / `PULSE_KEYSTORE_PASSWORD` / `PULSE_KEY_ALIAS` /
  `PULSE_KEY_PASSWORD` environment variables, or the matching
  `pulseKeystoreFile/Password/Alias/KeyPassword` gradle properties.
- With NO override the committed distribution keystore remains the default —
  CI and the LiveUpdater keep working and upgrade continuity is never
  silently broken.

## Rotation and upgrade continuity (the procedure)

Android v2/v3 signatures cannot be dual-signed across key rotations: an APK
signed with a NEW key will not install over a package signed with the OLD key
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — the user must uninstall (losing
local data) or the app must switch to Play App Signing (which holds the
upgrade key server-side and rotates the upload key freely).

1. **First protected release**: generate a new key out-of-band, build with
   `PULSE_KEYSTORE_*` set, publish as a NEW install path (fresh package or
   explicit uninstall migration). The LiveUpdater must distribute this build
   under a release note that says "uninstall first".
2. **Adopt Play App Signing** for anything distributed through Play: the
   upload key becomes the only secret, rotation becomes routine.
3. **The committed key stays for dev/CI distribution** only; it must never
   sign another release once a protected key exists.
4. **Recovery**: the protected key material (not the committed one) needs an
   escrowed copy — loss after step 1 means another uninstall-migration for
   every user. For Play-distributed builds, Play App Signing IS the escrow.

## What remains an external release blocker

Everything in this document that touches the production key (generation,
escrow, Play onboarding, the uninstall-migration release) is out-of-band for
this repository's CI. The code side is done: authorized builds can sign with
a protected key today, and the committed key is documented as public.
