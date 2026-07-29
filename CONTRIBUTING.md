# Contributing

Thanks for taking a look at LogcatEngine.

LogcatEngine is intentionally split into a releaseable `:core` library and a
sample `:app`. Contributions should preserve the core contract:

- bounded resources
- best-effort, non-lossless log capture
- native lifecycle ownership of file descriptors, worker threads, and child processes
- no UI dependency in `:core`
- no hidden network or upload behavior
- app-owned privacy, export, and sharing policy
- sample UI as a playground, not the library contract

## Local Checks

Run the normal feedback loop before opening a PR:

```bash
./gradlew :core:testDebugUnitTest --console=plain
./gradlew :core:lintDebug :app:lintDebug --console=plain
./gradlew :core:assembleRelease :app:assembleDebug --console=plain
```

For local machines that default to a different JVM, run Gradle with JDK 17:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew :core:testDebugUnitTest --console=plain
```

## Native Changes

Be conservative when changing `core/src/main/jni`.

Native changes should keep shutdown explicit, avoid blocking the hot write
path, keep memory bounded, and make ownership of file descriptors clear. When
changing line buffering, filtering, worker lifecycle, pipe writes, or JNI
boundaries, also run:

```bash
./gradlew :core:assembleRelease --console=plain
```

## Static Maven Publishing

This repository hosts its own static Maven repository under `maven/`.

To publish a new local static Maven version:

```bash
./gradlew :core:publishReleasePublicationToLocalStaticMavenRepository \
  --console=plain
```

Commit the generated `maven/` changes together with the version bump.
