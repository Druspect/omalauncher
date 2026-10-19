# The overlay

Upstream omalauncher now ships what this fork used to add itself:

- **Adreno/Turnip GPU** — native since 0.8/0.9 (`/dev/kgsl-3d0`, a patched Mesa
  bundled in the APK, SHA-pinned). The old self-built Turnip (`scripts/build-turnip.sh`,
  the `turnip` CI job, the KGSL branch of `Gpu.kt`) is gone: upstream's is better kept.
- **Translucent "glass" tiles** — native and configurable (`style.toml`:
  `widgets.opacity`, `*.framed`, `corners.scale`, frost/blur).

So the fork is no longer a GPU fork. All that is left is a thin **overlay** on top of
a stock upstream release tag, kept as a short branch (`druspect/overlay`) so it
re-stitches onto each new upstream version with almost no conflict.

## What the overlay adds (and nothing else)

1. **Report unusable theme names** — `ThemeDirs.scan` surfaces a user theme dir that
   has a palette but a name the launcher can't use (capitals, spaces, …) instead of
   dropping it silently. `theme/OmarchyTheme.kt` + a test.
2. **Side-by-side signed build** — `-PapplicationIdSuffix`, `-PappLabel`,
   `-PversionSuffix` in `app/build.gradle.kts`, with `${appLabel}` / `${applicationId}`
   in `AndroidManifest.xml`, so a build installs next to the store app as
   `dev.omalauncher.adreno` ("Omalauncher Adreno") and never touches its Linux.
3. **CI** — `.github/workflows/overlay-apk.yml`: on a push to `druspect/overlay`,
   run the unit tests, then assemble and sign a release APK with the `KEYSTORE_*`
   secrets so each build updates the last one in place. Uploads `omalauncher-overlay.apk`.

That is the whole fork. Each item is its own commit on top of the release tag.

## Re-stitching onto a new upstream release

```sh
cd ~/omalauncher-source
git fetch upstream --tags
# Move the three overlay commits onto the new tag (e.g. v0.10.0):
git rebase --onto v0.10.0 <current-base-tag> druspect/overlay
# Bump the base tag you rebased from for next time, resolve any small conflicts,
# then push to build:
git push --force-with-lease <public-fork-remote> druspect/overlay
```

`<current-base-tag>` is the upstream tag the overlay currently sits on — the first
line of `git log --oneline` below the three overlay commits. If a rebase conflicts,
it will only be in the three small files above; upstream owns everything else.

## Signing

The build needs four repo secrets on the fork it runs in: `KEYSTORE_BASE64`
(the keystore base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
The keystore itself lives only at `~/.config/omalauncher-adreno-signing/` (never in a
repo). Without the secrets the CI fails on purpose, because an unsigned build can't
update the previous one in place and would force an uninstall (losing its Linux).

See also [LAUNCHER.md](LAUNCHER.md) and [RESTORE.md](RESTORE.md).
