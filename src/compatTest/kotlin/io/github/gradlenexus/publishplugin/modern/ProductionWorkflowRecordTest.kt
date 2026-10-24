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
import java.net.URLClassLoader
import java.nio.file.Path

/** Exercises the packaged root-file protocol, independently of workflow task execution. */
internal class ProductionWorkflowRecordTest {
    private lateinit var loader: URLClassLoader
    private lateinit var files: Class<*>

    @BeforeEach
    fun loadCandidate() {
        loader = URLClassLoader(
            arrayOf(ModernTestPaths.candidateJar.toURI().toURL()),
            ClassLoader.getPlatformClassLoader()
        )
        files = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowFiles")
    }

    @AfterEach
    fun closeCandidate() {
        if (::loader.isInitialized) loader.close()
    }

    @Test
    fun `valid root receipt loads from actual candidate with private JSON`(@TempDir directory: File) {
        assertThat(files.classLoader).isSameAs(loader)
        assertThat(File(files.protectionDomain.codeSource.location.toURI())).isEqualTo(ModernTestPaths.candidateJar)
        assertThatThrownBy { loader.loadClass("com.google.gson.Gson") }.isInstanceOf(ClassNotFoundException::class.java)
        val record = read(write(directory, valid()))
        assertThat(record.javaClass.getField("invocation").get(record)).isEqualTo("current")
        assertThat(record.javaClass.getField("units").get(record)).isEqualTo(listOf("p0"))
    }

    private fun read(path: Path): Any =
        files.getMethod("read", Path::class.java, String::class.java, String::class.java).invoke(null, path, "current", "probe")

    private fun write(directory: File, value: String): Path =
        File(directory, "record.json").apply { writeText(value) }.toPath()

    private fun valid() =
        """{"schema":1,"invocation":"current","repository":"probe","operation":"plan","units":["p0"],"fingerprints":{},"empty":true,"format":"maven","route":"direct","layoutPolicy":"maven-standard","endpointIdentity":""}"""
}
