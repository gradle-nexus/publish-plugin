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

package io.github.gradlenexus.publishplugin.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Invocation
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.regex.Pattern

/** Settings-only validation and diagnostics around the shared Nexus API, before HTTP follow-up handling. */
internal class SettingsNexusClientPolicy : Interceptor {
    // Reject errors before OkHttp's follow-up logic can replay a POST (including Retry-After: 0).
    val statusInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        try {
            validateStatus(response, operation(chain.request()))
            response
        } catch (failed: RuntimeException) {
            response.close()
            throw failed
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().header("Accept", "application/json").build()
        val operation = operation(request)
        try {
            val response = chain.proceed(request)
            try {
                // Application interceptors see the body after transparent gzip decompression.
                if (response.isSuccessful && operation != "closeStagingRepo" && operation != "releaseStagingRepo") {
                    validate(response.peekBody(Long.MAX_VALUE).string(), operation)
                }
                return response
            } catch (failed: Exception) {
                response.close()
                throw failed
            }
        } catch (failed: SocketTimeoutException) {
            throw requestFailure(request, true)
        } catch (failed: IOException) {
            throw requestFailure(request, false)
        }
    }

    private fun validateStatus(response: Response, operation: String) {
        val transition = operation in setOf("closeStagingRepo", "releaseStagingRepo", "getStagingRepoById")
        check(response.code() != 401 && response.code() != 403) {
            if (transition) "NEXUS_TRANSITION_AUTHENTICATION_REJECTED" else "NEXUS_AUTHENTICATION_REJECTED"
        }
        if (operation == "getStagingRepoById" && response.code() == 404) return
        check(if (operation == "getStagingRepoById") response.code() == 200 else response.isSuccessful) {
            val prefix = if (operation == "getStagingRepoById") {
                "NEXUS_TRANSITION_POLL"
            } else if (transition) {
                "NEXUS_TRANSITION"
            } else {
                "NEXUS_STAGING"
            }
            "${prefix}_HTTP_FAILURE status=${response.code()}"
        }
    }

    private fun validate(content: String, operation: String) {
        try {
            val value = JsonParser().parse(content).asJsonObject
            when (operation) {
                "getStagingProfiles" -> value.getAsJsonArray("data").forEach {
                    val profile = it.asJsonObject
                    string(profile, "name")
                    string(profile, "id")
                }

                "getStagingRepositories" -> value.getAsJsonArray("data").forEach {
                    val repository = it.asJsonObject
                    if (repository.has("description") && !repository.get("description").isJsonNull) {
                        string(repository, "description")
                        string(repository, "repositoryId")
                    }
                }

                "startStagingRepo" -> string(value.getAsJsonObject("data"), "stagedRepositoryId")

                "getStagingRepoById" -> {
                    string(value, "repositoryId")
                    string(value, "type")
                    check(value.get("transitioning").asJsonPrimitive.isBoolean)
                }
            }
        } catch (invalid: RuntimeException) {
            throw IllegalStateException(if (operation == "getStagingRepoById") "NEXUS_INVALID_TRANSITION_RESPONSE" else "NEXUS_INVALID_STAGING_RESPONSE")
        }
    }

    private fun string(value: JsonObject, field: String) {
        check(value.get(field).asJsonPrimitive.isString)
    }

    companion object {
        private fun operation(request: Request): String =
            requireNotNull(request.tag(Invocation::class.java)).method().name

        fun requestFailure(request: Request, timeout: Boolean): IllegalStateException {
            val operation = operation(request)
            val message = when {
                operation == "startStagingRepo" -> "NEXUS_STAGING_CREATE_AMBIGUOUS"

                operation in setOf("closeStagingRepo", "releaseStagingRepo", "getStagingRepoById") ->
                    if (timeout) "NEXUS_TRANSITION_REQUEST_TIMEOUT" else "NEXUS_TRANSITION_REQUEST_FAILED"

                else -> if (timeout) "NEXUS_STAGING_TIMEOUT" else "NEXUS_STAGING_IO_FAILED"
            }
            return IllegalStateException(message)
        }

        fun removedRepository(content: String, id: String): Boolean {
            try {
                val value = JsonParser().parse(content).asJsonObject
                if (value.has("repositoryId")) {
                    return value.get("repositoryId").isJsonPrimitive && value.get("repositoryId").asJsonPrimitive.isString &&
                        id == value.get("repositoryId").asString
                }
            } catch (ignored: RuntimeException) { /* Nexus 2 may return a non-JSON error document. */ }
            return Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(id) + "(?![A-Za-z0-9_-])").matcher(content).find()
        }
    }
}
