/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.modern

import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream

/** Explicit actual-production OSS qualification, enrolled only by realProductionOssTest. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealProductionOssTest {
    private lateinit var workspace: File
    private val invocations = mutableSetOf<String>()
    private lateinit var output: File
    private lateinit var requestLog: File
    private lateinit var base: URI
    private lateinit var keyEnvironment: Map<String, String>
    private lateinit var publicKeys: PGPPublicKeyRingCollection
    private val repo = ModernTestPaths.checkoutRoot

    @BeforeAll
    fun assemble() {
        require(System.getenv("NEXUS_PRODUCTION_OSS_ENABLED") == "true") { "Explicit production OSS tests require --production harness inputs (NEXUS_PRODUCTION_OSS_ENABLED)." }
        fun input(name: String): String =
            requireNotNull(System.getenv(name)) { "Missing enabled real OSS input $name" }
        base = URI(input("NEXUS_PRODUCTION_OSS_URL"))
        require(base.toString().matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/nexus"))) { "Real OSS requires the disposable loopback endpoint" }
        val run = File(input("NEXUS_PRODUCTION_OSS_RUN_DIRECTORY")).canonicalFile
        require(run.toPath().startsWith(File(repo, ".agents/nexus-e2e/runs").canonicalFile.toPath())) { "Expected owned real OSS run directory" }
        val secret = File(input("LOCAL_E2E_KEY_FILE")).canonicalFile
        val password = File(input("LOCAL_E2E_PASSWORD_FILE")).canonicalFile
        require(secret.parentFile.name.startsWith("n2g-") && secret.parentFile.parentFile == File(repo, ".agents").canonicalFile && password.parentFile == secret.parentFile) { "Expected private ephemeral signing directory" }
        require(run.isDirectory && secret.isFile && password.isFile && base.port in 1..65535) { "Missing owned production run/private signing files" }
        keyEnvironment = mapOf("BundleFixture_PGP_KEY" to secret.readText(), "BundleFixture_PGP_PASSWORD" to password.readText())
        publicKeys = File(run, "signing-public-key.asc").inputStream().use { PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(it), JcaKeyFingerprintCalculator()) }
        requestLog = File(run, "installation/sonatype-work/nexus/logs/request.log")
        output = File(run, "production-evidence").apply { mkdirs() }
        workspace = File(run, "production-work").apply { mkdirs() }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `signed Maven release matches independent native bytes with real cache hit uploads`(runtime: ModernRuntime) {
        val name = "sample-maven-" + runtime.version.replace(".", "")
        val project = consumer()
        val producer = File(project, name).apply { mkdirs() }
        val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
        val script = producerScript("maven", "1.0.0")
        listOf(producer, control).forEach {
            copySources("maven", it)
            File(it, "build.gradle").writeText(script)
        }
        File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
        File(control, "build.gradle").appendText("publishing.repositories.maven { name = 'control'; url = layout.buildDirectory.dir('native-control') }\n")
        ModernGradleBuild(control, false, runtime, keyEnvironment).storeAndReuse("publishMavenJavaPublicationToControlRepository")
        configure(project, listOf(name), "maven", "releases")
        File(project, "build.gradle").writeText("version = '1.0.0'\n")
        val prefix = "io/github/gradle-nexus-e2e/$name/1.0.0/"
        val native = File(control, "build/native-control/$prefix").walkTopDown().filter { it.isFile }.associateBy { it.name }
        assertThat(native.keys.any { it.endsWith(".module") }).isTrue()
        var before = requestLines().size
        ModernGradleBuild(project, false, runtime, keyEnvironment).storeAndReuse(":$name:publishToProbe") { result ->
            freshRootUpload(project, result)
            assertThat(result.tasks.map { it.path }.none { it.contains("Ivy") }).isTrue()
            val writes = requestLines().drop(before).filter { it.contains("PUT /nexus/content/repositories/releases/$prefix") }
            assertThat(writes).describedAs("real Nexus receives fresh root uploads on both cache store and hit").isNotEmpty()
            before = requestLines().size
            archiveNative(control, runtime, "maven-release")
            verifyRelease("maven", name, "1.0.0", prefix, native, producer, runtime, writes)
        }
        val jar = File(output, "${runtime.version}/maven-release/$name-1.0.0.jar")
        listOf("pom", "module").forEach { consumeGradle("maven", it, name, "1.0.0", hash(jar.readBytes()), runtime) }
        File(output, "maven-release-${runtime.version}.properties").writeText("status=PASS\ngradle=${runtime.version}\nclassArtifact=true\ncacheStoreAndReuse=true\nfreshRemoteWrites=true\nconsumers=pom,module\nartifact=$name\nversion=1.0.0\nsha256=${hash(jar.readBytes())}\n")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `signed Ivy default layout retains native bytes and real metadata consumers`(runtime: ModernRuntime) {
        val name = "sample-ivy-" + runtime.version.replace(".", "")
        val project = consumer()
        val producer = File(project, name).apply { mkdirs() }
        val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
        val script = producerScript("ivy", "1.0.0") + "publishing.publications.ivy.descriptor.withXml { asNode().info[0].attributes().remove('publication') }\n"
        listOf(producer, control).forEach {
            copySources("ivy", it)
            File(it, "build.gradle").writeText(script)
        }
        File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
        File(control, "build.gradle").appendText("publishing.repositories.ivy { name = 'control'; url = layout.buildDirectory.dir('native-control'); layout 'maven' }\n")
        ModernGradleBuild(control, false, runtime, keyEnvironment).storeAndReuse("publishIvyPublicationToControlRepository")
        configure(project, listOf(name), "ivy", "releases")
        File(project, "build.gradle").writeText("version = '1.0.0'\n")
        val prefix = "io/github/gradle-nexus-e2e/$name/1.0.0/"
        val native = File(control, "build/native-control/$prefix").walkTopDown().filter { it.isFile }.associateBy { it.name }
        assertThat(native.keys.any { it.endsWith(".module") }).isTrue()
        var before = requestLines().size
        ModernGradleBuild(project, false, runtime, keyEnvironment).storeAndReuse(":$name:publishToProbe") { result ->
            freshRootUpload(project, result)
            assertThat(result.tasks.map { it.path }.none { it.contains("Maven") }).isTrue()
            assertThat(requestLines().drop(before).any { it.contains("PUT /nexus/content/repositories/releases/$prefix") }).describedAs("fresh real Ivy root uploads on store/hit").isTrue()
            before = requestLines().size
            assertThat(File(producer, "build/nexus-publishing/bundles/ivy/manifest.json").readText()).contains("gradle-module")
            archiveNative(control, runtime, "ivy-release")
            verifyRelease("ivy", name, "1.0.0", prefix, native, producer, runtime)
        }
        val jar = File(output, "${runtime.version}/ivy-release/$name-1.0.0.jar")
        listOf("descriptor", "module").forEach { consumeGradle("ivy", it, name, "1.0.0", hash(jar.readBytes()), runtime) }
        consumePublicIvy(name, "1.0.0", hash(jar.readBytes()), runtime)
        File(output, "ivy-release-${runtime.version}.properties").writeText("status=PASS\ngradle=${runtime.version}\nconsumers=descriptor,module\nsha256=${hash(jar.readBytes())}\n")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `custom Ivy layout matches native server acceptance or paired rejection`(runtime: ModernRuntime) {
        val name = "custom-ivy-" + runtime.version.replace(".", "")
        val artifactPattern = "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"
        val ivyPattern = "[organisation]/[module]/[revision]/ivy-[revision].xml"
        val patterns = "artifact '$artifactPattern'; ivy '$ivyPattern'; m2compatible = true"
        val project = consumer()
        val producer = File(project, name).apply { mkdirs() }
        val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
        val script = producerScript("ivy", "1.0.0") + "publishing.publications.ivy { descriptor.withXml { asNode().info[0].attributes().remove('publication') }; artifact(tasks.named('sourcesJar')) { name = 'custom-name'; classifier = 'linux.x86'; extension = 'zip'; type = 'custom-type'; conf = 'runtime' } }\n"
        listOf(producer, control).forEach {
            copySources("ivy", it)
            File(it, "build.gradle").writeText(script)
        }
        File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
        File(control, "build.gradle").appendText("publishing.repositories { ivy { name = 'control'; url = layout.buildDirectory.dir('native-control'); patternLayout { $patterns } }; ivy { name = 'remote'; url = uri('$base/content/repositories/releases/'); allowInsecureProtocol = true; credentials(PasswordCredentials); patternLayout { $patterns } } }\n")
        File(control, "gradle.properties").writeText("remoteUsername=admin\nremotePassword=admin123\n")
        val nativeBuild = ModernGradleBuild(control, false, runtime, keyEnvironment)
        nativeBuild.storeAndReuse("publishIvyPublicationToControlRepository")
        configure(project, listOf(name), "ivy", "releases", patterns)
        File(project, "build.gradle").writeText("version = '1.0.0'\n")
        var nativeRejected = false
        try {
            nativeBuild.storeAndReuse("publishIvyPublicationToRemoteRepository")
        } catch (failure: UnexpectedBuildFailure) {
            assertThat(failure.buildResult.output).contains("Received status code 400 from server")
            File(output, "native-custom-rejection-${runtime.version}.log").writeText(failure.buildResult.output)
            assertThat(failure.buildResult.output).contains("Configuration cache entry stored.")
            val nativeHit = nativeBuild.failWithDiagnostic("Received status code 400 from server", "publishIvyPublicationToRemoteRepository")
            assertThat(nativeHit.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
            nativeRejected = true
        }
        val build = ModernGradleBuild(project, false, runtime, keyEnvironment)
        var before = requestLines().size
        fun requireAttempt() {
            assertThat(requestLines().drop(before).any { it.contains("PUT /nexus/content/repositories/releases/io/github/gradle-nexus-e2e/$name/") }).isTrue()
            before = requestLines().size
        }
        if (nativeRejected) {
            val stored = build.failWithDiagnostic("Received status code 400 from server", ":$name:publishToProbe")
            assertThat(stored.output).contains("Configuration cache entry stored.")
            requireAttempt()
            val hit = build.failWithDiagnostic("Received status code 400 from server", ":$name:publishToProbe")
            assertThat(hit.output).contains("Configuration cache entry reused.")
            requireAttempt()
        } else {
            build.storeAndReuse(":$name:publishToProbe") {
                freshRootUpload(project, it)
                requireAttempt()
            }
        }
        archiveNative(control, runtime, "ivy-custom")
        val bundle = File(producer, "build/nexus-publishing/bundles/ivy")
        assertThat(File(bundle, "manifest.json").readText()).doesNotContain("gradle-module")
        val plan = ivyPlan(bundle, artifactPattern, ivyPattern, runtime)
        val native = File(control, "build/native-control").walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(File(control, "build/native-control")).invariantSeparatorsPath }
        assertThat(plan.keys).containsExactlyInAnyOrderElementsOf(native.keys)
        native.filterKeys { !it.endsWith(".asc") && !it.contains(".asc.") }.forEach { (path, file) -> assertThat(plan.getValue(path).readBytes()).describedAs("custom native path/byte parity $path").isEqualTo(file.readBytes()) }
        verifyPlanSignatures(bundle, plan)
        archiveBundle(producer, "ivy", runtime, "custom")
        if (!nativeRejected) {
            plan.forEach { (path, file) -> assertThat(fetch("/content/repositories/releases/$path")).describedAs("custom prepared bytes unchanged on real server").isEqualTo(file.readBytes()) }
            val main = File(producer, "build/libs/$name-1.0.0.jar")
            consumeGradle("ivy", "descriptor", name, "1.0.0", hash(main.readBytes()), runtime, "patternLayout { $patterns }")
            consumePublicIvy(name, "1.0.0", hash(main.readBytes()), runtime, artifactPattern, ivyPattern)
        }
        File(output, "ivy-custom-${runtime.version}.properties").writeText("status=${if (nativeRejected) "PAIRED_NATIVE_REJECTION_400" else "PASS"}\ngradle=${runtime.version}\nnativePathsAndBytes=true\ncacheStoreAndReuse=true\n")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `signed snapshots merge native history and resolve latest after cache hit and content change`(runtime: ModernRuntime) {
        val name = "snapshot-maven-" + runtime.version.replace(".", "")
        val version = "1.1-SNAPSHOT"
        val prefix = "io/github/gradle-nexus-e2e/$name/$version/"
        val project = consumer()
        val producer = File(project, name).apply { mkdirs() }
        val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
        listOf(producer, control).forEach {
            copySources("maven", it)
            File(it, "build.gradle").writeText(producerScript("maven", version))
        }
        File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
        File(control, "gradle.properties").writeText("seedUsername=admin\nseedPassword=admin123\n")
        File(control, "build.gradle").appendText("publishing.repositories { maven { name = 'control'; url = layout.buildDirectory.dir('native-control') }; maven { name = 'seed'; url = uri('$base/content/repositories/snapshots/'); allowInsecureProtocol = true; credentials(PasswordCredentials) } }\n")
        val seed = ModernGradleBuild(control, false, runtime, keyEnvironment)
        seed.storeAndReuse("publishMavenJavaPublicationToControlRepository")
        val history = linkedMapOf<String, ByteArray>()
        fun metadata(): String =
            String(fetch("/content/repositories/snapshots/${prefix}maven-metadata.xml"), Charsets.UTF_8)
        fun allocated(xml: String): String =
            "1.1-" + Regex("<timestamp>([^<]+)</timestamp>").find(xml)!!.groupValues[1] + "-" + Regex("<buildNumber>([0-9]+)</buildNumber>").find(xml)!!.groupValues[1]
        seed.storeAndReuse("publishMavenJavaPublicationToSeedRepository") {
            val xml = metadata()
            val value = allocated(xml)
            listOf("jar", "jar.asc").forEach { ext -> history["$name-$value.$ext"] = fetch("/content/repositories/snapshots/$prefix$name-$value.$ext") }
        }
        val compatibility = File(output, "${runtime.version}/native-metadata-client-views").apply { mkdirs() }
        val metadataPath = "/content/repositories/snapshots/${prefix}maven-metadata.xml"
        val modernView = fetch(metadataPath)
        val javaView = fetch(metadataPath, null)
        File(compatibility, "modern.xml").writeBytes(modernView)
        File(compatibility, "default-java.xml").writeBytes(javaView)
        assertThat(String(modernView, Charsets.UTF_8)).contains("<snapshotVersions>")
        assertThat(String(javaView, Charsets.UTF_8)).doesNotContain("<snapshotVersions>")
        listOf("md5", "sha1", "sha256", "sha512").forEach { algorithm ->
            val checksum = fetch("$metadataPath.$algorithm")
            File(compatibility, "modern.xml.$algorithm").writeBytes(checksum)
            assertChecksum(checksum, modernView, algorithm)
        }
        assertThat(MessageDigest.getInstance("MD5").digest(javaView).joinToString("") { "%02x".format(it.toInt() and 255) }).isNotEqualTo(File(compatibility, "modern.xml.md5").readText().trim())
        var number = Regex("<buildNumber>([0-9]+)</buildNumber>").find(metadata())!!.groupValues[1].toInt()
        configure(project, listOf(name), "maven", "snapshots")
        File(project, "build.gradle").writeText("version = '$version'\n")
        val nativeDirectory = File(control, "build/native-control/$prefix")
        val nativeVersion = allocated(File(nativeDirectory, "maven-metadata.xml").readText())
        val native = nativeDirectory.walkTopDown().filter { it.isFile && it.name.startsWith("$name-$nativeVersion") }.associateBy { it.name.replaceFirst("$name-$nativeVersion", "$name-$version") }
        val build = ModernGradleBuild(project, false, runtime, keyEnvironment)
        var before = requestLines().size
        fun verifyUpload(compareNative: Boolean) {
            val writes = requestLines().drop(before).filter { it.contains("PUT /nexus/content/repositories/snapshots/$prefix") }
            archiveNative(control, runtime, "maven-snapshot")
            val xml = metadata()
            number++
            assertThat(xml).contains("<buildNumber>$number</buildNumber>")
            val value = allocated(xml)
            val archive = File(output, "${runtime.version}/snapshots/$number").apply { mkdirs() }
            File(archive, "maven-metadata.xml").writeText(xml)
            listOf("md5", "sha1", "sha256", "sha512").forEach { algorithm ->
                val checksum = fetch("/content/repositories/snapshots/${prefix}maven-metadata.xml.$algorithm")
                File(archive, "maven-metadata.xml.$algorithm").writeBytes(checksum)
                assertChecksum(checksum, xml.toByteArray(Charsets.UTF_8), algorithm)
            }
            archiveBundle(producer, "maven", runtime, "snapshot-$number")
            val currentBundle = File(producer, "build/nexus-publishing/bundles/maven")
            val currentEntries = bundleEntries(currentBundle).associateBy { it["destination"].toString().substringAfterLast('/') }
            native.forEach { (filename, file) ->
                val deployed = filename.replaceFirst("$name-$version", "$name-$value")
                val bytes = fetch("/content/repositories/snapshots/$prefix$deployed")
                File(archive, deployed).writeBytes(bytes)
                val currentEntry = currentEntries.getValue(filename)
                assertThat(bytes).describedAs("current prepared snapshot bytes including changed generation").isEqualTo(File(currentBundle, currentEntry["payload"].toString()).readBytes())
                if (filename.endsWith(".asc")) {
                    val bundle = File(producer, "build/nexus-publishing/bundles/maven")
                    val entry = bundleEntries(bundle).single { it["destination"].toString().substringAfterLast('/') == filename }
                    val own = File(bundle, entry["payload"].toString())
                    assertThat(bytes).isEqualTo(own.readBytes())
                    assertThat(verify(bytes, fetch("/content/repositories/snapshots/$prefix" + deployed.removeSuffix(".asc")))).isTrue()
                    verifyEmittedSignatureChecksums("/content/repositories/snapshots/$prefix$deployed", bytes, archive, writes)
                } else if (filename.contains(".asc.")) {
                    assertChecksum(bytes, fetch("/content/repositories/snapshots/$prefix" + deployed.substringBeforeLast('.')), filename.substringAfterLast('.'))
                } else if (compareNative) {
                    assertThat(bytes).describedAs("native snapshot prepared bytes $filename").isEqualTo(file.readBytes())
                }
            }
            history.forEach { (path, bytes) -> assertThat(fetch("/content/repositories/snapshots/$prefix$path")).describedAs("native and previous root snapshot history retained").isEqualTo(bytes) }
            val jar = fetch("/content/repositories/snapshots/$prefix$name-$value.jar")
            history["$name-$value.jar"] = jar
            history["$name-$value.jar.asc"] = fetch("/content/repositories/snapshots/$prefix$name-$value.jar.asc")
            assertThat(requestLines().drop(before).any { it.contains("PUT /nexus/content/repositories/snapshots/$prefix$name-$value.jar ") }).isTrue()
            listOf("pom", "module").forEach { consumeGradle("maven", it, name, version, hash(jar), runtime, repository = "snapshots", resolvedVersion = value) }
            before = requestLines().size
        }
        build.storeAndReuse(":$name:publishToProbe") { result ->
            freshRootUpload(project, result)
            verifyUpload(true)
        }
        val previousHash = hash(File(producer, "build/libs/$name-$version.jar").readBytes())
        val source = File(producer, "src/main/java/io/github/gradle/nexus/e2e/SomeClass.java")
        source.writeText(source.readText().replace("Hello world.", "Changed snapshot content."))
        val changed = build.run(":$name:publishToProbe")
        assertThat(changed.output).contains("Configuration cache entry reused.")
        freshRootUpload(project, changed)
        verifyUpload(false)
        val latestHash = hash(File(producer, "build/libs/$name-$version.jar").readBytes())
        assertThat(latestHash).isNotEqualTo(previousHash)
        File(output, "maven-snapshot-${runtime.version}.properties").writeText("status=PASS\ngradle=${runtime.version}\nartifact=$name\nversion=$version\nsha256=$latestHash\nbuildNumber=$number\nseededHistory=true\nunchangedCacheHit=true\nchangedContent=true\n")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `selected multiproject Maven publications and real plugin marker resolve from OSS`(runtime: ModernRuntime) {
        val suffix = runtime.version.replace(".", "")
        val names = listOf("a-multi-$suffix", "z-plugin-$suffix")
        val pluginId = "production.fixture$suffix"
        val project = consumer()
        configure(project, names, "maven", "releases")
        File(project, "build.gradle").writeText("version = '1.0.0'\n")
        val controls = names.mapIndexed { index, name ->
            val producer = File(project, name).apply { mkdirs() }
            val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
            val extra = if (index == 0) {
                """
                apply plugin: 'ivy-publish'
                publishing.publications { metadataOnly(MavenPublication) { artifactId = 'metadata-only-$suffix'; pom { name = 'Metadata only native control' } }; oppositeIvy(IvyPublication) { from components.java } }
                """.trimIndent()
            } else {
                """
                apply plugin: 'java-gradle-plugin'
                publishing.publications.remove(publishing.publications.mavenJava)
                gradlePlugin { plugins { probe { id = '$pluginId'; implementationClass = 'production.FixturePlugin' } } }
                """.trimIndent()
            }
            listOf(producer, control).forEach {
                copySources("maven", it)
                File(it, "build.gradle").writeText(producerScript("maven", "1.0.0") + extra + "\n")
                if (index == 1) File(it, "src/main/java/production/FixturePlugin.java").apply { parentFile.mkdirs() }.writeText("package production; public class FixturePlugin implements org.gradle.api.Plugin<org.gradle.api.Project> { public void apply(org.gradle.api.Project project) { project.getTasks().register(\"pluginMarkerWitness\", task -> task.doLast(t -> System.out.println(\"PRODUCTION_OSS-PLUGIN-MARKER-CONSUMED\"))); } }\n")
            }
            File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
            File(control, "build.gradle").appendText("publishing.repositories.maven { name = 'control'; url = layout.buildDirectory.dir('native-control') }\n")
            val tasks = if (index == 0) arrayOf("publishMavenJavaPublicationToControlRepository", "publishMetadataOnlyPublicationToControlRepository") else arrayOf("publishPluginMavenPublicationToControlRepository", "publishProbePluginMarkerMavenPublicationToControlRepository")
            ModernGradleBuild(control, false, runtime, keyEnvironment).storeAndReuse(*tasks)
            archiveNative(control, runtime, "multi-$index")
            control
        }
        val build = ModernGradleBuild(project, false, runtime, keyEnvironment)
        build.storeAndReuse(":${names[0]}:publishToProbe") { result ->
            freshRootUpload(project, result)
            assertThat(result.tasks.map { it.path }.none { it.startsWith(":${names[1]}:") || it.contains("Ivy") }).isTrue()
            assertThat(result.output).doesNotContain("NEXUS-RESOLVE :${names[1]}", "NEXUS-RESOLVE :${names[0]} ivy")
            assertThat(File(project, "${names[1]}/build/nexus-publishing/bundles/maven/manifest.json")).doesNotExist()
            verifyNativeTree(controls[0], File(project, names[0]), runtime)
        }
        build.storeAndReuse(":publishAllProjectsToProbe") { result ->
            freshRootUpload(project, result)
            assertThat(result.tasks.map { it.path }.none { it.contains("Ivy") }).isTrue()
            controls.forEachIndexed { index, control -> verifyNativeTree(control, File(project, names[index]), runtime) }
        }
        assertThat(fetch("/content/repositories/releases/io/github/gradle-nexus-e2e/metadata-only-$suffix/1.0.0/metadata-only-$suffix-1.0.0.pom").toString(Charsets.UTF_8)).contains("Metadata only native control")
        val consumer = File(workspace, "fresh-plugin-${java.util.UUID.randomUUID()}/consumer").apply { mkdirs() }
        File(consumer, "settings.gradle").writeText("pluginManagement { repositories { maven { url = uri('$base/content/repositories/releases/'); allowInsecureProtocol = true } } }\nrootProject.name = 'plugin-consumer'\n")
        File(consumer, "build.gradle").writeText("plugins { id '$pluginId' version '1.0.0' }\n")
        val before = requestLines().size
        ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("pluginMarkerWitness") { assertThat(it.output).contains("PRODUCTION_OSS-PLUGIN-MARKER-CONSUMED") }
        assertThat(requestLines().drop(before).any { it.contains("GET /nexus/content/repositories/releases/production/fixture$suffix/$pluginId.gradle.plugin/1.0.0/$pluginId.gradle.plugin-1.0.0.pom HTTP/1.1\" 200") }).isTrue()
        File(output, "maven-multi-${runtime.version}.properties").writeText("status=PASS\ngradle=${runtime.version}\nselectedScope=true\nmetadataOnly=true\npluginMarkerConsumed=true\n")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    internal fun `Ivy snapshots follow actual native revision paths with fresh bytes and consumers`(runtime: ModernRuntime) {
        val name = "snapshot-ivy-" + runtime.version.replace(".", "")
        val version = "1.1-SNAPSHOT"
        val prefix = "io/github/gradle-nexus-e2e/$name/$version/"
        val project = consumer()
        val producer = File(project, name).apply { mkdirs() }
        val control = File(workspace, "native-$name/consumer").apply { mkdirs() }
        val script = producerScript("ivy", version) + "publishing.publications.ivy.descriptor.withXml { asNode().info[0].attributes().remove('publication') }\n"
        listOf(producer, control).forEach {
            copySources("ivy", it)
            File(it, "build.gradle").writeText(script)
        }
        File(control, "settings.gradle").writeText("rootProject.name = '$name'\n")
        File(control, "gradle.properties").writeText("seedUsername=admin\nseedPassword=admin123\n")
        File(control, "build.gradle").appendText("publishing.repositories { ivy { name = 'control'; url = layout.buildDirectory.dir('native-control'); layout 'maven' }; ivy { name = 'seed'; url = uri('$base/content/repositories/snapshots/'); allowInsecureProtocol = true; credentials(PasswordCredentials); layout 'maven' } }\n")
        val seed = ModernGradleBuild(control, false, runtime, keyEnvironment)
        seed.storeAndReuse("publishIvyPublicationToControlRepository")
        val nativeDirectory = File(control, "build/native-control/$prefix")
        val native = nativeDirectory.listFiles()!!.associateBy { it.name }
        assertThat(native).isNotEmpty()
        val beforeNative = requestLines().size
        var nativeRejected = false
        try {
            seed.storeAndReuse("publishIvyPublicationToSeedRepository")
        } catch (failure: UnexpectedBuildFailure) {
            assertThat(failure.buildResult.output).contains("Received status code 400 from server", "Configuration cache entry stored.")
            File(output, "native-ivy-snapshot-rejection-${runtime.version}.log").writeText(failure.buildResult.output)
            assertThat(seed.failWithDiagnostic("Received status code 400 from server", "publishIvyPublicationToSeedRepository").output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
            nativeRejected = true
        }
        val nativeWrites = requestLines().drop(beforeNative).filter { it.contains("PUT /nexus/content/repositories/snapshots/$prefix") }
        assertThat(nativeWrites).isNotEmpty()
        File(output, "native-ivy-snapshot-requests-${runtime.version}.log").writeText(nativeWrites.joinToString("\n") + "\n")
        archiveNative(control, runtime, "ivy-snapshot")
        if (!nativeRejected) {
            val evidence = File(output, "${runtime.version}/native-ivy-snapshot-server").apply { mkdirs() }
            native.forEach { (filename, file) ->
                val bytes = fetch("/content/repositories/snapshots/$prefix$filename")
                File(evidence, filename).writeBytes(bytes)
                if (filename.endsWith(".asc")) {
                    assertThat(verify(bytes, fetch("/content/repositories/snapshots/$prefix" + filename.removeSuffix(".asc")))).isTrue()
                } else if (filename.contains(".asc.")) {
                    assertChecksum(bytes, fetch("/content/repositories/snapshots/$prefix" + filename.substringBeforeLast('.')), filename.substringAfterLast('.'))
                } else {
                    assertThat(bytes).describedAs("native Ivy SNAPSHOT uses directly retrievable revision paths").isEqualTo(file.readBytes())
                }
            }
        }
        configure(project, listOf(name), "ivy", "snapshots")
        File(project, "build.gradle").writeText("version = '$version'\n")
        val build = ModernGradleBuild(project, false, runtime, keyEnvironment)
        var before = requestLines().size
        var generation = 0
        fun checkGeneration(compareNative: Boolean) {
            generation++
            val bundle = File(producer, "build/nexus-publishing/bundles/ivy")
            val own = bundleEntries(bundle)
            assertThat(own).isNotEmpty()
            val archive = File(output, "${runtime.version}/ivy-snapshots/$generation").apply { mkdirs() }
            val writes = requestLines().drop(before).filter { it.contains("PUT /nexus/content/repositories/snapshots/$prefix") }
            assertThat(writes).isNotEmpty()
            assertThat(writes.none { it.contains("maven-metadata") }).isTrue()
            own.forEach { entry ->
                val filename = entry["destination"].toString().substringAfterLast('/')
                val prepared = File(bundle, entry["payload"].toString()).readBytes()
                val remote = fetch("/content/repositories/snapshots/$prefix$filename")
                File(archive, filename).writeBytes(remote)
                assertThat(remote).describedAs("actual current Ivy snapshot revision payload $filename").isEqualTo(prepared)
                if (entry["role"] == "signature") {
                    val target = own.single { it["destination"] == entry["relatedTo"] }
                    assertThat(verify(remote, File(bundle, target["payload"].toString()).readBytes())).isTrue()
                }
                if (compareNative && !filename.endsWith(".asc") && !filename.contains(".asc.")) {
                    assertThat(remote).isEqualTo(native.getValue(filename).readBytes())
                }
            }
            archiveBundle(producer, "ivy", runtime, "snapshot-$generation")
            val jar = File(producer, "build/libs/$name-$version.jar")
            listOf("descriptor", "module").forEach { consumeGradle("ivy", it, name, version, hash(jar.readBytes()), runtime, repository = "snapshots") }
            consumePublicIvy(name, version, hash(jar.readBytes()), runtime, repository = "snapshots")
            before = requestLines().size
        }
        if (nativeRejected) {
            val first = build.failWithDiagnostic("Received status code 400 from server", ":$name:publishToProbe")
            assertThat(first.output).contains("Configuration cache entry stored.")
            assertThat(requestLines().drop(before).any { it.contains("PUT /nexus/content/repositories/snapshots/$prefix") }).isTrue()
            before = requestLines().size
            assertThat(build.failWithDiagnostic("Received status code 400 from server", ":$name:publishToProbe").output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
            assertThat(requestLines().drop(before).any { it.contains("PUT /nexus/content/repositories/snapshots/$prefix") }).isTrue()
            val bundle = File(producer, "build/nexus-publishing/bundles/ivy")
            archiveBundle(producer, "ivy", runtime, "snapshot-native-rejection")
            bundleEntries(bundle).filter { it["role"] != "signature" && !it["destination"].toString().contains(".asc.") }.forEach { entry ->
                val filename = entry["destination"].toString().substringAfterLast('/')
                assertThat(File(bundle, entry["payload"].toString()).readBytes()).isEqualTo(native.getValue(filename).readBytes())
            }
        } else {
            build.storeAndReuse(":$name:publishToProbe") { result ->
                freshRootUpload(project, result)
                checkGeneration(true)
            }
            val previous = hash(File(producer, "build/libs/$name-$version.jar").readBytes())
            val source = File(producer, "src/main/java/io/github/gradle/nexus/e2e/SomeClass.java")
            source.writeText(source.readText().replace("Hello world.", "Changed Ivy snapshot content."))
            val changed = build.run(":$name:publishToProbe")
            assertThat(changed.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
            freshRootUpload(project, changed)
            checkGeneration(false)
            assertThat(hash(File(producer, "build/libs/$name-$version.jar").readBytes())).isNotEqualTo(previous)
        }
        File(output, "ivy-snapshot-${runtime.version}.properties").writeText("status=${if (nativeRejected) "PAIRED_NATIVE_REJECTION_400" else "PASS"}\ngradle=${runtime.version}\nnativeRevisionPaths=true\ncacheStoreAndReuse=true\nchangedContent=${!nativeRejected}\nconsumers=${if (nativeRejected) "none-native-rejection" else "descriptor,module,public-ivy"}\n")
    }

    private fun consumer(): File =
        File(workspace, "producer-${java.util.UUID.randomUUID()}/consumer").apply {
            mkdirs()
            val published = File(requireNotNull(System.getProperty("modernTest.publicationRepository"))).toURI()
            val version = requireNotNull(System.getProperty("modernTest.publicationVersion"))
            File(this, "settings.gradle").writeText("pluginManagement { repositories { maven { url = uri('$published') } } }\nplugins { id 'io.github.gradle-nexus.publish-plugin' version '$version' }\nrootProject.name = 'real-production'\n")
        }

    private fun configure(project: File, names: List<String>, format: String, repository: String, patterns: String? = null) {
        val layout = if (patterns == null) "" else "ivyPatternLayout { $patterns }"
        File(project, "settings.gradle").appendText(
            "include " + names.joinToString(", ") { "'$it'" } + "\n" +
                "nexusPublishing { useStaging.set(false); repositories { create('probe') { publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.${format.toUpperCase()}); nexusUrl.set(uri('$base/service/local/')); snapshotRepositoryUrl.set(uri('$base/content/repositories/$repository/')); allowInsecureProtocol.set(true); username.set('admin'); password.set('admin123'); $layout } } }\n"
        )
    }

    private fun freshRootUpload(project: File, result: BuildResult) {
        val logs = result.output.lines().filter { it.startsWith("NEXUS-WORKFLOW-UPLOADED repository=probe ") }
        assertThat(logs).hasSize(1)
        val invocation = Regex("invocation=([0-9a-f-]+)").find(logs.single())!!.groupValues[1]
        assertThat(invocations.add(invocation)).describedAs("fresh root action invocation on store and cache hit").isTrue()
        val uploads = result.tasks.filter { it.path.endsWith("_upload") }
        assertThat(uploads).hasSize(1)
        assertThat(uploads.single().path.count { it == ':' }).isEqualTo(1)
        assertThat(uploads.single().outcome).isEqualTo(TaskOutcome.SUCCESS)
        val workflows = File(project, "build/nexus-publishing/workflows")
        val receipt = workflows.walkTopDown().single { it.isFile && it.name == "upload.json" }
        val record = Gson().fromJson(receipt.readText(), Map::class.java)
        assertThat(record["invocation"]).isEqualTo(invocation)
        assertThat(record["operation"]).isEqualTo("upload")
        workflows.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            File(output, "root-receipts/$invocation/" + file.relativeTo(workflows).invariantSeparatorsPath).apply {
                parentFile.mkdirs()
                writeBytes(file.readBytes())
            }
        }
    }

    private fun verifyNativeTree(control: File, producer: File, runtime: ModernRuntime) {
        val native = File(control, "build/native-control")
        val bundle = File(producer, "build/nexus-publishing/bundles/maven")
        archiveBundle(producer, "maven", runtime, "multi-${java.util.UUID.randomUUID()}")
        val own = bundleEntries(bundle).associateBy { it["destination"].toString() }
        native.walkTopDown().filter { it.isFile && it.parentFile.name == "1.0.0" && !it.name.startsWith("maven-metadata") }.forEach { file ->
            val path = file.relativeTo(native).invariantSeparatorsPath
            val bytes = fetch("/content/repositories/releases/$path")
            File(output, "multi/${producer.name}/$path").apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
            if (file.name.endsWith(".asc")) {
                assertThat(bytes).isEqualTo(File(bundle, own.getValue(path)["payload"].toString()).readBytes())
                assertThat(verify(bytes, fetch("/content/repositories/releases/" + path.removeSuffix(".asc")))).isTrue()
            } else if (file.name.contains(".asc.")) {
                assertChecksum(bytes, fetch("/content/repositories/releases/" + path.substringBeforeLast('.')), path.substringAfterLast('.'))
            } else {
                assertThat(bytes).describedAs("independent multiple-publication native bytes $path").isEqualTo(file.readBytes())
            }
        }
    }

    private fun ivyPlan(bundle: File, artifacts: String, ivys: String, runtime: ModernRuntime): Map<String, File> =
        NativeIvyPlanEvidence.plan(listOf(bundle.toPath()), listOf(artifacts), listOf(ivys), true, true, runtime).mapValues { it.value.file }

    @Suppress("UNCHECKED_CAST")
    private fun verifyPlanSignatures(bundle: File, plan: Map<String, File>) {
        URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val model = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec").getMethod("readModel", java.nio.file.Path::class.java).invoke(null, bundle.toPath()) as Map<String, Any?>
            (model["publications"] as List<Map<String, Any?>>).forEach { publication ->
                val entries = (publication["entries"] as List<Map<String, Any?>>).associateBy { it["destination"].toString() }
                entries.values.filter { it["role"] == "signature" }.forEach { signature ->
                    val bytes = File(bundle, signature["payload"].toString()).readBytes()
                    assertThat(plan.values.any { java.util.Arrays.equals(it.readBytes(), bytes) }).isTrue()
                    val target = entries[signature["relatedTo"].toString()]!!
                    assertThat(verify(bytes, File(bundle, target["payload"].toString()).readBytes())).isTrue()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bundleEntries(bundle: File): List<Map<String, Any?>> =
        URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val model = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec").getMethod("readModel", java.nio.file.Path::class.java).invoke(null, bundle.toPath()) as Map<String, Any?>
            (model["publications"] as List<Map<String, Any?>>).flatMap { it["entries"] as List<Map<String, Any?>> }
        }

    private fun verifyRelease(format: String, name: String, version: String, prefix: String, native: Map<String, File>, producer: File, runtime: ModernRuntime, writes: List<String> = emptyList()) {
        archiveBundle(producer, format, runtime, "release-${java.util.UUID.randomUUID()}")
        val downloaded = File(output, "${runtime.version}/$format-release").apply { mkdirs() }
        native.forEach { (filename, file) ->
            val bytes = fetch("/content/repositories/releases/$prefix$filename")
            File(downloaded, filename).writeBytes(bytes)
            if (filename.endsWith(".asc")) {
                assertThat(verify(bytes, fetch("/content/repositories/releases/$prefix" + filename.removeSuffix(".asc")))).describedAs("remote software signature verifies $filename").isTrue()
                val own = File(producer, "build/nexus-publishing/bundles/$format/payload").walkTopDown().single { it.isFile && it.name == filename }
                assertThat(bytes).describedAs("signature bytes remain the prepared producer output").isEqualTo(own.readBytes())
                if (format == "maven") verifyEmittedSignatureChecksums("/content/repositories/releases/$prefix$filename", bytes, downloaded, writes)
            } else if (filename.contains(".asc.")) {
                assertChecksum(bytes, fetch("/content/repositories/releases/$prefix" + filename.substringBeforeLast('.')), filename.substringAfterLast('.'))
            } else {
                assertThat(bytes).describedAs("independent native file publication parity $filename").isEqualTo(file.readBytes())
            }
        }
        ZipInputStream(File(downloaded, "$name-$version.jar").inputStream()).use { zip ->
            assertThat(generateSequence { zip.nextEntry }.map { it.name }.toList()).contains("io/github/gradle/nexus/e2e/SomeClass.class")
        }
    }

    private fun consumeGradle(format: String, metadata: String, name: String, version: String, expectedHash: String, runtime: ModernRuntime, layout: String? = null, repository: String = "releases", resolvedVersion: String = version) {
        val consumer = File(workspace, "fresh-$format-$metadata-${java.util.UUID.randomUUID()}/consumer").apply { mkdirs() }
        File(consumer, "settings.gradle").writeText("rootProject.name = 'fresh-consumer'\n")
        val source = when (metadata) {
            "pom" -> "mavenPom(); ignoreGradleMetadataRedirection()"
            "descriptor" -> "ivyDescriptor(); ignoreGradleMetadataRedirection()"
            else -> "gradleMetadata()"
        }
        File(consumer, "build.gradle").writeText(
            """
            plugins { id 'java-library' }
            repositories { $format { url = uri('$base/content/repositories/$repository/'); allowInsecureProtocol = true; ${layout ?: if (format == "ivy") "layout 'maven';" else ""}; metadataSources { $source } } }
            dependencies { implementation 'io.github.gradle-nexus-e2e:$name:$version' }
            tasks.register('resolveArtifact', Sync) { from configurations.runtimeClasspath; into layout.buildDirectory.dir('resolved') }
            """.trimIndent() + "\n"
        )
        val before = requestLines().size
        ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("resolveArtifact")
        val resolved = File(consumer, "build/resolved/$name-$version.jar")
        assertThat(hash(resolved.readBytes())).describedAs("fresh independent $format $metadata consumer resolves expected Java artifact").isEqualTo(expectedHash)
        val required = when (metadata) {
            "pom" -> "$name-$resolvedVersion.pom"
            "descriptor" -> "ivy-$version.xml"
            else -> "$name-$resolvedVersion.module"
        }
        assertThat(requestLines().drop(before).any { it.contains("GET /nexus/content/repositories/$repository/io/github/gradle-nexus-e2e/$name/$version/$required HTTP/1.1\" 200") }).describedAs("required metadata fetched successfully, no fallback claim").isTrue()
    }

    private fun consumePublicIvy(name: String, version: String, expectedHash: String, runtime: ModernRuntime, artifacts: String = "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])", ivys: String = "[organisation]/[module]/[revision]/ivy-[revision].xml", repository: String = "releases") {
        val home = File(workspace, "public-ivy-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val source = File(home, "IvyConsumer.java")
        // Independent public Ivy consumer, with no plugin or Gradle libraries at runtime.
        source.writeText(javaClass.classLoader.getResource("nexus-oss-fixtures/IvyConsumer.java")!!.readText())
        File(home, "settings.gradle").writeText("rootProject.name = 'independent-public-ivy'\n")
        File(home, "build.gradle").writeText(
            """
            repositories { mavenCentral() }
            configurations { publicIvyConsumer { canBeConsumed = false; canBeResolved = true } }
            dependencies { publicIvyConsumer 'org.apache.ivy:ivy:2.6.0' }
            tasks.register('publicIvyLibraries', Sync) {
                from configurations.publicIvyConsumer
                into layout.buildDirectory.dir('public-ivy-libraries')
            }
            """.trimIndent() + "\n"
        )
        ModernGradleBuild(home, false, runtime).storeAndReuse("publicIvyLibraries")
        val libraries = File(home, "build/public-ivy-libraries").listFiles()!!.filter { it.extension == "jar" }.sortedBy { it.name }
        val ivy = libraries.single { it.name == "ivy-2.6.0.jar" }
        assertThat(libraries).isNotEmpty().allMatch { it.canonicalFile.toPath().startsWith(home.canonicalFile.toPath()) && it.canonicalFile != ModernTestPaths.candidateJar && !it.name.startsWith("gradle-") }
        File(output, "public-ivy-libraries-${home.name}.json").writeText(Gson().toJson(libraries.associate { it.name to hash(it.readBytes()) }))
        val classpath = libraries.joinToString(File.pathSeparator) { it.path }
        val log = File(output, "public-ivy-$name-${runtime.version}.log")
        val before = requestLines().size
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-Duser.home=${home.path}", "-Djava.net.useSystemProxies=false", "-cp", classpath, source.path, home.path, "$base/content/repositories/$repository/", "io.github.gradle-nexus-e2e", name, version, expectedHash, artifacts, ivys)
            .directory(home).redirectErrorStream(true).redirectOutput(log).apply {
                val keep = System.getenv().filterKeys { it in setOf("PATH", "LANG", "LC_ALL") }
                environment().clear()
                environment().putAll(keep)
            }.start()
        try {
            check(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { "Public Ivy consumer timed out" }
            assertThat(process.exitValue()).describedAs(log.readText()).isZero()
            assertThat(log.readText()).contains("OSS-PUBLIC-IVY-CONSUMED ${ivy.toURI().toURL()}")
        } finally {
            if (process.isAlive) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
        assertThat(requestLines().drop(before).any { it.contains("GET /nexus/content/repositories/$repository/io/github/gradle-nexus-e2e/$name/$version/ivy-$version.xml HTTP/1.1\" 200") }).describedAs("standalone public Ivy actually fetches real descriptor").isTrue()
    }

    private fun archiveNative(control: File, runtime: ModernRuntime, scenario: String) {
        val native = File(control, "build/native-control")
        require(native.isDirectory)
        native.copyRecursively(File(output, "${runtime.version}/native-$scenario"), overwrite = true)
    }

    private fun archiveBundle(producer: File, format: String, runtime: ModernRuntime, phase: String) {
        val bundle = File(producer, "build/nexus-publishing/bundles/$format")
        require(bundle.isDirectory)
        bundle.copyRecursively(File(output, "${runtime.version}/bundles/${producer.name}/$phase"), overwrite = true)
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun verifyEmittedSignatureChecksums(path: String, payload: ByteArray, archive: File, writes: List<String>) {
        val algorithms = listOf("md5", "sha1", "sha256", "sha512").filter { algorithm -> writes.any { it.contains("PUT /nexus$path.$algorithm HTTP/1.1") } }
        File(output, "signature-sidecars.log").appendText("$path emitted=$algorithms\n")
        algorithms.forEach { algorithm ->
            val checksum = fetch("$path.$algorithm")
            File(archive, path.substringAfterLast('/') + ".$algorithm").writeBytes(checksum)
            assertChecksum(checksum, payload, algorithm)
        }
    }

    private fun assertChecksum(checksum: ByteArray, payload: ByteArray, algorithm: String) {
        val name = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512").getValue(algorithm)
        val expected = MessageDigest.getInstance(name).digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertThat(String(checksum, Charsets.US_ASCII).trim()).describedAs("remote $algorithm checksum of actual prepared signature").isEqualTo(expected)
    }

    private fun requestLines(): List<String> =
        if (requestLog.isFile) requestLog.readLines() else emptyList()

    private fun copySources(kind: String, target: File) {
        val suffix = if (kind == "ivy") "-ivy" else ""
        val source = File(repo, "src/e2eTest/resources/nexus-publish-e2e-minimal$suffix/src")
        source.copyRecursively(File(target, "src"))
        source.walkTopDown().filter { it.isFile }.forEach { file -> assertThat(File(target, "src/" + file.relativeTo(source).invariantSeparatorsPath).readBytes()).isEqualTo(file.readBytes()) }
    }

    private fun producerScript(kind: String, version: String): String {
        val suffix = if (kind == "ivy") "-ivy" else ""
        val pinned = File(repo, "src/e2eTest/resources/nexus-publish-e2e-minimal$suffix/build.gradle").readText()
        val start = pinned.indexOf("publishing {")
        require(start >= 0)
        var braces = 0
        val end = (start until pinned.length).first { i ->
            if (pinned[i] == '{') braces++
            if (pinned[i] == '}') braces--
            pinned[i] == '}' && braces == 0
        }
        return """
            plugins { id 'java-library'; id '$kind-publish'; id 'signing' }
            group = 'io.github.gradle-nexus-e2e'; version = '$version'
            java { sourceCompatibility = JavaVersion.VERSION_1_8; withSourcesJar(); withJavadocJar() }
            tasks.withType(AbstractArchiveTask).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }
            ${pinned.substring(start, end + 1)}
            signing { required = true; useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(), providers.environmentVariable('BundleFixture_PGP_PASSWORD').get()); sign publishing.publications }
        """.trimIndent() + "\n"
    }

    private fun fetch(path: String, userAgent: String? = "gradle-nexus-production-verifier/1.0"): ByteArray {
        require(path.startsWith("/") && !path.startsWith("//"))
        val connection = base.resolve(base.path + path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.instanceFollowRedirects = false
        if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:admin123".toByteArray()))
        try {
            check(connection.responseCode == 200) { "Local OSS HTTP ${connection.responseCode} for $path" }
            if (path.endsWith("maven-metadata.xml")) {
                val headers = connection.headerFields.filterKeys { it != null && it.toLowerCase() in setOf("content-type", "content-length", "vary", "etag") }
                File(output, "metadata-response-headers.txt").appendText("$path agent=${userAgent ?: "default-java"} status=200 $headers\n")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun verify(signature: ByteArray, payload: ByteArray): Boolean {
        val list = PGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)), JcaKeyFingerprintCalculator()).nextObject() as PGPSignatureList
        val value = list.get(0)
        value.init(JcaPGPContentVerifierBuilderProvider().setProvider(BouncyCastleProvider()), publicKeys.getPublicKey(value.keyID))
        value.update(payload)
        return value.verify()
    }
}
