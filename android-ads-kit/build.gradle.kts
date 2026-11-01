plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
}

android {
    namespace = "com.tukonnichiwa.adskit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.tu-konnichiwa.AndroidAdsKit"
                artifactId = "android-ads-kit"
                version = providers.environmentVariable("VERSION")
                    .orElse("1.0.0-SNAPSHOT")
                    .get()
            }
        }
    }
}

dependencies {
    api(libs.play.services.ads)
    implementation(libs.shimmer)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.user.messaging.platform)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
    api(libs.androidx.lifecycle.runtime.compose)
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.analytics)
    compileOnly(libs.facebook.android.sdk)
    testImplementation(libs.junit)
}
