import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Builds the APK with Debian/Ubuntu's Android tools (aapt2, dx, zipalign, apksigner) and Maven Central
// artifacts only, for environments where Google's SDK and Maven repositories are unreachable.
plugins {
    kotlin("jvm") version "2.0.21"
}

val appDir: File = file(providers.gradleProperty("appDir").getOrElse("../../app"))
val applicationId: String = providers.gradleProperty("applicationId").getOrElse("com.mindfullness.weather")
val versionCode: String = providers.gradleProperty("versionCode").getOrElse("1")
val versionName: String = providers.gradleProperty("versionName").getOrElse("1.0.$versionCode")
val buildTools = "/usr/lib/android-sdk/build-tools/debian"
val out = layout.buildDirectory.dir("apk")

val androidAll by configurations.creating
val proguard by configurations.creating

dependencies {
    androidAll("org.robolectric:android-all:14-robolectric-10818077")
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    proguard("com.guardsquare:proguard-base:7.6.1")
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

val androidAllJar = providers.provider { androidAll.singleFile }
val manifest = out.map { it.file("AndroidManifest.xml") }
val genDir = out.map { it.dir("gen") }

val prepareManifest by tasks.registering {
    val source = appDir.resolve("src/main/AndroidManifest.xml")
    inputs.file(source)
    outputs.file(manifest)
    doLast {
        // AGP injects the package from the namespace; aapt2 needs it in the manifest itself.
        val text = source.readText().replaceFirst("<manifest ", "<manifest package=\"$applicationId\" ")
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
    }
}

val linkResources by tasks.registering(Exec::class) {
    dependsOn(prepareManifest)
    val resDir = appDir.resolve("src/main/res")
    inputs.dir(resDir)
    outputs.dir(genDir)
    outputs.file(out.map { it.file("resources.apk") })
    doFirst {
        val o = out.get().asFile
        o.resolve("compiled").deleteRecursively()
        o.resolve("compiled").mkdirs()
        genDir.get().asFile.deleteRecursively()
        genDir.get().asFile.mkdirs()
        project.exec { commandLine("$buildTools/aapt2", "compile", "--dir", resDir.path, "-o", o.resolve("compiled/res.zip").path) }
    }
    commandLine(
        "$buildTools/aapt2", "link",
        "-I", androidAllJar.get().path,
        "--manifest", manifest.get().asFile.path,
        "--min-sdk-version", "26", "--target-sdk-version", "34",
        "--version-code", versionCode, "--version-name", versionName,
        "--java", genDir.get().asFile.path,
        "--proguard", out.get().file("aapt-rules.pro").asFile.path,
        "--auto-add-overlay",
        "-o", out.get().file("resources.apk").asFile.path,
        out.get().file("compiled/res.zip").asFile.path,
    )
}

sourceSets.main {
    kotlin.srcDir(appDir.resolve("src/main/java"))
    java.srcDir(genDir)
}
tasks.compileKotlin { dependsOn(linkResources) }
tasks.compileJava { dependsOn(linkResources) }

val shrink by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar, linkResources)
    val shrunk = out.map { it.file("shrunk.jar") }
    outputs.file(shrunk)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    doFirst {
        val runtime = configurations.runtimeClasspath.get().files.filter { it.name.endsWith(".jar") }
        val javaHome = System.getProperty("java.home")
        args(
            "-injars", tasks.jar.get().archiveFile.get().asFile.path,
            *runtime.flatMap { listOf("-injars", "${it.path}(!META-INF/**)") }.toTypedArray(),
            "-outjars", shrunk.get().asFile.path,
            "-libraryjars", "${androidAllJar.get().path}(!java/**)",
            "-libraryjars", "$javaHome/jmods/java.base.jmod(!**.jar;!module-info.class)",
            "-include", out.get().file("aapt-rules.pro").asFile.path,
            "-include", file("proguard.pro").path,
        )
    }
}

val dex by tasks.registering(Exec::class) {
    dependsOn(shrink)
    outputs.file(out.map { it.file("classes.dex") })
    commandLine(
        "java", "-jar", "$buildTools/lib/dx.jar", "--dex", "--min-sdk-version=26",
        "--output=${out.get().file("classes.dex").asFile.path}", out.get().file("shrunk.jar").asFile.path,
    )
}

val assembleApk by tasks.registering(Exec::class) {
    dependsOn(dex, linkResources)
    val o = out.get().asFile
    doFirst {
        o.resolve("unaligned.apk").delete()
        o.resolve("resources.apk").copyTo(o.resolve("unaligned.apk"))
        project.exec { workingDir = o; commandLine("zip", "-q", "unaligned.apk", "classes.dex") }
        o.resolve("aligned.apk").delete()
        project.exec { commandLine("$buildTools/zipalign", "-p", "-f", "4", o.resolve("unaligned.apk").path, o.resolve("aligned.apk").path) }
    }
    val keystore = providers.gradleProperty("keystore").getOrElse(appDir.resolve("signing/havauyari.jks").path)
    val password = providers.gradleProperty("keystorePassword").getOrElse("havauyari")
    val alias = providers.gradleProperty("keyAlias").getOrElse("havauyari")
    commandLine(
        "$buildTools/apksigner", "sign",
        "--ks", keystore, "--ks-pass", "pass:$password", "--ks-key-alias", alias, "--key-pass", "pass:$password",
        "--out", o.resolve("app-release.apk").path, o.resolve("aligned.apk").path,
    )
}
