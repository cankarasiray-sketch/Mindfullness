import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Builds the APK with Debian/Ubuntu's Android tools (aapt2, dx, zipalign, apksigner) and Maven Central
// artifacts only, for environments where Google's SDK and Maven repositories are unreachable.
//   apt install aapt dalvik-exchange apksigner zipalign
//   ../../gradlew -p tools/offline-apk assembleApk
plugins {
    kotlin("jvm") version "2.0.21"
}

val appDir: File = file(providers.gradleProperty("appDir").getOrElse("../../app"))
val namespace = "com.mindfullness.weather"

// Same scheme as app/build.gradle.kts, so offline and CI builds of a commit share one version.
val versionCode: Int = providers.gradleProperty("versionCode").orNull?.toInt()
    ?: runCatching {
        providers.exec { commandLine("git", "-C", appDir.path, "rev-list", "--count", "HEAD") }
            .standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)
val versionName = "1.0.$versionCode"

val buildTools = "/usr/lib/android-sdk/build-tools/debian"
val out = layout.buildDirectory.dir("apk")
val manifest = out.map { it.file("AndroidManifest.xml") }
val compiledResources = out.map { it.file("compiled/res.zip") }
val resourcesApk = out.map { it.file("resources.apk") }
val genDir = out.map { it.dir("gen") }
val aaptRules = out.map { it.file("aapt-rules.pro") }
val shrunkJar = out.map { it.file("shrunk.jar") }
val classesDex = out.map { it.file("classes.dex") }
val unalignedApk = out.map { it.file("unaligned.apk") }
val alignedApk = out.map { it.file("aligned.apk") }
val signedApk = out.map { it.file("app-release.apk") }

val androidAll by configurations.creating
val proguard by configurations.creating

dependencies {
    androidAll("org.robolectric:android-all:14-robolectric-10818077")
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    proguard("com.guardsquare:proguard-base:7.6.1")
    implementation("org.threeten:threetenbp:1.7.0:no-tzdb")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // dx cannot desugar invokedynamic, so lambdas and SAM conversions must be real classes.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xjdk-release=1.8")
    }
}

val prepareManifest by tasks.registering {
    val source = appDir.resolve("src/main/AndroidManifest.xml")
    inputs.file(source)
    inputs.property("namespace", namespace)
    outputs.file(manifest)
    doLast {
        // AGP injects the package from the namespace; aapt2 needs it in the manifest itself.
        val text = source.readText().replaceFirst("<manifest ", "<manifest package=\"$namespace\" ")
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
    }
}

val compileResources by tasks.registering(Exec::class) {
    val resDir = appDir.resolve("src/main/res")
    inputs.dir(resDir)
    outputs.file(compiledResources)
    doFirst { compiledResources.get().asFile.parentFile.mkdirs() }
    commandLine("$buildTools/aapt2", "compile", "--dir", resDir.path, "-o", compiledResources.get().asFile.path)
}

val linkResources by tasks.registering(Exec::class) {
    inputs.file(compiledResources).withPropertyName("resources")
    inputs.file(manifest).withPropertyName("manifest")
    inputs.files(androidAll).withPropertyName("framework")
    inputs.property("versionCode", versionCode)
    outputs.file(resourcesApk)
    outputs.file(aaptRules)
    outputs.dir(genDir)
    dependsOn(compileResources, prepareManifest)
    doFirst {
        genDir.get().asFile.deleteRecursively()
        genDir.get().asFile.mkdirs()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "link",
            "-I", androidAll.singleFile.path,
            "--manifest", manifest.get().asFile.path,
            "--min-sdk-version", "24", "--target-sdk-version", "34",
            "--version-code", versionCode.toString(), "--version-name", versionName,
            "--java", genDir.get().asFile.path,
            "--proguard", aaptRules.get().asFile.path,
            "--auto-add-overlay",
            "-o", resourcesApk.get().asFile.path,
            compiledResources.get().asFile.path,
        )
    })
    executable("$buildTools/aapt2")
}

sourceSets.main {
    kotlin.srcDir(appDir.resolve("src/main/java"))
    java.srcDir(files(genDir).builtBy(linkResources))
}

