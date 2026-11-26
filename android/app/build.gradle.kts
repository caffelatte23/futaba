plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
}

// google-services.json is gitignored (per-developer Firebase config); build without it when absent.
if (file("google-services.json").exists()) {
    apply(
        plugin =
            libs.plugins.google.services
                .get()
                .pluginId,
    )
}

android {
    namespace = "io.github.caffelatte23.futaba"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.caffelatte23.futaba"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        lintConfig = file("lint.xml")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // No baseline on purpose: every finding must be fixed.
}

// detekt 1.23 creates no type-resolution tasks for AGP 9, so wire each source set to its own
// compile classpath by hand. Without a classpath, type-based rules (ForbiddenMethodCall for Color
// literals) are silently skipped.
// Source set -> Kotlin compile task whose classpath matches it (main: debug; test: debug unit test).
val detektSourceSets =
    mapOf(
        "main" to "compileDebugKotlin",
        "test" to "compileDebugUnitTestKotlin",
        "androidTest" to "compileDebugAndroidTestKotlin",
    )

fun io.gitlab.arturbosch.detekt.Detekt.wire(
    sourceSet: String,
    compileTask: String,
) {
    val compile = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(compileTask)
    setSource(files("src/$sourceSet/kotlin", "src/$sourceSet/java"))
    include("**/*.kt", "**/*.kts")
    dependsOn(compile)
    classpath.setFrom(
        compile.map { it.libraries },
        compile.flatMap { it.destinationDirectory },
        androidComponents.sdkComponents.bootClasspath,
    )
    jvmTarget = "21"
}

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") { wire("main", detektSourceSets.getValue("main")) }
detektSourceSets.filterKeys { it != "main" }.forEach { (sourceSet, compileTask) ->
    val task =
        tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detekt${sourceSet.replaceFirstChar { it.uppercase() }}") {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            wire(sourceSet, compileTask)
        }
    tasks.named("check") { dependsOn(task) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(platform(libs.firebase.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)

    detektPlugins(libs.detekt.compose.rules)
}
