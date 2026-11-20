import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}
kotlin { jvmToolchain(25) }
val backend = tasks.register<Exec>("prepareBackend") {
    workingDir(rootDir.parentFile)
    val args = listOf("-B", "-ntp", "-Dmaven.test.skip=true", "package", "org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=${rootDir.parentFile}/target/compose-libs")
    // Maven package/copy-dependencies do not remove obsolete classes or libraries.
    // Refresh only generated runtime outputs; retain backend test reports and user data.
    doFirst {
        delete(rootDir.parentFile.resolve("target/classes"), rootDir.parentFile.resolve("target/compose-libs"))
    }
    if (System.getProperty("os.name").startsWith("Windows")) commandLine(listOf("cmd", "/d", "/c", "mvnw.cmd") + args)
    else commandLine(listOf("./mvnw") + args)
    inputs.dir("../src/main"); inputs.file("../pom.xml")
    outputs.file("../target/professor-os-0.1.0-SNAPSHOT.jar"); outputs.dir("../target/compose-libs")
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(files("../target/professor-os-0.1.0-SNAPSHOT.jar").builtBy(backend))
    runtimeOnly(fileTree("../target/compose-libs") { include("*.jar") }.builtBy(backend))
    testImplementation(kotlin("test-junit"))
    testImplementation(compose.desktop.uiTestJUnit4)
    // Verify generated files with the same POI/PDFBox versions already packaged by Maven.
    testImplementation(fileTree("../target/compose-libs") { include("*.jar") }.builtBy(backend))
}
val desktopPackageName = "Professor OS"
val desktopPackageVersion = "1.1.0"
val desktopPackageDescription = "Online academic workspace for university professors with SQLite recovery"
val desktopUpgradeUuid = "b5680b84-3e0b-4a04-9550-8745858157d9"
val releaseOrigin = providers.environmentVariable("PROFESSOR_OS_RELEASE_SUPABASE_URL").orNull
val releaseKey = providers.environmentVariable("PROFESSOR_OS_RELEASE_SUPABASE_PUBLISHABLE_KEY").orNull
val validReleaseConfiguration = releaseOrigin?.matches(Regex("https://[a-z0-9]{20}\\.supabase\\.co")) == true &&
    releaseKey?.matches(Regex("sb_publishable_[A-Za-z0-9_-]+")) == true
compose.desktop {
    application {
        mainClass = "com.professoros.compose.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dprofessoros.packaged=true")
        if (validReleaseConfiguration) {
            jvmArgs += listOf("-Dprofessoros.supabase.url=$releaseOrigin", "-Dprofessoros.supabase.publishableKey=$releaseKey")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            description = desktopPackageDescription
            vendor = desktopPackageName
            // POI/PDFBox/JDBC use services and reflection: prefer a complete runtime to a fragile module guess.
            includeAllModules = true
            windows {
                iconFile.set(project.file("src/main/resources/icons/professor-os.ico"))
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                menuGroup = "Professor OS"
                // Stable application identity for future upgrades. Never regenerate this UUID.
                upgradeUuid = desktopUpgradeUuid
            }
        }
    }
}
tasks.test {
    dependsOn(backend)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("compose.test.output", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
tasks.withType<JavaExec>().configureEach {
    doFirst { jvmArgs("-Dprofessoros.packaged=false") }
    workingDir(rootDir.parentFile)
    providers.gradleProperty("previewWidth").orNull?.let { systemProperty("professoros.preview.width", it) }
    providers.gradleProperty("previewHeight").orNull?.let { systemProperty("professoros.preview.height", it) }
}

// Only native launchers select installed-user storage; Gradle development runs retain data/.
tasks.withType<AbstractJPackageTask>().configureEach {
    providers.gradleProperty("wixToolsetDir").orNull?.let { wixToolsetDir.set(file(it)) }
    doFirst {
        check(name != "packageExe") { "Use tools/package-windows.ps1 to build the installer with session preservation." }
        check(validReleaseConfiguration) {
            "Set the release Supabase HTTPS project URL and its sb_publishable_ key before packaging."
        }
    }
}

tasks.register<Exec>("packageWindowsRelease") {
    group = "distribution"
    description = "Build the Windows installer with legacy encrypted-session preservation"
    dependsOn("createDistributable")
    val releaseJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val image = layout.buildDirectory.dir("compose/binaries/main/app/$desktopPackageName")
    val destination = layout.buildDirectory.dir("compose/binaries/main/exe")
    doFirst {
        val resources = providers.gradleProperty("windowsInstallerResources").get()
        val wix = providers.gradleProperty("wixToolsetDir").get()
        val launcher = image.get().file("app/$desktopPackageName.cfg").asFile.readLines()
        check(validReleaseConfiguration && "java-options=-Dprofessoros.supabase.url=$releaseOrigin" in launcher &&
            "java-options=-Dprofessoros.supabase.publishableKey=$releaseKey" in launcher) {
            "The app image is missing the configured public Supabase settings."
        }
        environment("PATH", "$wix;${System.getenv("PATH")}")
        commandLine(
            releaseJdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile,
            "--type", "exe", "--app-image", image.get().asFile,
            "--dest", destination.get().asFile, "--resource-dir", resources,
            "--name", desktopPackageName, "--app-version", desktopPackageVersion,
            "--description", desktopPackageDescription, "--vendor", desktopPackageName,
            "--win-upgrade-uuid", desktopUpgradeUuid, "--win-per-user-install",
            "--win-dir-chooser", "--win-shortcut", "--win-menu", "--win-menu-group", desktopPackageName,
            "--icon", file("src/main/resources/icons/professor-os.ico")
        )
    }
}

tasks.register<JavaExec>("runOnline") {
    group = "application"
    description = "Open the authenticated Professor OS desktop"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.professoros.compose.OnlineDesktopLauncher")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runRecovery") {
    group = "application"
    description = "Open the local SQLite recovery workspace"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.professoros.compose.RecoveryDesktopLauncher")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaExec>().matching { it.name in setOf("run","runOnline") }.configureEach {
    val portalSettings = rootDir.parentFile.resolve("student-portal/.env.local")
    doFirst {
        if (portalSettings.isFile) {
            val settings = portalSettings.readLines().mapNotNull { line ->
                val fields = line.split('=',limit=2)
                fields.takeIf { it.size==2 }?.let { it[0].trim() to it[1].trim().trim('"','\'') }
            }.toMap()
            for (name in listOf("SUPABASE_URL","SUPABASE_PUBLISHABLE_KEY")) {
                val desktopKey = "PROFESSOR_OS_$name"
                val portalValue = settings["VITE_$name"]
                if (environment[desktopKey]?.toString().isNullOrBlank() && !portalValue.isNullOrBlank())
                    environment(desktopKey,portalValue)
            }
        }
    }
}

// Launch Java directly in a terminal so the opt-in live check can read its password without echo.
tasks.register("prepareAttendanceReadCheck") {
    group = "verification"
    description = "Prepare the interactive, read-only hosted attendance check"
    dependsOn(tasks.testClasses)
    val argsFile = layout.buildDirectory.file("attendance-read-check.args")
    val runtime = sourceSets.test.get().runtimeClasspath
    inputs.files(runtime)
    outputs.file(argsFile)
    doLast {
        val classpath = runtime.asPath.replace('\\', '/')
        argsFile.get().asFile.writeText("--enable-native-access=ALL-UNNAMED\n-classpath\n\"$classpath\"\ncom.professoros.compose.LiveAttendanceReadCheck\n")
    }
}
