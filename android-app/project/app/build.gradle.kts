plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val oauthClientId = providers.gradleProperty("HA_OAUTH_CLIENT_ID")
    .orElse("https://example.invalid/suivi-alimentation-android")
val oauthRedirectUri = "suivialimentation://auth-callback"
val appVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .orElse("1")
    .get()
    .toInt()

val signingStorePath = providers.environmentVariable("ANDROID_SIGNING_STORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("ANDROID_SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("ANDROID_SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ANDROID_SIGNING_KEY_PASSWORD").orNull
val hasCiSigning = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.suivialimentation.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.suivialimentation.android"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = "0.1.0-mvp"

        buildConfigField("String", "HA_OAUTH_CLIENT_ID", quoted(oauthClientId.get()))
        buildConfigField("String", "HA_OAUTH_REDIRECT_URI", quoted(oauthRedirectUri))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
