plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.xly.u_8th"
    // Fijados explícitamente: heredarlos de flutter.* hacía que actualizar
    // Flutter cambiase el target silenciosamente.
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.xly.u_8th"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 + shrink: sin esto el APK incluía todo AndroidX (car.app,
            // media, documentfile) y Kotlin stdlib sin podar: más I/O en
            // arranque, más memoria y sin ofuscación.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // APK universal = libflutter.so + libapp.so x4 ABIs (~3-4x más grande).
    // NOTA: NO declarar splits.abi aquí: el plugin de Flutter inyecta su
    // propio ndk.abiFilters y gradle falla con "Conflicting configuration".
    // El reparto por ABI lo hace la herramienta:
    //   flutter build apk --release --split-per-abi
    //   (o publica AAB en Play, que divide solo)
}

dependencies {
    // documentfile eliminado: el listado SAF usa DocumentsContract directo
    // (1 cursor por directorio en vez de 1 query por hijo).
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.media:media:1.7.0")
}

flutter {
    source = "../.."
}
