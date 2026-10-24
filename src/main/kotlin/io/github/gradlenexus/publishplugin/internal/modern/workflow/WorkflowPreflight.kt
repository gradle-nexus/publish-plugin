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

import io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@DisableCachingByDefault(because = "Current invocation action and admission must be fresh")
abstract class WorkflowPreflight : WorkflowTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundles: ConfigurableFileCollection

    @get:Input abstract val producerPath: Property<String>

    @get:Input abstract val format: Property<String>

    @get:Internal abstract val workspace: DirectoryProperty

    @get:OutputDirectory abstract val staged: DirectoryProperty

    @TaskAction
    @Throws(Exception::class)
    fun stage() {
        WorkflowFiles.requireToken(workspace.get().asFile.toPath(), observation.get().getInvocation())
        if (bundles.files.size != 1) throw GradleException("NEXUS_EXPECTED_ONE_PRODUCER_BUNDLE")
        val source: Path = bundles.singleFile.toPath()
        val model: Map<String, Any> = BundleCodec.readModel(source)
        if (model["producerPath"] != producerPath.get() || model["format"] != format.get()) throw GradleException("NEXUS_PRODUCER_BUNDLE_IDENTITY_MISMATCH")
        val target: Path = staged.get().asFile.toPath()
        for (relative in WorkflowFiles.payloads(model)) {
            val from: Path = BundleCodec.ownedPayloadPath(source, relative)
            val to: Path = target.resolve(relative)
            Files.createDirectories(to.parent)
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        logger.lifecycle("NEXUS-WORKFLOW-STAGED " + producerPath.get())
    }
}
