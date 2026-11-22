# PhotoAvatar RU — instructions for Codex

Scope: work only inside `photoavatar-ru-android/` on the `photoavatar-ru-android` branch. Do not edit the unrelated Sony Vegas Android app in the repository root.

## Goal
Build an installable, testable Android app for Samsung SM-F966B (Android 17, ARM64, foldable displays) that presents a realistic real-time video call with an avatar created from the user's chosen photo. Russian speech input/output. Original call-style UI, not a WhatsApp trademark or copied assets.

## Workflow
1. Before modifying avatar/session functionality, use the official **Runway Dev** plugin and its `runway-dev-characters` skill. Check current Runway Dev Characters docs and the connected project's access, capabilities, limits, pricing and available session methods. Do not assume a model or integration is available.
2. Use **Runway Characters** for the visual character, with an optional **ElevenLabs Agents** conversation/speech integration only where its documented APIs and the user's connected accounts allow this. Verify Russian support and reference-voice options using provider documentation and integration tests.
3. Accept one uploaded image and support cropping/selecting the intended front-facing portrait from an optional 3x3 collage. **Do not claim a collage trains a multi-view character**: submit the single valid portrait accepted by the provider.
4. Allow entering the character persona and a free-form Russian conversation topic / context. The app must expose recording permission, microphone toggle, speaker control, end-call action, connection/error states and clear consent for a cloned voice.
5. Keep **all long-lived secrets on a server**; do not hard-code `RUNWAYML_API_SECRET`, ElevenLabs keys or signed WebSocket URLs in the APK, HTML, repository or telemetry. Server issues only scoped, short-lived session credentials. Provide `.env.example` with placeholders.
6. Never create a paid Character, make billable model calls or use real credits without user approval. Do not call the app fully functional until an end-to-end provider-backed call is tested.
7. Provide Gradle/GitHub Actions builds, Android tests on suitable API levels and layout testing for cover and unfolded screens. Record test evidence and known gaps. Do not claim physical-device tests unless actually run.

## Status
The existing project can contain D-ID-era implementation. Treat this as migration guidance; inspect current code before replacing functionality. Read `RUNWAY_DEV_SETUP.md` for installation instructions. Official docs: https://docs.dev.runwayml.com/characters/ and https://docs.dev.runwayml.com/guides/plugin/ .
