import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.netops.handbook"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }

    sourceSets.getByName("main") {
        /* P2-6 方案 A（2026-11-09 实测后拍板执行）：4 个纯 UI Compose 文件从未
           挂载（NetOpsRoot.createNetOpsRoot 无调用方；MainActivity 只用
           NetOpsShell 状态桥），移出编译实测减重 480KB（6.49→6.00MB）。
           文件原位保留；Compose 主线（HANDOFF-Compose迁移）恢复时删除本段
           exclude 即可复原。 */
        java {
            exclude("com/netops/handbook/lens/NetOpsRoot.kt",
                    "com/netops/handbook/lens/NetOpsDock.kt",
                    "com/netops/handbook/lens/NetOpsTopBar.kt",
                    "com/netops/handbook/lens/DockBackdrop.kt")
        }
    }

    defaultConfig {
        applicationId = "com.netops.handbook"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "2.1.4"
    }

    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { load(keystorePropsFile.inputStream()) }
                storeFile = rootProject.file(props.getProperty("storeFile")!!)
                storePassword = props.getProperty("storePassword")!!
                keyAlias = props.getProperty("keyAlias")!!
                keyPassword = props.getProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/NOTICE",
            "META-INF/AL.2.0",
            "META-INF/LGPL*.txt"
        )
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
