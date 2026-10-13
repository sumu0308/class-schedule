plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9.0 起 Kotlin 编译已内置，不能再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.classschedule.app"
    // 锁定在 API 36：更新的一批 AndroidX 库要求 compileSdk 37，但 AGP 9.4.1
    // 期望的平台目录名（android-37.0 / android-37.0-ext2）与实际发布的
    // android-37.2 对不上，会直接构建失败。等版本体系对齐后再升。
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.classschedule.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 14
        versionName = "0.12.0"
    }

    // 正式签名（M6 阶段接入）：届时由 tools/create-keystore.mjs 生成密钥并写入
    // keystore.properties，再在这里 create("release") 读取。
    // 现在 release 与 debug 都用项目内的调试密钥库，保证任何时候都能打出可安装的包。
    signingConfigs {
        getByName("debug") {
            // 用构建时推导出的绝对路径，避免相对路径被解析到 Gradle daemon 工作目录下；
            // 同时不写死盘符，项目整体移动位置也不用改。
            storeFile = rootProject.layout.projectDirectory.dir(".keystore").file("debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 代码裁剪留到 M6（发布准备）再做；届时开启 isMinifyEnabled +
            // isShrinkResources 并补 keep 规则（Room / Compose 通常已自带 consumer 规则）。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Room 的 schema 导出目录：配合 AppDatabase 的 exportSchema = true，
// 让每次结构变更都落一份可对比的 schema（迁移测试与 review 都依赖它）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
}
