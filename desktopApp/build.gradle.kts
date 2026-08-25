import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.slte"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.snakeyaml)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
}

val generatedResources = layout.buildDirectory.dir("generated/desktop-resources")
val generateDesktopConfig by tasks.registering {
    val output = generatedResources.map { it.file("slte-desktop.properties") }
    val apiBaseUrl = providers.environmentVariable("SLTE_API_BASE_URL").orElse("https://api.example.com")
    val apiType = providers.environmentVariable("SLTE_API_TYPE").orElse("xiaov2b")
    val remoteConfigUrls = providers.environmentVariable("SLTE_REMOTE_CONFIG_URLS").orElse("")
    val allowedDomains = providers.environmentVariable("SLTE_ALLOWED_DOMAINS").orElse("")

    inputs.property("apiBaseUrl", apiBaseUrl)
    inputs.property("apiType", apiType)
    inputs.property("remoteConfigUrls", remoteConfigUrls)
    inputs.property("allowedDomains", allowedDomains)
    outputs.file(output)

    doLast {
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            buildString {
                appendLine("apiBaseUrl=${apiBaseUrl.get()}")
                appendLine("apiType=${apiType.get()}")
                appendLine("remoteConfigUrls=${remoteConfigUrls.get()}")
                appendLine("allowedDomains=${allowedDomains.get()}")
            }
        )
    }
}

sourceSets.main {
    resources.srcDir(generatedResources)
    resources.srcDir(rootProject.file("app/src/main/res/mipmap-xxxhdpi"))
}

tasks.named("processResources") {
    dependsOn(generateDesktopConfig)
}

compose.desktop {
    application {
        mainClass = "com.slte.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "SLTE"
            packageVersion = project.version.toString()
            description = "SLTE desktop proxy client powered by mihomo"
            copyright = "© 2026 SLTE"
            vendor = "SLTE"
            // jdeps 无法从 Kotlin 字节码稳定识别 HttpClient/JNA 的反射依赖，显式保留。
            modules("java.net.http", "jdk.unsupported")
            windows {
                menuGroup = "SLTE"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "9f43ee42-6a40-4d6d-bef6-1c936d73966b"
            }

            macOS {
                bundleID = "com.slte.desktop"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "11.0"
            }
        }
    }
}
