@file:Suppress("UnstableApiUsage") // Suppress warnings for incubating DSL features in AGP

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.core.logcat.capture"
    compileSdk = 36

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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}