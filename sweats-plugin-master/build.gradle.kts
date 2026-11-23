import java.util.zip.ZipFile

plugins {
    java
}

group = "io.github.near"
version = "1.0.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")

    // Paper downloads the SQLite driver listed under `libraries` in plugin.yml, so only the
    // compile-time types are needed here.
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")

    // SimpleLogin used jBCrypt; keep it around so existing hashes can still be verified.
    // The class files are bundled into the Sweats jar below, because Paper does not ship it.
    implementation("org.mindrot:jbcrypt:0.4")

    // Bundled for the embedded Votifier listener's protocol v2 packets. Paper also has Gson,
    // but keeping our own copy avoids depending on a particular server build exposing it.
    implementation("com.google.code.gson:gson:2.14.0")

    // Needed at test runtime too: the jumpscare catalog builds Bukkit Particles and Keys,
    // and the auth store tests exercise a real SQLite file.
    testImplementation("io.papermc.paper:paper-api:26.3.build.249-stable")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("net.kyori:adventure-api:5.2.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
    // Defense in depth: credential databases are never shipped. The resource guard below fails
    // the build if one is ever added back to the source tree.
    exclude("legacy-passwords.db", "passwords.db", "auth.db")
}

tasks.jar {
    archiveBaseName.set("Sweats")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("legacy-passwords.db", "passwords.db", "auth.db")

    // Bundle the few runtime dependencies (currently jBCrypt) so the plugin is a single jar.
    // paper-api and sqlite-jdbc stay compileOnly because the server provides them.
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}

// Targeted guard against credential material being committed or bundled. This checks the known
// password-database filenames rather than banning every *.db file, because some legitimate
// resources (for example SQLite fixtures) may use that extension.
val credentialFileNames = listOf("legacy-passwords.db", "passwords.db", "auth.db")

val verifyNoCredentialMaterial by tasks.registering {
    group = "verification"
    description = "Fails if a password database is present in plugin resources or the jar."
    dependsOn(tasks.jar)
    val resources = layout.projectDirectory.dir("src/main/resources")
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    inputs.dir(resources)
    inputs.file(jarFile)
    doLast {
        val bundled = resources.asFile.walkTopDown()
            .filter { it.isFile && it.name in credentialFileNames }
            .map { it.relativeTo(resources.asFile).path }
            .toList()
        if (bundled.isNotEmpty()) {
            throw GradleException(
                "Refusing to build: credential database(s) found in plugin resources: $bundled"
            )
        }
        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries().asSequence()
                .map { it.name }
                .filter { name -> credentialFileNames.any { name == it || name.endsWith("/$it") } }
                .toList()
            if (entries.isNotEmpty()) {
                throw GradleException(
                    "Refusing to build: credential database(s) found in the plugin jar: $entries"
                )
            }
        }
    }
}

tasks.check {
    dependsOn(verifyNoCredentialMaterial)
}

tasks.test {
    useJUnitPlatform()
}
