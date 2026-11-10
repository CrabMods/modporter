plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
    jacoco
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.modporter"
version = "0.4.0"

repositories {
    mavenCentral()
}

val testKitRuntimeOnly by configurations.creating
val javaParserVersion = "3.26.4"

dependencies {
    // Java AST parsing
    implementation("com.github.javaparser:javaparser-core:$javaParserVersion")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:$javaParserVersion")

    // CLI framework
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // JSON serialization for mapping rules
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines for parallel processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("net.java.dev.jna:jna:5.13.0")
    testCompileOnly(gradleTestKit())
    testKitRuntimeOnly(gradleTestKit())
}

// TestKit contributes Gradle's older parser to compilation only. Keep the
// parser API used to compile tests identical to the implementation/runtime API.
sourceSets.named("test") {
    val alignedClasspath = compileClasspath.filter { file ->
        !file.name.startsWith("javaparser-core-") ||
            file.name == "javaparser-core-$javaParserVersion.jar"
    }
    compileClasspath = files(
        alignedClasspath.filter { it.name == "javaparser-core-$javaParserVersion.jar" },
        alignedClasspath
    )
}

application {
    mainClass.set("com.modporter.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // The full structural suite retains whole-project JavaParser graphs under JaCoCo.
    maxHeapSize = "4g"
    filter {
        excludeTestsMatching("com.modporter.integration.CompilationVerificationTest")
        excludeTestsMatching("com.modporter.transforms.LegacyPlantResolverTaskDependencyTest")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

tasks.register<Test>("testKitTest") {
    description = "Runs Gradle TestKit-backed integration tests in an isolated task"
    group = "verification"

    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + testKitRuntimeOnly
    filter {
        includeTestsMatching("com.modporter.integration.CompilationVerificationTest")
        includeTestsMatching("com.modporter.transforms.LegacyPlantResolverTaskDependencyTest")
    }
    shouldRunAfter(tasks.test)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

tasks.named("check") {
    dependsOn("testKitTest")
}

fun Test.defaultEnvironment(name: String, value: String) {
    if (System.getenv(name).isNullOrBlank()) {
        environment(name, value)
    }
}

fun realModBenchmarkCaseIds(): String =
    file("src/test/resources/benchmarks/real-mods.tsv")
        .takeIf { it.exists() }
        ?.readLines()
        ?.asSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.map { it.split('\t') }
        ?.filter { columns -> columns.size >= 7 && !columns[2].equals("missing", ignoreCase = true) }
        ?.map { columns -> columns[0] }
        ?.joinToString(",")
        ?: ""

tasks.register<Test>("realModBenchmark") {
    dependsOn("modporterTestingEnsure")
    description = "Ports real mod benchmark targets from src/test/resources/benchmarks/real-mods.tsv"
    group = "verification"
    // Whole-project JavaParser snapshots exceed Gradle Test's default 512 MiB heap for Create.
    maxHeapSize = "8g"
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform()
    maxHeapSize = System.getenv("MODPORTER_BENCHMARK_MAX_HEAP") ?: "4g"
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = false
    }
    environment("MODPORTER_REAL_MOD_TEST", "true")
    filter {
        includeTestsMatching("com.modporter.integration.RealModBenchmarkTest")
    }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}

tasks.register<Test>("strictRealModBenchmark") {
    dependsOn("modporterTestingEnsure")
    description = "Ports real mod benchmark targets and requires strict runtime success gates"
    group = "verification"
    maxHeapSize = "8g"
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = false
    }

    useJUnitPlatform()
    maxHeapSize = System.getenv("MODPORTER_BENCHMARK_MAX_HEAP") ?: "4g"
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = false
    }
    environment("MODPORTER_REAL_MOD_TEST", "true")
    defaultEnvironment("MODPORTER_BENCHMARK_STRICT_RUNTIME", "true")
    defaultEnvironment("MODPORTER_BENCHMARK_CASES", realModBenchmarkCaseIds())
    defaultEnvironment("MODPORTER_BENCHMARK_TIMEOUT_SECONDS", "540")
    defaultEnvironment("MODPORTER_BENCHMARK_UPDATE_README", "true")
    filter {
        includeTestsMatching("com.modporter.integration.RealModBenchmarkTest")
    }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("modporter-version.properties") {
        expand("version" to project.version.toString())
    }
    from("LICENSE") { into("META-INF/modporter") }
    from("NOTICE") { into("META-INF/modporter") }
    from("third_party") { into("META-INF/modporter/third-party") }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Main-Class" to "com.modporter.cli.MainKt",
            "Implementation-Title" to "ModPorter",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

apply(from = "gradle/modporter-testing.gradle")
