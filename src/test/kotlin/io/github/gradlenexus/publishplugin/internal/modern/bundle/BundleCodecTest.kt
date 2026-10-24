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

package io.github.gradlenexus.publishplugin.internal.modern.bundle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Direct production protocol checks; runtime action admission remains in TestKit. */
@EnabledForJreRange(min = JRE.JAVA_17)
class BundleCodecTest {
    @Test
    fun `empty and populated bundles parse in both formats`(@TempDir directory: File) {
        val empty = """{"schemaVersion":1,"producerPath":":p","format":"maven","empty":true,"publications":[]}"""
        assertThat(parse(directory, empty)["empty"]).isEqualTo(true)
        writePayloads(directory)
        val parsed = parse(directory, document())
        assertThat(parsed["producerPath"]).isEqualTo(":p")
        assertThat(parsed["empty"]).isEqualTo(false)
        assertThat(publications(parsed)).hasSize(1)
        assertThat(parse(directory, document().replace("\"format\":\"maven\"", "\"format\":\"ivy\""))["format"]).isEqualTo("ivy")
    }

    @Test
    fun `schema identities and roles reject incompatible manifests`(@TempDir directory: File) {
        writePayloads(directory)
        val valid = document()
        val cases = listOf(
            "unknown field" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"endpoint\":\"must-not-travel\""),
            "missing field" to valid.replace("\"producerPath\":\":p\",", ""),
            "unsupported schema" to valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            "unsupported format" to valid.replace("\"format\":\"maven\"", "\"format\":\"unsupported\""),
            "empty marker" to valid.replace("\"empty\":false", "\"empty\":true"),
            "unsupported role" to valid.replace("\"role\":\"artifact\"", "\"role\":\"unsupported\""),
            "missing artifact identity" to valid.replace("\"classifier\":\"\",", ""),
            "missing Ivy artifact identity" to valid.replace("\"format\":\"maven\"", "\"format\":\"ivy\"").replace("\"type\":\"jar\",", "")
        )
        cases.forEach { (diagnostic, json) -> reject(directory, json, diagnostic) }
    }

    @Test
    fun `owned payload and logical destination paths reject traversal and absolute forms`(@TempDir directory: File) {
        writePayloads(directory)
        val paths = listOf("/absolute.jar", "../outside.jar", "payload/../outside.jar", "C:/outside.jar", "payload//a.jar", "payload/./a.jar", "payload\\\\a.jar")
        paths.forEach { path ->
            reject(directory, document().replace("payload/a.jar", path), "unsafe path")
            reject(directory, document().replace(DESTINATION, path), "unsafe path")
        }
    }

    @Test
    fun `missing altered and incorrectly sized payloads reject stale bytes`(@TempDir directory: File) {
        writePayloads(directory)
        reject(directory, document().replace("payload/a.jar", "payload/missing.jar"), "missing payload")
        reject(directory, document().replace("\"size\":3", "\"size\":4"), "size mismatch")
        reject(directory, document().replace(DIGEST, "0".repeat(64)), "digest mismatch")
        File(directory, "payload/a.jar").writeText("xyz")
        reject(directory, document(), "digest mismatch")
    }

    @Test
    fun `duplicate payloads destinations and publication identities are rejected`(@TempDir directory: File) {
        writePayloads(directory)
        reject(directory, document("$ENTRY,$ENTRY"), "duplicate payload")
        reject(directory, document("$ENTRY,${ENTRY.replace("payload/a.jar", "payload/b.jar")}"), "duplicate destination")
        val publication = document().substringAfter("\"publications\":[").removeSuffix("]}")
        reject(directory, document().replace("\"publications\":[$publication]", "\"publications\":[$publication,$publication]"), "duplicate publication")
    }

    @Test
    fun `sidecars and metadata require valid targets within their publication`(@TempDir directory: File) {
        writePayloads(directory)
        val missingSignatureIdentity = ENTRY.replace("\"role\":\"artifact\"", "\"role\":\"signature\",\"relatedTo\":\"$DESTINATION\"")
            .replace("\"type\":\"jar\",", "")
        reject(directory, document(missingSignatureIdentity), "missing signature identity")
        reject(directory, document(ENTRY.replace("\"role\":\"artifact\"", "\"role\":\"checksum\"")), "missing relationship target")
        val missingTarget = ENTRY.replace("\"role\":\"artifact\"", "\"role\":\"signature\",\"relatedTo\":\"g/a/1/missing.jar\"")
        reject(directory, document(missingTarget), "missing relationship target")
        val selfChecksum = ENTRY.replace("\"role\":\"artifact\"", "\"role\":\"checksum\",\"relatedTo\":\"$DESTINATION\"")
        reject(directory, document(selfChecksum), "invalid sidecar relationship")
        val metadata = ENTRY.replace("\"role\":\"artifact\"", "\"role\":\"pom\"")
        reject(directory, document(metadata), "missing metadata relationships")
        reject(directory, document(metadata.replace("\"role\":\"pom\"", "\"role\":\"pom\",\"describes\":[\"g/a/1/missing.jar\"]")), "missing relationship target")

        val first = document().substringAfter("\"publications\":[").removeSuffix("]}")
        val foreign = selfChecksum.replace("payload/a.jar", "payload/b.jar").replace("\"destination\":\"$DESTINATION\"", "\"destination\":\"g/b/1/b-1.jar.sha1\"")
        val second = first.replace("\"name\":\"p\"", "\"name\":\"second\"").replace(ENTRY, foreign)
        reject(directory, document().replace("\"publications\":[$first]", "\"publications\":[$first,$second]"), "missing relationship target")
    }

    @Test
    fun `local repository metadata is never accepted as authoritative deployment metadata`(@TempDir directory: File) {
        writePayloads(directory)
        val local = """{"payload":"payload/b.jar","role":"local-repository-metadata","size":3,"sha256":"$DIGEST","authoritative":false}"""
        val valid = document().replace("\"localRepositoryMetadata\":[]", "\"localRepositoryMetadata\":[$local]")
        parse(directory, valid)
        reject(directory, valid.replace("\"authoritative\":false", "\"authoritative\":true"), "invalid local metadata policy")
        reject(directory, valid.replace("\"authoritative\":false", "\"authoritative\":\"false\""), "invalid local metadata policy")
        reject(directory, valid.replace("payload/b.jar", "payload/absent.jar"), "missing payload")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `decoded maps and lists cannot be mutated by downstream adapters`(@TempDir directory: File) {
        writePayloads(directory)
        val model = parse(directory, document())
        val publications = publications(model)
        val publication = publications.single()
        val entries = publication["entries"] as List<Map<String, Any?>>
        assertThatThrownBy { (model as MutableMap<String, Any?>)["endpoint"] = "not-allowed" }.isInstanceOf(UnsupportedOperationException::class.java)
        assertThatThrownBy { (publications as MutableList<Map<String, Any?>>).clear() }.isInstanceOf(UnsupportedOperationException::class.java)
        assertThatThrownBy { (entries.single() as MutableMap<String, Any?>)["destination"] = "other" }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `publication namespace is stable safe and distinguishes similar identities`() {
        val identities = listOf("", ".", "..", "Publication", "publication", "x".repeat(500))
        val namespaces = identities.map { BundleCodec.namespace(it) }
        assertThat(namespaces).doesNotHaveDuplicates()
        assertThat(namespaces.all { it.matches(Regex("p-[0-9a-f]{64}")) }).isTrue()
        identities.zip(namespaces).forEach { (identity, expected) -> assertThat(BundleCodec.namespace(identity)).isEqualTo(expected) }
    }

    @Test
    fun `an existing symbolic link ancestor is rejected before opening its target`() {
        val existing = Paths.get("/proc/self/root")
        assumeTrue(Files.isSymbolicLink(existing), "Existing Linux proc link is needed; this test creates no symlink")
        val thrown = catchThrowable { BundleCodec.ownedPayloadPath(existing.parent, "root/not-opened") }
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("symlink payload")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(directory: File, json: String): Map<String, Any?> {
        File(directory, "manifest.json").writeText(json + "\n")
        return BundleCodec.readModel(directory.toPath())
    }

    @Suppress("UNCHECKED_CAST")
    private fun publications(model: Map<String, Any?>): List<Map<String, Any?>> =
        model["publications"] as List<Map<String, Any?>>

    private fun reject(directory: File, json: String, diagnostic: String) {
        val thrown = catchThrowable { parse(directory, json) }
        assertThat(thrown).describedAs("Invalid bundle must be rejected: $diagnostic")
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(diagnostic)
    }

    private fun writePayloads(directory: File) {
        File(directory, "payload").mkdirs()
        listOf("a.jar", "b.jar").forEach { File(directory, "payload/$it").writeText("abc") }
    }

    private fun document(entries: String = ENTRY): String =
        """{"schemaVersion":1,"producerPath":":p","format":"maven","empty":false,"publications":[{"name":"p","group":"g","module":"a","version":"1","snapshot":false,"kind":"publication","artifacts":[],"entries":[$entries],"localRepositoryMetadata":[]}]}"""

    companion object {
        private const val DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        private const val DESTINATION = "g/a/1/a-1.jar"
        private const val ENTRY = """{"payload":"payload/a.jar","destination":"g/a/1/a-1.jar","role":"artifact","name":"a","classifier":"","extension":"jar","type":"jar","size":3,"sha256":"$DIGEST"}"""
    }
}