val shrink by tasks.registering(JavaExec::class) {
    val runtime = configurations.runtimeClasspath
    inputs.files(tasks.jar).withPropertyName("appJar")
    inputs.files(runtime).withPropertyName("runtime")
    inputs.file("proguard.pro")
    inputs.file(aaptRules)
    outputs.file(shrunkJar)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    argumentProviders.add(CommandLineArgumentProvider {
        val javaHome = System.getProperty("java.home")
        listOf("-injars", tasks.jar.get().archiveFile.get().asFile.path) +
            runtime.get().files.filter { it.name.endsWith(".jar") }.flatMap { listOf("-injars", "${it.path}(!META-INF/**)") } +
            listOf(
                "-outjars", shrunkJar.get().asFile.path,
                "-libraryjars", "${androidAll.singleFile.path}(!java/**)",
                "-libraryjars", "$javaHome/jmods/java.base.jmod(!**.jar;!module-info.class)",
                "-include", aaptRules.get().asFile.path,
                "-include", file("proguard.pro").path,
            )
    })
    doFirst { shrunkJar.get().asFile.delete() }
}

val dex by tasks.registering(Exec::class) {
    inputs.file(shrunkJar)
    outputs.file(classesDex)
    dependsOn(shrink)
    commandLine(
        "java", "-jar", "$buildTools/lib/dx.jar", "--dex", "--min-sdk-version=24",
        "--output=${classesDex.get().asFile.path}", shrunkJar.get().asFile.path,
    )
}

val packageApk by tasks.registering(Exec::class) {
    inputs.file(resourcesApk)
    inputs.file(classesDex)
    outputs.file(unalignedApk)
    dependsOn(linkResources, dex)
    workingDir(out)
    doFirst { resourcesApk.get().asFile.copyTo(unalignedApk.get().asFile, overwrite = true) }
    // aapt (v1) appends classes.dex without touching the uncompressed resources.arsc.
    commandLine("$buildTools/aapt", "add", "-k", unalignedApk.get().asFile.path, classesDex.get().asFile.path)
}

val alignApk by tasks.registering(Exec::class) {
    inputs.file(unalignedApk)
    outputs.file(alignedApk)
    dependsOn(packageApk)
    commandLine("$buildTools/zipalign", "-p", "-f", "4", unalignedApk.get().asFile.path, alignedApk.get().asFile.path)
}

// The release key is private (GitHub Secrets in CI); pass it with -Pkeystore=… -PkeystorePassword=…
// or the SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD environment variables. Without it the APK is
// signed with a throwaway local key, fine for testing but unable to update an installed release.
val releaseKeystore: String? = providers.gradleProperty("keystore").orNull ?: System.getenv("SIGNING_STORE_FILE")
val testKeystore = layout.buildDirectory.file("test-signing.jks")

val createTestKey by tasks.registering(Exec::class) {
    onlyIf { releaseKeystore == null && !testKeystore.get().asFile.exists() }
    executable("${System.getProperty("java.home")}/bin/keytool")
    args(
        "-genkeypair", "-storetype", "PKCS12", "-keystore", testKeystore.get().asFile.path,
        "-storepass", "android", "-keypass", "android", "-alias", "havauyari",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Hava Uyari Test",
    )
}

val assembleApk by tasks.registering(Exec::class) {
    val keystore = releaseKeystore ?: testKeystore.get().asFile.path
    val password = providers.gradleProperty("keystorePassword").orNull ?: System.getenv("SIGNING_STORE_PASSWORD")
        ?: if (releaseKeystore == null) "android" else error("keystorePassword (or SIGNING_STORE_PASSWORD) is required with a keystore")
    val alias = providers.gradleProperty("keyAlias").orNull ?: System.getenv("SIGNING_KEY_ALIAS") ?: "havauyari"
    inputs.file(alignedApk)
    inputs.file(keystore)
    outputs.file(signedApk)
    dependsOn(alignApk, createTestKey)
    commandLine(
        "$buildTools/apksigner", "sign",
        "--ks", keystore, "--ks-pass", "pass:$password", "--ks-key-alias", alias, "--key-pass", "pass:$password",
        // JAR (v1) signing too, although Android 7 needs only v2: some vendor installers still check it.
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
        "--out", signedApk.get().asFile.path, alignedApk.get().asFile.path,
    )
    doLast {
        println("APK: ${signedApk.get().asFile} ($versionName, versionCode $versionCode)")
        if (releaseKeystore == null) println("Uyarı: test anahtarıyla imzalandı; yüklü sürümün üzerine kurulamaz.")
    }
}
