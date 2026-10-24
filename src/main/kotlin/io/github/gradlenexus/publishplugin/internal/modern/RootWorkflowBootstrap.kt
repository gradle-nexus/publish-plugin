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

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Root-local readiness join; workflow implementation loading remains lazy and root-owned.
 */
object RootWorkflowBootstrap {
    private const val REGISTERED: String = "io.github.gradle-nexus.publish-plugin.workflow-registered"
    private const val PRODUCER_READY: String = "io.github.gradle-nexus.publish-plugin.root-producer-ready"

    @JvmStatic
    fun producerReady(root: Project) {
        root.extensions.add(PRODUCER_READY, java.lang.Boolean.TRUE)
        ready(root)
    }

    @JvmStatic
    fun ready(root: Project) {
        if (root.extensions.findByName(PRODUCER_READY) == null || root.extensions.findByType(ProducerState::class.java) == null || root.extensions.findByType(RootCoordinator::class.java) == null || root.extensions.findByName(
                REGISTERED
            ) != null
        ) {
            return
        }
        try {
            val type: Class<out Plugin<Project>> = Class.forName("io.github.gradlenexus.publishplugin.internal.modern.workflow.RootWorkflowPlugin", true, RootWorkflowBootstrap::class.java.getClassLoader()).asSubclass(Plugin::class.java) as Class<*> as Class<out Plugin<Project>>
            root.pluginManager.apply(type)
            root.extensions.add(REGISTERED, java.lang.Boolean.TRUE)
        } catch (missing: ClassNotFoundException) {
            throw GradleException("NEXUS_WORKFLOW_IMPLEMENTATION_UNAVAILABLE", missing)
        }
    }
}
