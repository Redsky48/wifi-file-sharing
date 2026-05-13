plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wifishare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wifishare"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.6.0"
        resourceConfigurations += listOf("en")

        // ---- AdMob configuration ----
        // Leave both empty to ship without ads. To enable:
        //   1. Create an AdMob app + banner ad-unit at https://admob.google.com
        //      (your AdSense publisher ID auto-links into AdMob).
        //   2. Paste the IDs below.
        //   3. The app initialises MobileAds and shows a banner only when
        //      ADMOB_BANNER_UNIT_ID is non-empty — otherwise the SDK is left
        //      idle and the bottom bar disappears.
        val admobAppId = ""              // e.g. "ca-app-pub-1608660333482788~1234567890"
        val admobBannerUnitId = ""       // e.g. "ca-app-pub-1608660333482788/0987654321"

        // Manifest needs *some* APPLICATION_ID even when ads are disabled,
        // otherwise the Play Services bootstrap crashes on init. We feed
        // Google's official test ID as a harmless placeholder.
        manifestPlaceholders["admobAppId"] = admobAppId.ifBlank {
            "ca-app-pub-3940256099942544~3347511713"
        }
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerUnitId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")

    androidResources {
        noCompress += listOf("exe")
    }

    // Recognisable APK filenames that embed both versionName and versionCode
    // so the build.sh push-to-phone step can wipe older builds of the same
    // variant by prefix match.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "WiFiShare-${variant.versionName}-${variant.buildType.name}-v${variant.versionCode}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Mobile Ads (AdMob). Bundles Play Services dependency.
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}
