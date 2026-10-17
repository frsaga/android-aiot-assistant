# Android AIoT Assistant

Java/XML Android coursework with conversational sessions, streaming replies, Markdown rendering, image/document attachments, speech input, local command parsing and status/configuration screens.

## Build

Open the project in Android Studio with Android SDK 36 installed, or run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. The Gradle 9.4.1 wrapper JAR is the official upstream binary verified against Gradle's published SHA-256. Configure the local Android SDK through Android Studio or your ANDROID_HOME environment variable; local.properties is intentionally not included.

API keys are supplied in the app's settings on your own device; no real key is bundled. Without a configured key the application offers local demonstration replies.

This source snapshot excludes APKs, personal coursework reports, private device data, build logs and original Git history. Sample telephone numbers in unit tests are synthetic examples, not personal contacts. Android instrumentation tests require a separate emulator/device.

Publication validation: `assembleDebug` and `testDebugUnitTest` passed in an isolated local build. Instrumentation tests require a separate Android device/emulator.
