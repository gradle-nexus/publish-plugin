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

import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import java.util.ArrayList
import java.util.Collections

/**
 * Root-local file collection only. Configuration names/routes derive solely from frozen strings.
 */
object RootBundleCollection {
    @JvmStatic
    fun configure(root: Project, bootstrap: BootstrapSpec) {
        for (format in bootstrap.getFormats()) {
            val capital: String = BootstrapSpec.capitalize(format)
            val units: MutableList<String> = ArrayList<String>()
            for (path in bootstrap.getProjectPaths()) {
                val excluded: Boolean = bootstrap.getExclusions().contains(path)
                var input: Any? = null
                if (!excluded) {
                    if (path == ":") {
                        val local: TaskProvider<PrepareBundle> = root.tasks.named("prepareNexus" + capital + "Bundle", PrepareBundle::class.java)
                        input = root.files(local.flatMap { obj: PrepareBundle -> obj.directory }).builtBy(root.tasks.named("resetNexus" + capital + "Bundle"), local)
                    } else {
                        val name: String = "nexus" + capital + "BundleInput" + bootstrap.getProjectPaths().indexOf(path)
                        val incoming: Configuration = root.configurations.create(name)
                        incoming.isCanBeConsumed = false
                        incoming.isCanBeResolved = true
                        incoming.attributes.attribute(ProducerBundles.FORMAT, format).attribute(ProducerBundles.SCHEMA, BootstrapSpec.BUNDLE_SCHEMA)
                        root.dependencies.add(name, root.dependencies.project(Collections.singletonMap("path", path)))
                        incoming.incoming.beforeResolve { println("NEXUS-RESOLVE $path $format") }
                        input = incoming
                    }
                }
                val taskPath: String = bootstrap.collectionTaskPath(path, format)
                if (!excluded) units.add(taskPath)
                val selected: Any? = input
                root.tasks.register(taskPath.substring(1), CollectBundles::class.java) { task ->
                    task.producerPath.set(path)
                    task.format.set(format)
                    task.excluded.set(excluded)
                    if (selected != null) task.bundles.from(selected)
                }
            }
            root.tasks.register("collectNexus" + capital + "Bundles") { task ->
                task.description = "Prepare and validate local $format bundles; does not publish remotely"
                task.dependsOn(units)
            }
        }
    }
}
