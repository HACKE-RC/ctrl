# AGENTS

This file guides agentic coding tools working in this repository.

## Scope
- Primary app lives under `app/` and uses Gradle with Kotlin and Jetpack Compose.
- A separate `kotlin-sdk/` subtree exists with its own docs and samples.
- No Cursor or Copilot instruction files were found in this repo.

## Build, Lint, and Test Commands

### Gradle wrapper
- Use the wrapper from repo root: `./gradlew <task>`.
- If Gradle fails to find the Android SDK, set `ANDROID_HOME` and update `local.properties`.

### Build
- Debug APK: `./gradlew :app:assembleDebug`
- Release APK: `./gradlew :app:assembleRelease`
- Bundle (AAB): `./gradlew :app:bundleDebug`
- Clean build: `./gradlew clean :app:assembleDebug`

### Lint
- All variants: `./gradlew lint`
- Debug lint only: `./gradlew :app:lintDebug`
- Release lint only: `./gradlew :app:lintRelease`
- Lint report location: `app/build/reports/lint-results-*.html`

### Unit tests (JVM)
- All unit tests: `./gradlew test`
- Debug unit tests: `./gradlew :app:testDebugUnitTest`
- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.example.ctrl.FooTest"`
- Single test method: `./gradlew :app:testDebugUnitTest --tests "com.example.ctrl.FooTest.testBar"`

### Instrumented tests (Android device/emulator)
- All connected tests: `./gradlew :app:connectedDebugAndroidTest`
- Single class: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ctrl.FooInstrumentedTest`
- Single method: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ctrl.FooInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.method=testBar`

### Helpful discovery
- List tasks: `./gradlew tasks`
- Module tasks: `./gradlew :app:tasks`

## Code Style Guidelines

### Kotlin and formatting
- Indentation is 4 spaces; no tabs.
- Use trailing commas in multiline argument lists and data classes (as in existing files).
- Keep lines readable; wrap long parameter lists across multiple lines.
- No formatter is configured; follow Android Studio default Kotlin style.

### Imports
- One import per line; avoid wildcard imports.
- Prefer grouping by origin: `android.*`, `androidx.*`, `com.example.*`, `kotlinx.*`, `java.*`, `kotlin.*`.
- Remove unused imports when editing a file.

### Naming
- Packages are all lowercase with dots (example: `com.example.ctrl.server`).
- Classes, interfaces, objects, and data classes use UpperCamelCase.
- Functions and properties use lowerCamelCase.
- Constants use UPPER_SNAKE_CASE and live in `companion object` or top level.
- File names match the primary type (example: `AllowlistStore.kt`).

### Types and nullability
- Prefer `val` and immutable data; use `var` only for mutable state.
- Use type inference for locals when obvious; add explicit types for public APIs or when it improves clarity.
- Use nullable types intentionally and handle nulls early with guards and returns.

### Error handling
- Use early returns for invalid input (see `McpServerService`).
- Use `runCatching {}` only for best-effort operations and handle failure explicitly.
- Throw domain-specific exceptions for JSON-RPC errors (`JsonRpcException`).
- Avoid silent catches; if an exception is intentionally ignored, add a short comment.

### Coroutines and concurrency
- Use `Dispatchers.IO` for network or blocking work.
- Prefer structured concurrency with a dedicated `CoroutineScope` (see `McpServerService`).
- Use `SupervisorJob` for long-lived services that should continue on child failure.
- Avoid leaking flows or channels; close and clean up in `onDestroy`.

### Jetpack Compose
- `@Composable` functions are named for what they render (noun or noun phrase).
- UI state is stored with `remember` and `mutableStateOf`.
- Prefer `rememberCoroutineScope()` for UI-triggered background work.
- Keep composables small and pure; move side effects to helpers or services.

### Android services and permissions
- Foreground services must create a notification channel on API 26+.
- Use `applicationContext` for long-lived services and stores.
- Gate runtime permissions with `ActivityResultContracts`.

### Networking and JSON-RPC
- Ktor server endpoints validate content type, size, and Accept headers.
- JSON parsing is strict (no lenient or unknown keys) via `Json` in `JsonRpc`.
- Use `JsonRpc.error(...)`/`JsonRpc.result(...)` to build responses.

### Security and access control
- Allowlist is enforced for non-localhost requests; update `AllowlistStore` for changes.
- Rate limiting uses `RateLimiter` and should be checked before heavy work.
- Do not weaken checks without documenting the reason and updating tests.

### Resource and UI usage
- Use string literals sparingly; prefer resources for user-visible text if UI grows.
- Use Material3 components and theme defaults as in `MainActivity`.

## Repository Notes for Agents
- `app/` is the only Gradle module included in `settings.gradle.kts`.
- `kotlin-sdk/` contains its own `AGENTS.md` and samples; follow its rules if you work there.
- No `.cursor/rules/`, `.cursorrules`, or `.github/copilot-instructions.md` files exist in this repo.
