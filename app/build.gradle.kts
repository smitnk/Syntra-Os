plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.syntra.os"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.syntra.os"
        minSdk = 28 // Vulkan 1.1 symbols (GPU backend) exist in libvulkan from Android 9
        targetSdk = 35
        versionCode = 13
        versionName = "0.13.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake {
            arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            // CI sets SYNTRA_VK_PREFIX (Vulkan + SPIR-V headers) and SYNTRA_GLSLC to build the optional GPU backend.
            System.getenv("SYNTRA_VK_PREFIX")?.let { vk ->
                arguments += listOf("-DSYNTRA_VULKAN=ON", "-DVulkan_INCLUDE_DIR=$vk/include",
                    "-DVulkan_GLSLC_EXECUTABLE=${System.getenv("SYNTRA_GLSLC")}", "-DSPIRV-Headers_DIR=$vk/share/cmake/SPIRV-Headers")
            }
        } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        // Committed debug key: every CI build has the same signature, so updates install over old ones.
        // shortcut: public key, fine for sideloaded test builds; use a private release key before publishing.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    flavorDimensions += "edition"
    productFlavors {
        // Full: phone control (accessibility) + notification reading. Play Protect warns about these.
        // Own app id: an old v0.2 "com.syntra.os" signed with a different key made Android reject the Full APK.
        create("full") { dimension = "edition"; applicationIdSuffix = ".full" }
        // Lite: no accessibility/notification services, so it installs without the "sensitive data" block.
        create("lite") { dimension = "edition"; applicationIdSuffix = ".lite"; versionNameSuffix = "-lite" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // proot and its loader are real executables shipped as lib*.so; they must be unpacked to run.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") // PDF text for RAG
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
