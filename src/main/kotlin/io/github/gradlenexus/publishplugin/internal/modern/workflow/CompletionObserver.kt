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

import org.gradle.api.GradleException
import org.gradle.api.provider.SetProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import java.util.Collections
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Detached completion observations only. No Gradle model, policy, credential, plan or session state.
 */
abstract class CompletionObserver :
    BuildService<CompletionObserver.Parameters>,
    OperationCompletionListener {
    interface Parameters : BuildServiceParameters {
        fun getRootTaskNames(): SetProperty<String>

        fun getProducerTaskPaths(): SetProperty<String>

        fun getNativeExportNamespaces(): SetProperty<String>

        fun getPublicOperationPaths(): SetProperty<String>
    }

    private val invocation: String = UUID.randomUUID().toString()
    private val outcomes: MutableMap<String, Outcome> = ConcurrentHashMap<String, Outcome>()
    private val delivered: MutableMap<String, CountDownLatch> = ConcurrentHashMap<String, CountDownLatch>()

    @Volatile
    private var rootPrefix: String? = null

    fun getInvocation(): String =
        invocation

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val task: TaskFinishEvent = event
        val path: String = task.descriptor.taskPath
        val name: String = path.substring(path.lastIndexOf(':') + 1)
        val local: String
        if (parameters.getRootTaskNames().get().contains(name)) {
            val prefix: String = path.substring(0, path.length - name.length)
            if (rootPrefix != null && rootPrefix != prefix) throw GradleException("NEXUS_WORKFLOW_ROOT_IDENTITY_CONFLICT")
            rootPrefix = prefix
            local = ":$name"
        } else {
            val prefix: String? = rootPrefix
            if (prefix == null || !path.startsWith(prefix)) return
            local = ":" + path.substring(prefix.length)
            if (!parameters.getProducerTaskPaths().get().contains(local) && !parameters.getPublicOperationPaths().get().contains(local) && !nativeExport(local)) return
        }
        val result: Any = task.result
        val status: String = when (result) {
            is TaskFailureResult -> {
                "FAILED"
            }

            is TaskSkippedResult -> {
                "SKIPPED"
            }

            is TaskSuccessResult -> {
                "SUCCESS"
            }

            else -> {
                "UNKNOWN"
            }
        }
        val upToDate: Boolean = result is TaskSuccessResult && result.isUpToDate
        val fromCache: Boolean = result is TaskSuccessResult && result.isFromCache
        outcomes[local] = Outcome(status, upToDate, fromCache)
        delivered.computeIfAbsent(local) { CountDownLatch(1) }.countDown()
    }

    private fun nativeExport(path: String): Boolean {
        for (namespace in parameters.getNativeExportNamespaces().get()) {
            if (!path.startsWith(namespace)) continue
            val suffix: String = path.substring(namespace.length)
            if (suffix.isNotEmpty() && suffix.indexOf(':') < 0) return true
        }
        return false
    }

    fun await(path: String): Map<String, Outcome> {
        try {
            if (!delivered.computeIfAbsent(path) { CountDownLatch(1) }.await(15, TimeUnit.SECONDS)) throw GradleException("NEXUS_WORKFLOW_CHECKPOINT_TIMEOUT")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleException("NEXUS_WORKFLOW_INTERRUPTED")
        }
        return Collections.unmodifiableMap(TreeMap(outcomes))
    }

    class Outcome internal constructor(
        internal val status: String,
        internal val upToDate: Boolean,
        internal val fromCache: Boolean
    )

    companion object {
        @JvmStatic
        fun requireFresh(values: Map<String, Outcome>, path: String?) {
            val value: Outcome? = values[path]
            if (value == null || (value.status != "SUCCESS") || value.upToDate || value.fromCache) {
                throw GradleException(
                    "NEXUS_WORKFLOW_NOT_SUCCESSFUL $path"
                )
            }
        }
    }
}
