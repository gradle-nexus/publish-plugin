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

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Removes only the dedicated format workspace; no user/native artifact directory is touched.
 */
@DisableCachingByDefault(because = "Local bundle validation requires current native export evidence")
abstract class ResetBundle : DefaultTask() {
    @get:Internal abstract val directory: DirectoryProperty

    @get:Internal abstract val stateDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Reset must execute for current local preparation") { true }
    }

    @TaskAction
    @Throws(Exception::class)
    fun reset() {
        clean(stateDirectory.get().asFile.toPath())
        clean(directory.get().asFile.toPath())
        logger.lifecycle("NEXUS-RESET $path")
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        private fun clean(root: Path) {
            if (Files.exists(root)) {
                Files.walk(root).use { files ->
                    for (path in Iterable { files.sorted(Comparator.reverseOrder()).iterator() }) Files.delete(path)
                }
            }
            Files.createDirectories(root)
        }
    }
}
