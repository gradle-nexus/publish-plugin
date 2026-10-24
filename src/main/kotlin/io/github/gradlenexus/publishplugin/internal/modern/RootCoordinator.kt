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

import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import java.time.Duration
import java.util.ArrayList
import java.util.Collections

/** Root-local provider conventions detach final root defaults during cache serialization.  */
class RootCoordinator internal constructor(root: Project, private val spec: CoordinatorSpec) {
    val useStaging: Property<Boolean>
    val packageGroup: Property<String>
    val repositoryDescription: Property<String>
    val repositories: List<Repository>

    init {
        val objects: ObjectFactory = root.objects
        useStaging = objects.property(Boolean::class.java)
        useStaging.convention(
            root.provider {
                spec.useStaging ?: !root.version.toString().endsWith("-SNAPSHOT")
            }
        )
        packageGroup = objects.property(String::class.java)
        packageGroup.convention(root.provider { spec.packageGroup ?: root.group.toString() })
        repositoryDescription = objects.property(String::class.java)
        repositoryDescription.convention(
            root.provider {
                spec.repositoryDescription ?: (root.group.toString() + ":" + root.name + ":" + root.version)
            }
        )
        val values: MutableList<Repository> = ArrayList<Repository>()
        for (repository in spec.repositories) values.add(Repository(root, repository))
        repositories = Collections.unmodifiableList(values)
    }

    val clientTimeout: Duration
        get() {
            return spec.clientTimeout
        }
    val connectTimeout: Duration
        get() {
            return spec.connectTimeout
        }
    val delayBetween: Duration
        get() {
            return spec.delayBetween
        }
    val maxRetries: Int
        get() {
            return spec.maxRetries
        }
    class Repository internal constructor(root: Project, private val spec: CoordinatorSpec.Repository) {
        val username: Property<String> = root.objects.property(String::class.java)
        val password: Property<String> = root.objects.property(String::class.java)

        init {
            username.convention(
                root.provider {
                    credential(
                        root,
                        spec.name + "Username",
                        spec.username
                    )
                }
            )
            password.convention(
                root.provider {
                    credential(
                        root,
                        spec.name + "Password",
                        spec.password
                    )
                }
            )
        }
        fun getName(): String =
            spec.name
        fun getSpec(): CoordinatorSpec.Repository =
            spec

        companion object {
            @JvmStatic
            private fun credential(root: Project, name: String, explicit: String?): String? {
                val value: Any? = explicit ?: root.findProperty(name)
                return value as? String
            }
        }
    }
    companion object {
        const val EXTENSION_NAME: String = "io.github.gradle-nexus.publish-plugin.coordinator"
    }
}
