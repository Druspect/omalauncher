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
- `Runtime` connects a verified prompt to the requested provider, or
  `AgentStore.default` when no provider is requested, and
  `Runtime.terminal`; Linux-unavailable and no-agent cases return a signed
  `failed` status.
- OWL exposes `android.watch` and `watch("hello" | "pin" | "unpair" |
  "listen")`.
  `61-omawatch.owl` is the built-in home-screen widget for pairing and status.

## Contract

The wire contract is `protocol/phone.md` in the omawatch repo. Both sides run
its vectors: the watch in `EnvelopeTest`, this app in `OmaWatchBridgeTest`
(`app/src/test/resources/omawatch/phone-v1.json` is a copy).

- `sig` covers the canonical form of `{v, type, id, body}`. The canonical form
  is the RFC 8785 subset for objects, strings, and integers.
  `JSONObject.toString()` and `JSONObject.quote()` are not it: they escape `/`.
- The latest `theme` is an urgent `DataClient` item at `/omawatch/theme`, key
  `env`, so a watch that slept through a theme change still reads it.
  `hello`, `prompt`, and `status` are `MessageClient` messages on `/omawatch`.
- This app advertises `omalauncher_phone` and sends only to nodes advertising
  `omawatch_watch`.
- A `prompt` needs a `seq` above the last one accepted from the pinned watch
  key and an `expires` no more than 120 s ahead. Otherwise the reply is
  `status` `failed` with `reason` `duplicate` or `expired`, and nothing runs.
- Without Linux or a selected agent the reply is `failed`, `reason`
  `no_agent`. The watch does not choose the agent and is not sent a roster.

## Pairing flow

1. Each side sends an unsigned `hello` with its raw Ed25519 key and fingerprint.
   A side that has not pinned the sender answers the first `hello` from a key
   once, so neither side has to start.
2. The widget shows the watch fingerprint; the watch shows the phone's.
3. `pin` stores the exact key whose fingerprint was on screen. A pinned side
   ignores `hello`; `unpair` first to accept a different watch.

The private key is kept in the app-private files directory and is never placed
in a widget, log, message body, or repository.

## Hold-to-talk

The watch has no on-device recognizer, and no Google recognizer is used anywhere
(omawatch D14). The watch records 16 kHz PCM only while its talk tile is held
and streams it over a `ChannelClient` channel on `/omawatch/audio`, ending with
a signed `audio` envelope that carries the SHA-256 of the bytes.

This app writes the PCM to a private temp file, checks the envelope against the
pinned watch key, `seq`, `expires`, the byte count, and the hash, then
transcribes with whisper.cpp built into the app (`libomawhisper`, pinned
v1.9.5, built at `-O3` in every variant), deletes the audio, and runs
`AgentStore.default` with the text through `agentInvocation`. The watch sees
`whisper running`, then the agent's `running` and `exited`.

The model is `ggml-base.en.bin` (142 MB), downloaded once from the widget
(`watch("voice")`) and checked against whisper.cpp's published SHA-1. Without
it the reply is `failed`, `reason` `no_transcriber`. The phone cannot open the
watch's microphone: there is no `listen` message.

## Debug and release identity

Wear Data Layer delivery requires the phone and watch packages and signing
identities to match. The watch application id is `dev.omalauncher`. Build both
release APKs on the same machine (both fall back to its debug key) or give
both the same owner key. The side-by-side `dev.omalauncher.mod` debug build pairs with a watch shell built with `-Pomawatch.applicationId=dev.omalauncher.mod`.

## Verification

```sh
ANDROID_HOME=$HOME/Library/Android/sdk \
  ./gradlew :owl:test :app:testDebugUnitTest --console=plain
```
