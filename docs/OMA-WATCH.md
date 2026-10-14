# OmaWatch phone bridge

This branch adds the phone side of the OmaWatch v1 link. The phone remains the
computer: the watch sends a short, signed prompt and OmaLauncher opens the
already-selected agent in its normal terminal path. The phone sends the
resolved theme palette and short agent status messages back to the watch.

## Runtime pieces

- `OmaWatchBridge` owns the Wear Data Layer `/omawatch` path, Ed25519 keys,
  pairing state, message canonicalization, and theme/status envelopes.
- `OmaWatchBridgeService` is the exported Play Services listener. It does no
  work besides handing an event to the application bridge.
- `Runtime` connects a verified prompt to `AgentStore.default` and
  `Runtime.terminal`; Linux-unavailable and no-agent cases return a signed
  `failed` status.
- OWL exposes `android.watch` and `watch("hello" | "pin" | "unpair")`.
  `61-omawatch.owl` is the built-in home-screen widget for pairing and status.

## Pairing flow

1. The phone sends an unsigned `hello` containing its public key.
2. The watch sends its unsigned `hello`.
3. OmaLauncher shows the pending watch fingerprint in the widget.
4. The user taps `pin`; the phone stores the watch's raw public key and its
   matching fingerprint together.
5. Prompts are accepted only when their Ed25519 signature verifies against that
   stored key. Theme and status messages sent by the phone are signed with the
   phone key.

The private key is kept in the app-private files directory and is never placed
in a widget, log, message body, or repository. A future hardening pass can move
it to an Android Keystore once the minimum platform policy is settled; the
current implementation fails closed when Ed25519 is unavailable.

## OnePlus-side implementation checklist

The watch client must make these behaviors match this branch:

- Send `hello` with `body.pubkey` as the raw 32-byte Ed25519 public key in
  lowercase hex and `body.fingerprint` as `SHA256:` plus standard Base64 of
  that raw key. `hello` is the only unsigned message.
- On the phone's `hello`, retain the phone raw public key as a pending key and
  show its fingerprint. A confirm action must store the key and fingerprint as
  one pair; never store only the fingerprint.
- After the watch key is pinned on the phone, send `prompt` with
  `body.text` limited to 500 characters and sign the canonical body. The phone
  verifies the signature before invoking the selected agent.
- Verify the phone signature on every `theme` and `status` message. Theme
  fields are `name`, `bg`, `fg`, `accent`, `muted`, and increasing integer
  `seq`; status fields are `agent`, `state`, `ref`, and optional `line`.
- Apply an accepted theme to the interactive shell palette. Keep the WFF
  ambient face static; the phone bridge cannot make a resource-only face
  dynamically recolor.
- Use `MessageClient` on `/omawatch`; prompts/status are best effort and must
  be retried by the user-visible hello/status path. Do not add a socket,
  `INTERNET`, cloud speech, or a second agent selector on the watch.

The current watch snapshot has a trust mismatch (`pinnedFingerprint` versus
`pinnedPubkey`) and saves theme fields without feeding them into Compose. Fix
those before attempting an end-to-end prompt test.

## Debug and release identity

Wear Data Layer delivery requires the phone and watch packages and signing
identities to match. The watch application id must therefore remain
`dev.omalauncher`, and an APK signed with a different debug key will not pair
with a release OmaLauncher install. For device testing, install both debug
artifacts signed by the same key, or test both release-signed artifacts.

## Verification

```sh
ANDROID_HOME=/Users/fuller_m/Library/Android/sdk \
  ./gradlew :owl:test :app:testDebugUnitTest --offline --console=plain
```

The branch also checks the bundled bridge widget through the OWL compiler test.
