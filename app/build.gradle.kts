import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

fun fetchGitCommitHash(): String {
    val process =
        ProcessBuilder("git", "rev-parse", "--verify", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

val gitCommitHash = fetchGitCommitHash()
val releaseVersion = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
// 标签构建优先采用发布标签；本地与普通分支构建默认使用当前正式版版本号。
val normalizedVersionName = releaseVersion?.removePrefix("v") ?: "1.0.0"
val keyProps = Properties()
val releaseKeyPropsFile: File = rootProject.file("signature/keystore_release.properties")
val debugKeyPropsFile: File = rootProject.file("signature/keystore.properties")


if (releaseKeyPropsFile.exists()) {
    println("Loading keystore properties from ${releaseKeyPropsFile.absolutePath}")
    keyProps.load(FileInputStream(releaseKeyPropsFile))
} else if (debugKeyPropsFile.exists()) {
    keyProps.load(FileInputStream(debugKeyPropsFile))
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "me.ash.reader"
        minSdk = 26
        targetSdk = 34
        // v1.0.0 正式版递增安装版本号，确保可覆盖升级此前的开发版本。
        versionCode = 50
        versionName = normalizedVersionName

        buildConfigField(
            "String",
            "USER_AGENT_STRING",
            "\"OrigRead/${versionName}(${versionCode})\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 发布包仅面向主流 64 位 ARM 设备，避免将 ML Kit 的多套大型原生库重复打入 APK。
        ndk { abiFilters += "arm64-v8a" }

        ksp { arg("room.incremental", "true") }
    }

    // Room Gradle Plugin 要求 schemaDirectory 配置在 android 层；这样 KSP/compile task 才会把 schema
    // 作为正式 input/output 跟踪并稳定导出。KSP 可直接写入该目录，后续 copyRoomSchemas 无额外来源时允许 NO-SOURCE。
    room { schemaDirectory("$projectDir/schemas") }

    flavorDimensions.addAll(listOf("edition", "channel"))
    productFlavors {
        create("standard") {
            isDefault = true
            dimension = "edition"
            buildConfigField("String", "EDITION", "\"standard\"")
        }
        create("llm") {
            dimension = "edition"
            applicationIdSuffix = ".llm"
            buildConfigField("String", "EDITION", "\"llm\"")
        }
        create("github") {
            isDefault = true
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
        }
        create("fdroid") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"fdroid\"")
        }
        create("googlePlay") {
            dimension = "channel"
            applicationIdSuffix = ".google.play"
            buildConfigField("String", "CHANNEL", "\"googlePlay\"")
        }
    }
    signingConfigs {
        create("release") {
            keyAlias = keyProps["keyAlias"] as String?
            keyPassword = keyProps["keyPassword"] as String?
            storeFile = keyProps["storeFile"]?.let { file(it as String) }
            storePassword = keyProps["storePassword"] as String?
        }
    }
    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation")) }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        all { signingConfig = signingConfigs.getByName("release") }
    }
    applicationVariants.all {
        val edition = productFlavors.firstOrNull { it.dimension == "edition" }?.name ?: "standard"
        val artifactName = if (edition == "llm") "OrigRead-X" else "OrigRead"
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (releaseVersion != null) {
                    "${artifactName}-${releaseVersion}.apk"
                } else {
                    "${artifactName}-${defaultConfig.versionName}-${gitCommitHash}.apk"
                }
        }
    }
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        resources.excludes.add("rome-utils-*.jar")
    }
    androidResources { generateLocaleConfig = true }
    composeCompiler { featureFlags = setOf(ComposeFeatureFlag.PausableComposition) }
    namespace = "me.ash.reader"
}

aboutLibraries {
    // 构建时不访问 GitHub API，避免匿名请求限额导致本地编译阻塞。
    offlineMode = true
    excludeFields = arrayOf("generated")
}

dependencies {
    // 应用内 APK 自更新仅属于 GitHub 渠道；F-Droid / Google Play 不打入下载库及其 Manifest 组件。
    "githubImplementation"(libs.app.updater)

    // AboutLibraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose)

    // Compose
    implementation(libs.compose.html)
    implementation(platform(libs.compose.bom.alpha))
    implementation(libs.androidx.ui.graphics)
    androidTestImplementation(platform(libs.compose.bom.stable))
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    androidTestImplementation(libs.compose.ui.test.junit4)
    implementation(libs.compose.material3)

    // LLM edition 富文本公式渲染。Standard 不引入，避免普通阅读版承担额外公式字体/解析依赖。
    "llmImplementation"(libs.latex.base)
    "llmImplementation"(libs.latex.parser)
    "llmImplementation"(libs.latex.renderer)
    "llmImplementation"(libs.codehigh.parser)
    "llmImplementation"(libs.codehigh.render)
    "llmImplementation"(libs.diagram.render)

    // Coil
    implementation(libs.coil.base)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)

    // Hilt
    implementation(libs.hilt.work)
    implementation(libs.hilt.android)
    androidTestImplementation(platform(libs.compose.bom.stable))
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel)

    // AndroidX
    implementation(libs.android.svg)
    implementation(libs.opml.parser)
    implementation(libs.readability4j)
    implementation(libs.rome)
    implementation(libs.rome.modules)
    implementation(libs.telephoto)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.profileinstaller)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.room.paging)
    implementation(libs.room.common)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.paging.common.ktx)
    implementation(libs.paging.runtime.ktx)
    implementation(libs.paging.compose)
    implementation(libs.browser)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.appwidget.preview)
    implementation(libs.glance.material3)
    implementation(libs.glance.preview)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    //    implementation(libs.compose.material3.adaptive.navigation3)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigationevent)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.material3.adaptive.layout)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.jvm)
}
