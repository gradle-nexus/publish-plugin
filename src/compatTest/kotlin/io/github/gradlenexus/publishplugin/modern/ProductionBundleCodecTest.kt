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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Path

/** The real packaged bundle boundary must work without Gradle or host JSON libraries. */
class ProductionBundleCodecTest {
    private lateinit var loader: URLClassLoader
    private lateinit var codec: Class<*>
    private lateinit var readMethod: Method

    @BeforeEach
    fun loadCandidate() {
        loader = URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        codec = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec")
        readMethod = codec.getMethod("readModel", Path::class.java)
    }

    @AfterEach
    fun closeCandidate() {
        if (::loader.isInitialized) loader.close()
    }

    @Test
    fun `empty and populated bundles parse using only packaged dependencies`(@TempDir directory: File) {
        assertThat(codec.classLoader).isSameAs(loader)
        assertThatThrownBy { loader.loadClass("org.gradle.api.Project") }.isInstanceOf(ClassNotFoundException::class.java)
        assertThatThrownBy { loader.loadClass("com.google.gson.Gson") }.isInstanceOf(ClassNotFoundException::class.java)
        val empty = """{"schemaVersion":1,"producerPath":":p","format":"maven","empty":true,"publications":[]}"""
        assertThat(parse(directory, empty)["empty"]).isEqualTo(true)
        writePayloads(directory)
        val parsed = parse(directory, document())
        assertThat(parsed["producerPath"]).isEqualTo(":p")
        assertThat(parsed["empty"]).isEqualTo(false)
        assertThat(publications(parsed)).hasSize(1)
        assertThat(parse(directory, document().replace("\"format\":\"maven\"", "\"format\":\"ivy\""))["format"]).isEqualTo("ivy")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(directory: File, json: String): Map<String, Any?> {
        File(directory, "manifest.json").writeText(json + "\n")
        return readMethod.invoke(null, directory.toPath()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun publications(model: Map<String, Any?>): List<Map<String, Any?>> =
        model["publications"] as List<Map<String, Any?>>

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
