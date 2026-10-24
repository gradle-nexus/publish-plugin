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

package io.github.gradlenexus.publishplugin.internal.modern.transport

import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class MavenPublicationPlanTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `empty selection and empty producer bundles have no publications`() {
        assertThat(MavenPublicationPlan.plan(emptyList()).publications).isEmpty()
        assertThat(MavenPublicationPlan.plan(emptyList()).isEmpty()).isTrue()
        writeBundle(directory, emptyList())
        assertThat(MavenPublicationPlan.plan(listOf(directory)).publications).isEmpty()
        assertThat(MavenPublicationPlan.plan(listOf(directory)).isEmpty()).isTrue()
    }

    @Test
    fun `a publication without deployment entries is empty`() {
        writeBundle(directory, emptyList(), includeEmptyPublication = true)
        val plan = MavenPublicationPlan.plan(listOf(directory))
        assertThat(plan.publications).hasSize(1)
        assertThat(plan.isEmpty()).isTrue()
    }

    @Test
    fun `metadata only publication is not empty`() {
        val metadata = entry(directory, "a.pom", "g/a/1/a-1.pom", "pom").apply { put("describes", emptyList<String>()) }
        writeBundle(directory, listOf(metadata))
        val plan = MavenPublicationPlan.plan(listOf(directory))
        assertThat(plan.isEmpty()).isFalse()
        plan.verify()
    }

    @Test
    fun `metadata signatures and classifiers preserve payload bytes and checksum entries stay out of native verification`() {
        val artifact = entry(directory, "a.jar", "g/a/1/a-1.jar")
        val sources = entry(directory, "sources.jar", "g/a/1/a-1-sources.jar").apply { put("classifier", "sources") }
        val pom = entry(directory, "a.pom", "g/a/1/a-1.pom", "pom").apply { put("describes", listOf(artifact["destination"])) }
        val module = entry(directory, "a.module", "g/a/1/a-1.module", "gradle-module").apply { put("describes", listOf(artifact["destination"])) }
        val signature = entry(directory, "a.jar.asc", "g/a/1/a-1.jar.asc", "signature").apply {
            put("extension", "asc")
            put("type", "jar.asc")
            put("relatedTo", artifact.getValue("destination"))
        }
        val checksum = entry(directory, "a.jar.sha1", "g/a/1/a-1.jar.sha1", "checksum").apply {
            put("relatedTo", artifact.getValue("destination"))
        }
        writeBundle(directory, listOf(artifact, sources, pom, module, signature, checksum))

        val plan = MavenPublicationPlan.plan(listOf(directory))

        assertThat(plan.publications).hasSize(1)
        assertThat(plan.isEmpty()).isFalse()
        assertThat(plan.publications.single().artifacts.map { it.destination }).containsExactly(
            "g/a/1/a-1.jar",
            "g/a/1/a-1-sources.jar",
            "g/a/1/a-1.pom",
            "g/a/1/a-1.module",
            "g/a/1/a-1.jar.asc",
            "g/a/1/a-1.jar.sha1"
        )
        plan.publications.single().artifacts.forEach { assertThat(Files.readAllBytes(it.payload.getFile())).isEqualTo("abc".toByteArray()) }
        Files.write(directory.resolve("payload/a.jar.sha1"), "changed".toByteArray())
        plan.verify()
        Files.write(directory.resolve("payload/a.jar.asc"), "abd".toByteArray())
        assertThatThrownBy { plan.verify() }.hasMessageContaining("NEXUS_DEPLOYMENT_PAYLOAD_CHANGED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["g/a/1/wrong.jar", "g/a/2/a-1.jar", "other/a/1/a-1.jar"])
    fun `canonical destinations must agree with publication and artifact identity`(destination: String) {
        writeBundle(directory, listOf(entry(directory, "a.jar", destination)))
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(directory)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("NEXUS_MAVEN_IDENTITY_MAPPING")
    }

    @Test
    fun `artifact name must match the module even when its destination matches the coordinates`() {
        val artifact = entry(directory, "a.jar", "g/a/1/a-1.jar").apply { put("name", "other") }
        writeBundle(directory, listOf(artifact))
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(directory)) }.hasMessageContaining("NEXUS_MAVEN_IDENTITY_MAPPING")
    }

    @Test
    fun `individually valid producers cannot collide in the selected union`() {
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        listOf(first, second).forEach { root ->
            writeBundle(root, listOf(entry(root, "a.jar", "g/a/1/a-1.jar")))
            MavenPublicationPlan.plan(listOf(root))
        }
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(first, second)) }.hasMessageContaining("NEXUS_DESTINATION_COLLISION")
    }

    @Test
    fun `checksum destinations participate in collision checks across producers`() {
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        val checksum = entry(first, "a.jar.sha1", "g/a/1/a-1.jar.sha1", "checksum").apply {
            put("relatedTo", "g/a/1/a-1.jar")
        }
        writeBundle(first, listOf(entry(first, "a.jar", "g/a/1/a-1.jar"), checksum))
        val artifact = entry(second, "a.jar.sha1", "g/a/1/a-1.jar.sha1").apply { put("extension", "jar.sha1") }
        writeBundle(second, listOf(artifact))
        listOf(first, second).forEach { MavenPublicationPlan.plan(listOf(it)) }
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(first, second)) }.hasMessageContaining("NEXUS_DESTINATION_COLLISION")
    }

    @Test
    fun `metadata relationship validation remains part of reading a plan`() {
        val metadata = entry(directory, "a.pom", "g/a/1/a-1.pom", "pom")
        writeBundle(directory, listOf(metadata))
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(directory)) }.hasMessageContaining("missing metadata relationships")
        metadata["describes"] = listOf("g/a/1/missing.jar")
        writeBundle(directory, listOf(metadata))
        assertThatThrownBy { MavenPublicationPlan.plan(listOf(directory)) }.hasMessageContaining("missing relationship target")
    }

    @Test
    fun `payload changes after planning are detected even at the same size`() {
        writeBundle(directory, listOf(entry(directory, "a.jar", "g/a/1/a-1.jar")))
        val plan = MavenPublicationPlan.plan(listOf(directory))
        Files.write(directory.resolve("payload/a.jar"), "abd".toByteArray())
        assertThatThrownBy { plan.verify() }.hasMessageContaining("NEXUS_DEPLOYMENT_PAYLOAD_CHANGED")
    }

    private fun entry(root: Path, file: String, destination: String, role: String = "artifact"): MutableMap<String, Any> {
        Files.createDirectories(root.resolve("payload"))
        Files.write(root.resolve("payload/$file"), "abc".toByteArray())
        return linkedMapOf(
            "payload" to "payload/$file", "destination" to destination, "role" to role,
            "name" to "a", "classifier" to "", "extension" to "jar", "type" to "jar",
            "size" to 3, "sha256" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    private fun writeBundle(root: Path, entries: List<Map<String, Any>>, includeEmptyPublication: Boolean = false) {
        val publications = if (entries.isEmpty() && !includeEmptyPublication) {
            emptyList()
        } else {
            listOf(
                linkedMapOf(
                    "name" to "p", "group" to "g", "module" to "a", "version" to "1", "snapshot" to false,
                    "kind" to "publication", "artifacts" to emptyList<Any>(), "entries" to entries, "localRepositoryMetadata" to emptyList<Any>()
                )
            )
        }
        val manifest = linkedMapOf(
            "schemaVersion" to 1,
            "producerPath" to ":p",
            "format" to "maven",
            "empty" to publications.isEmpty(),
            "publications" to publications
        )
        Files.write(root.resolve("manifest.json"), Gson().toJson(manifest).toByteArray(Charsets.UTF_8))
    }
}
