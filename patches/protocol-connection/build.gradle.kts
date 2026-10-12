// Patched build of org.cloudburstmc.protocol:bedrock-connection, pinned in the version catalog. The patched classes are derived from the upstream
// version recorded in upstream-base.txt; verifyUpstreamBase (run by check and compileJava) fails when the catalog version moves away from it.
// The jar is the upstream jar with the classes under src/main/java replaced by their patched versions.
// See patches/README.md for what is changed and why.

plugins {
    `java-library`
}

group = "org.cloudburstmc.protocol"
version = libs.versions.protocol.connection.get() + "-geyser"

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val upstream: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    upstream(libs.protocol.connection)

    compileOnly(libs.protocol.connection)
    compileOnly(libs.raknet) // Ensure protocol does not pull in an older RakNet
    compileOnly(libs.netty.handler)
    compileOnly(libs.checker.qual)

    // Runtime dependencies of the upstream artifact
    api(libs.protocol.codec)
    api(project(":patched-network-raknet"))
    api("io.airlift:aircompressor:0.27")

    testImplementation(libs.protocol.connection)
    testImplementation(libs.raknet)
    testImplementation(libs.netty.handler)
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    // Patched classes must win over the upstream copies on the test classpath
    classpath = sourceSets.main.get().output + classpath
    jvmArgs("-Dio.netty.leakDetection.level=PARANOID")
}

tasks.jar {
    // Merge the untouched upstream classes, leaving out every class (including nested ones) that is patched here
    val patched = fileTree("src/main/java").files.map {
        it.relativeTo(file("src/main/java")).path.removeSuffix(".java")
    }
    from(upstream.elements.map { files -> files.map { zipTree(it) } }) {
        exclude { element -> patched.any { element.path == "$it.class" || element.path.startsWith("$it$") } }
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// Fails when the catalog moves to an upstream version the patched classes were not derived from
val verifyUpstreamBase by tasks.registering {
    group = "verification"
    description = "Checks that upstream-base.txt matches the bedrock-connection version in the version catalog."
    val baseFile = layout.projectDirectory.file("upstream-base.txt")
    val projectPath = project.path
    val pinned = libs.protocol.connection.map { it.version.orEmpty() }
    inputs.file(baseFile)
    inputs.property("pinned", pinned)
    doLast {
        val base = baseFile.asFile.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (base != pinned.get()) {
            throw GradleException(
                "The patched classes in $projectPath were derived from org.cloudburstmc.protocol:bedrock-connection:$base, but the version catalog " +
                    "pins ${pinned.get()}. Re-derive them from the ${pinned.get()} sources, then update ${baseFile.asFile}."
            )
        }
    }
}

tasks.check {
    dependsOn(verifyUpstreamBase)
}

// Also guard plain jar builds (e.g. :standalone:shadowJar), which never run check.
tasks.compileJava {
    dependsOn(verifyUpstreamBase)
}

// Consumers must see the merged jar, not just the few patched classes in the classes directory
listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) {
        outgoing.variants.removeIf { it.name == "classes" || it.name == "resources" }
    }
}
