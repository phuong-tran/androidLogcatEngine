@file:Suppress("UnstableApiUsage") // Suppress warnings for incubating DSL features in AGP

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.core.logcat.capture"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    buildFeatures {
        aidl = true
    }

    sourceSets {
        getByName("main") {
            // Ensure the build system recognizes the AIDL directory for IPC
            aidl.srcDir("src/main/aidl")
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // consumerProguardFiles automatically applies rules to apps that include this library
        consumerProguardFiles("consumer-rules.pro")

        /**
         * NATIVE BUILD CONFIGURATION (GLOBAL)
         * We move most flags here to avoid unstable API warnings in buildTypes.
         */
        externalNativeBuild {
            cmake {
                // Support all major Android architectures for Open Source compatibility
                abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

                /**
                 * C++ COMPILER FLAGS
                 * -std=c++17: Required for std::string_view and modern syntax.
                 * -O3: Aggressive optimization for maximum performance.
                 * -fvisibility=hidden: Reduces binary size and hides internal symbols.
                 * -flto: Link-time optimization to further squeeze out performance.
                 */
                cppFlags(
                    "-std=c++17",
                    "-O3",
                    "-fvisibility=hidden",
                    "-flto",
                    "-frtti",
                    "-fexceptions"
                )

                // Use shared C++ runtime to prevent duplicate library issues in complex apps
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            /**
             * NOTE: In modern AGP, externalNativeBuild inside buildTypes
             * can be unstable. We have moved the primary O3/LTO flags to
             * defaultConfig for broader stability.
             */
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = "logcat-engine-core"
            version = providers.gradleProperty("VERSION_NAME").get()

            pom {
                name.set("LogcatEngine Core")
                description.set("Native-backed Android logcat capture core with Kotlin Flow APIs.")
                url.set("https://github.com/phuong-tran/androidLogcatEngine")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }

                developers {
                    developer {
                        id.set("phuong-tran")
                        name.set("Phuong Tran")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/phuong-tran/androidLogcatEngine.git")
                    developerConnection.set("scm:git:ssh://github.com/phuong-tran/androidLogcatEngine.git")
                    url.set("https://github.com/phuong-tran/androidLogcatEngine")
                }
            }
        }
    }

    repositories {
        maven {
            name = "LocalStaticMaven"
            url = rootProject.layout.projectDirectory.dir("maven").asFile.toURI()
        }
    }
}

afterEvaluate {
    publishing {
        publications.named<MavenPublication>("release") {
            from(components["release"])
        }
    }
}
