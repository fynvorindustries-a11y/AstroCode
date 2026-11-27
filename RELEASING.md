# Releasing ASTRO (how updates reach players)

Players never download source code. They download the *built* app, and only the built files that changed.

1. Commit your changes to AstroCode.
2. `git tag 5.1.0 && git push origin 5.1.0`   (plain MAJOR.MINOR.PATCH, no "v", higher than the last release)
3. The "Release" workflow builds the app, hashes every built file against the previous release's
   `update-manifest.json`, packs them, signs the manifest, and publishes to AstroLauncher > Releases.
   The run's summary page lists exactly which files changed.
4. An installed launcher checks AstroLauncher releases, sees 5.1.0 is newer, fetches the signed manifest,
   hashes its own files, downloads only the ones that differ (HTTP range requests), checks every hash,
   swaps them in after exit, and rolls back automatically if the new version doesn't start.

## One-time setup in AstroCode > Settings > Secrets and variables > Actions
- `UPDATE_SIGNING_KEY` : the private Ed25519 key (base64 PKCS#8). Its public half must be the `k1` entry in
  `src/main/resources/updater/trusted-keys.json`. Never commit the private key.
  (Generate a pair with the `KeyTool` class in `update/incremental/tool` if you don't have one.)
- `RELEASE_TOKEN` : fine-grained personal access token with Contents read & write on AstroLauncher.

## Rules the pipeline enforces
- Tag must be plain `X.Y.Z`. The launcher ignores `v1.2.3`, drafts and pre-releases.
- `build.gradle` must not hard-code a project `version`; the tag is passed in with `-Pversion`.
  The build fails if the built `version.properties` doesn't match the tag.
