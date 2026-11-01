plugins {
    alias(libs.plugins.android.library)
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
    testImplementation(libs.junit)
}
