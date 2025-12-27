plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    buildFeatures {
        aidl = true
    }
    sourceSets {
        getByName("main") {
            aidl.srcDir("src/main/aidl")
        }
    }

    namespace = "com.core.logcat.capture"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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


/*
apply from: rootProject.file("gradle/android_library_config.gradle")

android {

    buildFeatures {
        aidl = true
    }

    sourceSets {
        main {
            aidl.srcDirs = ['src/main/aidl']
        }
    }

    defaultConfig {
        consumerProguardFiles "consumer-rules.pro"
        ndkVersion = "29.0.13846066"
    }

    externalNativeBuild {
        cmake {
            path file('src/main/jni/CMakeLists.txt')
            version = "3.22.1"
        }
    }

    namespace = 'com.zalora.core.logcat.capture'
}


dependencies {
    // implementation libs.materialDesign
    // implementation libs.recyclerViewLib
}


afterEvaluate {
    android.buildFeatures.aidl = true
}
*/
