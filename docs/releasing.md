# Releasing

Mirrors iOS (`opencode-ios/docs/releasing.md`): every push to `main` is a beta. Push only when
Ryan asks or approves.

## What a push to main does

`.github/workflows/android.yml`, after `scripts/check.sh` passes:

1. **Google Play internal testing.** Builds `:app:bundleRelease` signed with the Play upload key
   and uploads it to the internal testing track (`play` job), released to every tester at
   once. Testers get it as a normal Play update.
2. **Google Play open testing** (the counterpart of iOS's External TestFlight group), only when
   the `PLAY_OPEN_TESTING` repo variable is `true`. The same `play` job then puts that bundle
   on the open testing (`beta`) track with `scripts/play-promote.sh`; it doesn't upload it a
   second time. Otherwise it skips with a notice.
3. **Sideload APK.** Publishes the debug APK (`ai.opencode.mobilecode.debug`, installs beside
   the Play build) as the rolling `main-latest` GitHub pre-release.

Versions: `versionCode` is the workflow run number, so it only goes up (Play rejects a code it
has seen); `versionName` is `0.1.0-<short sha>`. Never bump either by hand. A run that is
re-run keeps its number and Play refuses the duplicate: push a new commit instead.

## Google Play

- Account Anomaly Innovations, Inc. (`5599481792709483137`), app **OpenCode**
  `ai.opencode.mobilecode` (same ID as iOS), Play Console app id `4973267939382667246`.
- Play App Signing holds the app signing key. CI signs uploads with the **upload key**, kept
  only in repo secrets (`ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`,
  `ANDROID_UPLOAD_KEY_ALIAS`). If it's lost or leaks, request an upload key reset in Play Console
  (App integrity → App signing) with a new key's certificate; nothing else changes.
- The Play upload uses `PLAY_SERVICE_ACCOUNT_JSON`: a key for
  `play-publisher@opencode-android-play.iam.gserviceaccount.com` (Google Cloud project
  `opencode-android-play` in the anoma.ly org), invited in Play Console with only *View app
  information* and *Release apps to testing tracks* on OpenCode, so it can't publish to
  production. Rotate it with `gcloud iam service-accounts keys create` and `gh secret set`, then
  delete the old key. Without the secret the `play` job skips with a notice and the bundle stays
  a workflow artifact (`opencode-android-aab-<sha>`).
- Internal testing: email list **Anomaly team** (everyone on Anomaly's Slack, up to 100).
  Testers opt in once at https://play.google.com/apps/internaltest/4698009188042349382, then
  install OpenCode from Play. Add people by editing that list in Play Console (Testing →
  Internal testing → Testers).
- Internal testing has no Google review. Closed or open testing (like iOS's External group)
  needs the store listing, content rating, data safety form and privacy policy first.
- Open testing: in Play Console (Testing → Open testing) choose the countries and set up the
  testers page, finish the items above, then `gh variable set PLAY_OPEN_TESTING --body true`.
  Unset it (`gh variable delete PLAY_OPEN_TESTING`) to stop. Google reviews open testing
  releases, so a build can take a while to reach testers; a newer push replaces one still in
  review. Anyone opts in at https://play.google.com/apps/testing/ai.opencode.mobilecode.
