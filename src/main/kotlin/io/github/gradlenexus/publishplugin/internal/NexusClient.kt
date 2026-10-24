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

import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

open class NexusClient private constructor(
    private val baseUrl: URI,
    username: String?,
    password: String?,
    timeout: Duration?,
    connectTimeout: Duration?,
    private val settingsPolicy: Boolean
) : Closeable {
    constructor(baseUrl: URI, username: String?, password: String?, timeout: Duration?, connectTimeout: Duration?) :
        this(baseUrl, username, password, timeout, connectTimeout, false)

    private val api: NexusApi
    private val httpClient: OkHttpClient

    init {
        val httpClientBuilder = OkHttpClient.Builder()
        if (timeout != null) {
            httpClientBuilder
                .readTimeout(timeout)
                .writeTimeout(timeout)
        }
        if (connectTimeout != null) {
            httpClientBuilder.connectTimeout(connectTimeout)
        }
        if (username != null || password != null) {
            val credentials = if (settingsPolicy) {
                Credentials.basic(username ?: "", password ?: "", StandardCharsets.UTF_8)
            } else {
                Credentials.basic(username ?: "", password ?: "")
            }
            httpClientBuilder
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", credentials)
                            .build()
                    )
                }
        }
        httpClientBuilder.addInterceptor { chain ->
            val version = javaClass.`package`.implementationVersion ?: "dev"
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "gradle-nexus-publish-plugin/$version")
                    .build()
            )
        }
        if (settingsPolicy) {
            val policy = SettingsNexusClientPolicy()
            httpClientBuilder.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addInterceptor(policy).addNetworkInterceptor(policy.statusInterceptor)
        }
        httpClient = httpClientBuilder.build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl.toString())
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(NexusApi::class.java)
    }

    fun findStagingProfileId(packageGroup: String): String? {
        val response = execute(api.stagingProfiles)
        if (!response.isSuccessful) {
            throw failure("load staging profiles", response)
        }
        return response.body()
            ?.data
            ?.filter { profile ->
                // profile.name either matches exactly
                // or it is a prefix of a packageGroup
                packageGroup.startsWith(profile.name) &&
                    (
                        packageGroup.length == profile.name.length ||
                            packageGroup[profile.name.length] == '.'
                        )
            }
            ?.maxBy { it.name.length }
            ?.id
    }

    fun findStagingRepository(stagingProfileId: String, descriptionRegex: Regex): StagingRepositoryDescriptor {
        val response = execute(api.getStagingRepositories(stagingProfileId))
        if (!response.isSuccessful) {
            throw failure("find staging repository, stagingProfileId: $stagingProfileId", response)
        }
        val data = response.body()?.data
        if (data.isNullOrEmpty()) {
            check(!settingsPolicy) { "NEXUS_FIND_REQUIRES_EXACTLY_ONE_REPOSITORY" }
            throw NoSuchElementException("No staging repositories found for stagingProfileId: $stagingProfileId")
        }
        val matchingRepositories = data.filter { it.description?.contains(descriptionRegex) == true }
        check(!settingsPolicy || matchingRepositories.size == 1) { "NEXUS_FIND_REQUIRES_EXACTLY_ONE_REPOSITORY" }
        if (matchingRepositories.isEmpty()) {
            throw NoSuchElementException(
                "No staging repositories found for stagingProfileId: $stagingProfileId, descriptionRegex: $descriptionRegex. " +
                    "Here are all the repositories: $data"
            )
        }
        if (matchingRepositories.size > 1) {
            throw IllegalStateException(
                "Too many repositories found for stagingProfileId: $stagingProfileId, descriptionRegex: $descriptionRegex. " +
                    "If some of the repositories are not needed, consider deleting them manually. " +
                    "Here are the repositories matching the regular expression: $matchingRepositories"
            )
        }
        return StagingRepositoryDescriptor(baseUrl, matchingRepositories.first().repositoryId)
    }

    fun createStagingRepository(stagingProfileId: String, description: String): StagingRepositoryDescriptor {
        val response = execute(api.startStagingRepo(stagingProfileId, Dto(Description(description))))
        if (!response.isSuccessful) {
            throw failure("create staging repository", response)
        }
        val stagingRepositoryId = response.body()?.data?.stagedRepositoryId ?: throw RuntimeException("No response body")
        return StagingRepositoryDescriptor(baseUrl, stagingRepositoryId)
    }

    open fun closeStagingRepository(stagingRepositoryId: String, description: String) {
        val response = execute(api.closeStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), description))))
        if (!response.isSuccessful) {
            throw failure("close staging repository", response)
        }
    }

    open fun releaseStagingRepository(stagingRepositoryId: String, description: String) {
        val response = execute(api.releaseStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), description))))
        if (!response.isSuccessful) {
            throw failure("release staging repository", response)
        }
    }

    open fun getStagingRepositoryStateById(stagingRepositoryId: String): StagingRepository {
        val response = execute(api.getStagingRepoById(stagingRepositoryId))
        if (response.code() == 404) {
            val content = response.errorBody()?.string()
            val removed = if (settingsPolicy) {
                SettingsNexusClientPolicy.removedRepository(content ?: "", stagingRepositoryId)
            } else {
                content?.contains(stagingRepositoryId) == true
            }
            if (removed) return StagingRepository.notFound(stagingRepositoryId)
            check(!settingsPolicy) { "NEXUS_TRANSITION_POLL_HTTP_FAILURE status=404" }
        }
        if (!response.isSuccessful) {
            throw failure("get staging repository by id", response)
        }
        val readStagingRepo: ReadStagingRepository? = response.body()
        if (readStagingRepo != null) {
            if (settingsPolicy) {
                check(stagingRepositoryId == readStagingRepo.repositoryId) { "NEXUS_TRANSITION_WRONG_REPOSITORY" }
                check(readStagingRepo.type in setOf("open", "closed", "released")) { "NEXUS_TRANSITION_WRONG_STATE" }
            }
            require(stagingRepositoryId == readStagingRepo.repositoryId) {
                "Unexpected read repository id ($stagingRepositoryId != ${readStagingRepo.repositoryId})"
            }
            return StagingRepository(
                readStagingRepo.repositoryId,
                StagingRepository.State.parseString(readStagingRepo.type),
                readStagingRepo.transitioning
            )
        } else {
            return StagingRepository.notFound(stagingRepositoryId) // Should not happen
        }
    }

    override fun close() {
        httpClient.dispatcher().executorService().shutdown()
        httpClient.connectionPool().evictAll()
    }

    private fun <T> execute(call: Call<T>): Response<T> {
        if (!settingsPolicy) return call.execute()
        try {
            return call.execute()
        } catch (failed: SocketTimeoutException) {
            throw SettingsNexusClientPolicy.requestFailure(call.request(), true)
        } catch (failed: IOException) {
            throw SettingsNexusClientPolicy.requestFailure(call.request(), false)
        }
    }

    companion object {
        /** Strict execution-local requests for the Settings workflow; legacy defaults remain unchanged. */
        internal fun forSettings(baseUrl: URI, username: String?, password: String?, connectTimeout: Int, requestTimeout: Int): NexusClient =
            NexusClient(baseUrl, username, password, Duration.ofMillis(requestTimeout.toLong()), Duration.ofMillis(connectTimeout.toLong()), true)
    }

    // TODO: Cover all API calls with unified error handling (including unexpected IOExceptions)
    private fun failure(action: String, response: Response<*>): RuntimeException {
        var message = "Failed to $action, server at $baseUrl responded with status code ${response.code()}"
        val errorBody = response.errorBody()
        if (errorBody != null) {
            message += try {
                ", body: ${errorBody.string()}"
            } catch (exception: IOException) {
                ", body: <error while reading body of error response, message: ${exception.message}>"
            }
        }
        return RuntimeException(message)
    }

    private interface NexusApi {

        companion object {
            private const val RELEASE_OPERATION_NAME_IN_NEXUS = "promote" // promote and release use the same operation, provided body parameters matter
        }

        @get:Headers("Accept: application/json")
        @get:GET("staging/profiles")
        val stagingProfiles: Call<Dto<List<StagingProfile>>>

        @Headers("Content-Type: application/json")
        @POST("staging/profiles/{stagingProfileId}/start")
        fun startStagingRepo(@Path("stagingProfileId") stagingProfileId: String, @Body description: Dto<Description>): Call<Dto<CreatedStagingRepository>>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/close")
        fun closeStagingRepo(@Body stagingRepoToClose: Dto<StagingRepositoryToTransit>): Call<Unit>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/$RELEASE_OPERATION_NAME_IN_NEXUS")
        fun releaseStagingRepo(@Body stagingRepoToClose: Dto<StagingRepositoryToTransit>): Call<Unit>

        @Headers("Accept: application/json")
        @GET("staging/repository/{stagingRepoId}")
        fun getStagingRepoById(@Path("stagingRepoId") stagingRepoId: String): Call<ReadStagingRepository>

        @Headers("Accept: application/json")
        @GET("staging/profile_repositories/{stagingProfileId}")
        fun getStagingRepositories(@Path("stagingProfileId") stagingProfileId: String): Call<Dto<List<ReadStagingRepository>>>
    }

    data class Dto<T>(var data: T)

    data class StagingProfile(var id: String, var name: String)

    data class Description(val description: String)

    data class CreatedStagingRepository(var stagedRepositoryId: String)

    data class ReadStagingRepository(var repositoryId: String, var type: String, var transitioning: Boolean, var description: String?)

    data class StagingRepositoryToTransit(val stagedRepositoryIds: List<String>, val description: String, val autoDropAfterRelease: Boolean = true)
}
