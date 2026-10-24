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

package io.github.gradlenexus.publishplugin.internal.modern

import io.github.gradlenexus.publishplugin.internal.modern.bundle.ProducerBundles
import io.github.gradlenexus.publishplugin.internal.modern.bundle.RootBundleCollection
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.plugins.PublishingPlugin
import java.util.ArrayList

/**
 * Own-project preparation and lifecycle routes; all remote work remains in the root coordinator.
 */
class InternalProducerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.extensions.findByType(ProducerState::class.java) == null) {
            throw GradleException("The internal producer requires Settings application of io.github.gradle-nexus.publish-plugin")
        }
        val bootstrap: BootstrapSpec = project.extensions.getByType(ProducerState::class.java).getBootstrap()
        ProducerBundles.apply(project, bootstrap)
        val index: Int = bootstrap.getProjectPaths().indexOf(project.path)
        val ownRoutes: MutableList<String> = ArrayList<String>()
        for (route in bootstrap.repositories) {
            project.tasks.register(route.getLocalAlias()) { task ->
                task.group = PublishingPlugin.PUBLISH_TASK_GROUP
                task.description =
                    "Publishes to Nexus repository '" + route.getName() + "' through the root coordinator."
                task.dependsOn(route.unitPath(index))
            }
            if (!bootstrap.getExclusions().contains(project.path)) ownRoutes.add(route.unitPath(index))
        }
        if (ownRoutes.isNotEmpty()) {
            val dependencies: List<String> = java.util.List.copyOf(ownRoutes)
            project.plugins.withType(PublishingPlugin::class.java) {
                project.tasks.named(PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME).configure { task ->
                    task.dependsOn(dependencies)
                }
            }
        }
        if (project.path.equals(":")) {
            RootBundleCollection.configure(project, bootstrap)
            RootWorkflowBootstrap.producerReady(project)
        }
    }
    companion object {
        const val STATE_NAME: String = "io.github.gradle-nexus.publish-plugin.producer"
    }
}
