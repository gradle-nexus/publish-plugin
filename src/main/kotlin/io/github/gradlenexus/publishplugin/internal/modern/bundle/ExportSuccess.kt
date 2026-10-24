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

import org.gradle.api.Action
import org.gradle.api.Task
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.nio.file.Files

/**
 * Local native-action success only, not invocation/session admission and not bundled payload.
 */
class ExportSuccess(private val witness: File) :
    Action<Task>,
    Serializable {

    override fun execute(task: Task) {
        try {
            Files.createDirectories(witness.toPath().parent)
            Files.writeString(witness.toPath(), "native-export-succeeded\n")
        } catch (failure: IOException) {
            throw IllegalStateException("NEXUS_EXPORT_WITNESS_FAILED", failure)
        }
        task.logger.lifecycle("NEXUS-NATIVE-EXPORTED " + task.path)
    }
}
