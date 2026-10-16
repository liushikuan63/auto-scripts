plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.farmbot"
    // v1.4.3: 目标适配Android 16 (API 36)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.farmbot"
        minSdk = 29
        targetSdk = 36
        // v1.4.33: 一键务农图标点击偏移按实测(cx-40,cy-58)命中圆形按钮主体(此前cy-80偏上未打开面板)
        // v1.4.34: 修复引擎停止后由 WorkManager 兜底任务/开机广播静默隐式启动的回归
        // v1.4.35: ①修复moveTowardsStatue方向(向上朝雕像,此前向下推无法靠近) ②流程控制: 默认自家农场优先 + 优先偷菜开关
        // v1.4.36: 开垦主动化：配置门控(expandAlways/upgradeExpandAfter真正生效) + 需开垦时先确认在自家农场再主动找开垦入口
        // v1.4.37: ①一键务农修复(点齿轮开设置面板→确定) ②成熟检测修复 ③种植配置(平日/周末作物+卡双倍) ④地块走位(开垦/祝福/偷菜先走到对应地块)
        versionCode = 42
        versionName = "1.4.37"
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
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 禁用 coreLibraryDesugaring，绕过 GraalVM jdkImage transform 在 jlink --disable-plugin system-modules 失败的问题
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 单测里 android.util.Log 等框架方法返回默认值而不是抛 not mocked，
    // FarmRuntime.log 依赖 Log.d，处理器级单测需要它
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OpenCV Android - 通过本地 AAR 引入 (放在 app/libs/*.aar)
    // 若无 AAR 则继续使用源码内 org.opencv.* 的 Stub 类进行编译
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Google ML Kit OCR（中文识别）
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // Jetpack Compose BOM 锁定版本
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation Compose (页面路由：采集模板 / 设置 / 调试工具)
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // DataStore (替代 SharedPreferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Timber 日志
    implementation("com.jakewharton.timber:timber:5.0.1")

    // WorkManager（双通道兜底定时浇水）
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Kotlin Serialization（策略插件 JSON 持久化）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}