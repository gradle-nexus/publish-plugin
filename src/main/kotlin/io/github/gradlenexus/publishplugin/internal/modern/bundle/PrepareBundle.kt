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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files

@DisableCachingByDefault(because = "Local bundle validation requires current native export evidence")
abstract class PrepareBundle : DefaultTask() {
    @get:Internal abstract val directory: DirectoryProperty

    @get:Internal abstract val stateDirectory: DirectoryProperty

    @get:OutputFile abstract val manifest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exportedPayloads: ConfigurableFileCollection

    @get:Input abstract val producerPath: Property<String>

    @get:Input abstract val format: Property<String>

    @get:Input abstract val publications: MapProperty<String, String>

    init {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Native export witnesses must come from this preparation") { true }
    }

    @TaskAction
    @Throws(Exception::class)
    fun prepare() {
        for (name in publications.get().keys) {
            check(Files.isRegularFile(stateDirectory.get().asFile.toPath().resolve(BundleCodec.namespace(name) + ".ok"))) { "NEXUS_EXPORT_DID_NOT_SUCCEED" }
        }
        BundleCodec.write(directory.get().asFile.toPath(), producerPath.get(), format.get(), publications.get())
        logger.lifecycle("NEXUS-PREPARED " + producerPath.get() + " " + format.get())
    }
}
