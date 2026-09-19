plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.loopa.app"
    compileSdk = 35

    signingConfigs {
        // AGP domyslnie sam wyszukuje debug.keystore, ale gdzie dokladnie szuka
        // zalezy od zmiennych srodowiskowych (ANDROID_SDK_HOME / ANDROID_USER_HOME),
        // ktore na hostowanych maszynach GitHuba wskazuja gdzie indziej niz na tym
        // komputerze. Efekt: apka z CI byla podpisana zupelnie innym kluczem niz
        // lokalna, mimo poprawnie odtworzonego pliku - a to zrywa aktualizacje
        // "po wierzchu" i kasuje playlisty. Wskazujemy plik jawnie, wiec obie
        // strony (lokalnie i w CI) uzywaja dokladnie tego samego keystore'u.
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.loopa.app"
        minSdk = 26
        targetSdk = 35
        // Podbijane przy kazdym wydaniu - Android odmawia instalacji "po wierzchu",
        // gdy nowy plik ma nizszy kod wersji niz zainstalowany.
        versionCode = 6
        versionName = "1.3.2"
    }

    buildTypes {
        release {
            // NewPipeExtractor + Rhino lean on reflection; shrinking here is a footgun.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` produces a directly installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        // Apka idzie sideloadem na jeden telefon, nie do sklepu - `lintVital` na
        // buildzie release tylko wydluza kompilacje i potrafi ja przerwac przez
        // drobiazgi, ktore tu nikomu nie szkodza.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.guava)
    implementation(libs.newpipe.extractor)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
