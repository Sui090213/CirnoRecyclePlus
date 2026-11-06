import com.android.build.api.dsl.ApplicationExtension
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 公开测试签名密钥: 放在仓库里, 保证本地构建 / CI 构建出来的 APK 签名一致, 可以互相覆盖升级.
// 这不是私钥保密的商业应用, 密钥和口令都公开在 signing/README.md 里.
val releaseKeystore = rootProject.file("signing/cirno-release.jks")

configure<ApplicationExtension> {
    namespace = "nep.timeline.cirno"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flyingr.cirnorecycle"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = "flyingr123"
                keyAlias = "cirno"
                keyPassword = "flyingr123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.commons.io)
    compileOnly(libs.api)
    implementation(libs.service)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    implementation(libs.commons.lang3)
    implementation(libs.chrisbanes.haze)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 底部三页 / 应用详情页切换需要 AnimatedContent；material3 只带 animation-core.
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.material.icons.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    val libsuVersion = "6.0.0"
    implementation("com.github.topjohnwu.libsu:core:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:service:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:io:$libsuVersion")
}