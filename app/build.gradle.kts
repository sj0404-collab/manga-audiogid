import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "lv.bibliogid.manga"
    compileSdk = 34

    defaultConfig {
        applicationId = "lv.bibliogid.manga"
        minSdk = 24
        targetSdk = 34
        versionCode = 28
        versionName = "1.6.12"
        vectorDrawables { useSupportLibrary = true }
    }

    // Постоянный ключ подписи (лежит в проекте): благодаря ему новые версии
    // ставятся ПОВЕРХ старых, без «удалите приложение».
    signingConfigs {
        create("biblio") {
            val path = keystoreProps.getProperty("storeFile")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug тоже подписываем постоянным ключом — обновления ставятся поверх
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("biblio")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("biblio")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // ONNX Runtime тянет нативные библиотеки под все ABI — оставляем телефонные
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CBR (RAR) support
    implementation("com.github.junrar:junrar:7.5.5")

    // Локальный OCR #1: ML Kit (bundled, на устройстве, миллисекунды). Tesseract НЕ используется.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Локальный OCR #2: PaddleOCR-ONNX (кириллица, офлайн после загрузки модели)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    testImplementation("junit:junit:4.13.2")
}
