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
import java.io.File

/** Packaged production compatibility controls on the legacy floor and current build host. */
internal fun runLegacy(project: File, task: String, current: Boolean = false, expectedFailure: String? = null, info: Boolean = false, version: String = if (current) "8.7" else "6.2.2") {
    val javaHome = File(System.getProperty(if (current) "java.home" else "modernTest.legacyJavaHome"))
    require(if (current) version == "8.7" else version in setOf("6.2.2", "6.9.4"))
    val wrapper = File(project, "gradle/wrapper").apply { mkdirs() }
    val wrapperJar = File(wrapper, "gradle-wrapper.jar")
    File(System.getProperty("modernTest.wrapperJar")).copyTo(wrapperJar, overwrite = true)
    File(wrapper, "gradle-wrapper.properties").writeText(
        "distributionUrl=https://services.gradle.org/distributions/gradle-$version-bin.zip\n"
    )
    val log = File(project, "legacy-$version.log")
    val command = listOf(
        File(javaHome, "bin/java").path, "-classpath", wrapperJar.path, "org.gradle.wrapper.GradleWrapperMain",
        task, "--no-daemon", "--console=plain", "--stacktrace",
        "-PexpectedJava=${if (current) "17" else "1.8"}", "-Dorg.gradle.java.installations.auto-download=false"
    ) + if (info) listOf("--info") else emptyList()
    println("Legacy Gradle control: $command")
    val process = ProcessBuilder(command).directory(project).redirectErrorStream(true).redirectOutput(log).apply {
        environment().remove("CI")
        environment()["JAVA_HOME"] = javaHome.path
    }.start()
    try {
        check(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { "Legacy proof timed out: $command" }
        val output = log.readText()
        println(output)
        if (expectedFailure == null) {
            assertThat(process.exitValue()).describedAs(output).isZero()
            assertThat(output).contains("BUILD SUCCESSFUL")
        } else {
            assertThat(process.exitValue()).isNotZero()
            assertThat(output).contains(expectedFailure)
        }
    } finally {
        if (process.isAlive) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
