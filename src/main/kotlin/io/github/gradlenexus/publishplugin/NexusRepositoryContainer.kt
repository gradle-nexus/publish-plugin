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

package io.github.gradlenexus.publishplugin

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider

interface NexusRepositoryContainer : NamedDomainObjectContainer<NexusRepository> {
    val sonatype: NamedDomainObjectProvider<out NexusRepository>
        get() =
            // See https://github.com/gradle/gradle/issues/8057#issuecomment-826933995, Introduce TaskContainer.maybeNamed
            if ("sonatype" in names) {
                named("sonatype")
            } else {
                register("sonatype")
            }

    @Deprecated("Use sonatype property instead", ReplaceWith("sonatype"))
    fun sonatype(): NexusRepository = sonatype.get()

    fun sonatype(action: Action<in NexusRepository>): NexusRepository =
        sonatype.get().apply { action.execute(this) }
}
