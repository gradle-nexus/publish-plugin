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

import io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path

class PublicationDescriptionTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("maven,pom", "ivy,ivy-descriptor")
    fun `snapshot and metadata relationships remain in the validated manifest`(format: String, role: String) {
        val manifest = manifest(format, role)
        write(manifest)

        val description = PublicationDescription.read(listOf(directory), format).single()
        assertThat(description.version).isEqualTo("1-SNAPSHOT")
        assertThat(description.artifacts.map { it.role }).containsExactly("artifact", role)
        description.artifacts.forEach { assertThat(Files.readAllBytes(it.payload.getFile())).isEqualTo("abc".toByteArray()) }
        @Suppress("UNCHECKED_CAST")
        val publication = (BundleCodec.readModel(directory).getValue("publications") as List<Map<String, Any>>).single()
        assertThat(publication["snapshot"]).isEqualTo(true)

        write(manifest.replace("\"snapshot\":true,", ""))
        assertThatThrownBy { PublicationDescription.read(listOf(directory), format) }.hasMessageContaining("missing field")
        write(manifest.replace("\"describes\":[\"g/a/1-SNAPSHOT/a-1-SNAPSHOT.jar\"]", "\"describes\":[\"g/a/1-SNAPSHOT/missing.jar\"]"))
        assertThatThrownBy { PublicationDescription.read(listOf(directory), format) }.hasMessageContaining("missing relationship target")
        write(manifest.replace(",\"describes\":[\"g/a/1-SNAPSHOT/a-1-SNAPSHOT.jar\"]", ""))
        assertThatThrownBy { PublicationDescription.read(listOf(directory), format) }.hasMessageContaining("missing metadata relationships")
    }

    @ParameterizedTest
    @CsvSource("maven,pom", "ivy,ivy-descriptor")
    fun `bundle schema rejects non boolean snapshot markers before projection`(format: String, role: String) {
        listOf("\"true\"", "1", "[]", "{}").forEach { value ->
            write(manifest(format, role).replace("\"snapshot\":true", "\"snapshot\":$value"))
            assertThatThrownBy { BundleCodec.readModel(directory) }.hasMessageContaining("NEXUS_INVALID_BUNDLE: invalid snapshot marker")
            assertThatThrownBy { PublicationDescription.read(listOf(directory), format) }.hasMessageContaining("NEXUS_INVALID_BUNDLE: invalid snapshot marker")
        }
    }

    @ParameterizedTest
    @CsvSource("maven,pom", "ivy,ivy-descriptor")
    fun `reused descriptions still reject union collisions and changed payloads`(format: String, role: String) {
        val text = manifest(format, role).let {
            if (format == "maven") it.replace("1-SNAPSHOT/metadata.xml", "1-SNAPSHOT/a-1-SNAPSHOT.pom") else it
        }
        write(text)
        val publications = PublicationDescription.fromModel(directory, format, BundleCodec.readModel(directory))
        fun plan(selected: List<PublicationDescription>) {
            if (format == "maven") {
                MavenPublicationPlan.planPublications(selected).verify()
            } else {
                IvyPublicationPlan.planPublications(
                    selected,
                    listOf("unused"),
                    emptyList(),
                    false,
                    false,
                    object : IvyPublicationPlan.Paths {
                        override fun destination(publication: PublicationDescription, identity: ArtifactIdentity, pattern: String, m2Compatible: Boolean): String =
                            identity.name + "." + identity.extension
                    }
                ).verify()
            }
        }
        plan(publications)
        assertThatThrownBy { plan(publications + publications) }.hasMessageContaining("NEXUS_DESTINATION_COLLISION")
        Files.write(directory.resolve("payload/artifact"), "abd".toByteArray())
        assertThatThrownBy { plan(publications) }.hasMessageContaining("NEXUS_DEPLOYMENT_PAYLOAD_CHANGED")
        assertThatThrownBy { PublicationDescription.read(listOf(directory), format) }.hasMessageContaining("digest mismatch")
    }

    private fun write(manifest: String) {
        Files.createDirectories(directory.resolve("payload"))
        listOf("artifact", "metadata").forEach { Files.write(directory.resolve("payload/$it"), "abc".toByteArray()) }
        Files.write(directory.resolve("manifest.json"), manifest.toByteArray(Charsets.UTF_8))
    }

    private fun manifest(format: String, role: String): String =
        """{
          "schemaVersion":1,"producerPath":":p","format":"$format","empty":false,
          "publications":[{
            "name":"p","group":"g","module":"a","version":"1-SNAPSHOT","snapshot":true,"kind":"publication",
            "artifacts":[],"localRepositoryMetadata":[],"entries":[
              {"payload":"payload/artifact","destination":"g/a/1-SNAPSHOT/a-1-SNAPSHOT.jar","role":"artifact",
               "name":"a","classifier":"","extension":"jar","type":"jar","size":3,"sha256":"$DIGEST"},
              {"payload":"payload/metadata","destination":"g/a/1-SNAPSHOT/metadata.xml","role":"$role",
               "size":3,"sha256":"$DIGEST","describes":["g/a/1-SNAPSHOT/a-1-SNAPSHOT.jar"]}
            ]
          }]
        }
        """.trimIndent()

    companion object {
        private const val DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
