import org.gradle.api.tasks.compile.JavaCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.google.crashlytics)
    alias(libs.plugins.firebase.perf)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "isim.ia2y.myapplication"
    compileSdk = 36

    defaultConfig {
        applicationId = "isim.ia2y.myapplication"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = secret("RELEASE_STORE_FILE")
            val storePw = secret("RELEASE_STORE_PASSWORD")
            val alias = secret("RELEASE_KEY_ALIAS")
            val keyPw = secret("RELEASE_KEY_PASSWORD")
            if (storePath != null && storePw != null && alias != null && keyPw != null) {
                storeFile = file(storePath)
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "COST_SAFE_MODE", "true")
        }
        release {
            buildConfigField("boolean", "COST_SAFE_MODE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) {
                signingConfig = cfg
            }
        }
        create("staging") {
            initWith(getByName("release"))
            buildConfigField("boolean", "COST_SAFE_MODE", "true")
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Firebase (version managed by BOM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    add("stagingImplementation", libs.firebase.appcheck.debug)
    implementation(libs.androidx.security.crypto)

    // Coroutines for Firebase .await() support
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.android)
    // Credential Manager — replaces legacy GoogleSignIn API
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    // play-services-auth kept for transitive deps (e.g. phone auth)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.location)
    implementation(libs.lottie)
    implementation(libs.coil)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.espresso.contrib)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
