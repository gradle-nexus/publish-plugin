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

package io.github.gradlenexus.publishplugin.internal.modern.workflow

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@DisableCachingByDefault(because = "Current invocation action and admission must be fresh")
abstract class WorkflowReset : WorkflowTask() {
    @get:OutputDirectory abstract val workspace: DirectoryProperty

    @TaskAction
    @Throws(Exception::class)
    fun reset() {
        val root: Path = workspace.get().asFile.toPath()
        if (Files.exists(root)) {
            Files.walk(root).use { files ->
                for (path in files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path)
            }
        }
        Files.createDirectories(root.resolve("staged"))
        Files.writeString(root.resolve("invocation.txt"), observation.get().getInvocation())
        logger.lifecycle("NEXUS-WORKFLOW-RESET $path")
    }
}
