# LogcatEngine Testing

LogcatEngine has two feedback loops:

- regular Android/Gradle checks for Kotlin, AIDL, native packaging, and sample UI
- targeted native checks for C++ syntax and future host-side diagnostics

## Environment

Use JDK 17 for local Gradle checks:

```bash
java -version
./gradlew --version
```

If your machine is behind TLS inspection, make sure the JDK used by Gradle
trusts the local proxy root certificate.

Do not commit a machine-local `org.gradle.java.home` value to the project. If a
machine needs one, prefer `JAVA_HOME`, the IDE Gradle JVM setting, or a
user-level `~/.gradle/gradle.properties` entry.

For one-off commands on machines that default to another JVM:

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew --version
```

The output should show Java 17 as both launcher and daemon JVM.

## Regular Checks

Run the same checks used by CI:

```bash
./gradlew :core:testDebugUnitTest \
  :core:lintDebug \
  :app:lintDebug \
  :core:assembleRelease \
  :app:assembleDebug \
  --console=plain
```

Publish the local static Maven artifact:

```bash
./gradlew :core:publishReleasePublicationToLocalStaticMavenRepository --console=plain
```

Current local coordinate:

```kotlin
implementation("io.github.phuongtran:logcat-engine-core:1.2")
```

## Native Syntax Check

When Gradle dependency resolution is not available, use the Android NDK compiler
to catch C++ syntax issues:

```bash
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
NDK="$ANDROID_HOME/ndk/29.0.13846066"

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux) HOST_TAG="linux-x86_64" ;;
  *) echo "Unsupported host OS for this syntax-check snippet" >&2; exit 1 ;;
esac

CXX="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android24-clang++"
"$CXX" -std=c++17 -fsyntax-only \
  -Icore/src/main/jni \
  core/src/main/jni/LogEngine.cpp \
  core/src/main/jni/LogEngine_jni.cpp
```

## Kotlin Compile Check

Prefer Gradle for Kotlin compilation because it owns Android bootclasspath,
AIDL generation, dependency resolution, and compiler plugin configuration:

```bash
./gradlew :core:compileDebugKotlin --console=plain
```

## Future Host-Native Diagnostics

The native core would benefit from AndroidOutBox-style host diagnostics.

Good first targets:

- literal filter matching
- regex update behavior
- nonblocking all-or-drop write behavior
- line accumulator bounds
- stop wakeup and lifecycle race behavior with fake file descriptors

The current native implementation still couples process execution to
`/system/bin/logcat`, so host tests should start by extracting testable helpers
before trying to run the full worker loop on macOS.

## Sample App Policy

The `:app` module is a sample playground. It should build, exercise the core
API, and stay small. It does not need release-grade product UX.
