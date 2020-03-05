/*
 * Copyright 2019 the original author or authors.
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

import io.github.gradlenexus.publishplugin.StagingRepository
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.time.Duration
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

open class NexusClient(private val baseUrl: URI, username: String?, password: String?, timeout: Duration?, connectTimeout: Duration?) {
    private val api: NexusApi

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
            val credentials = Credentials.basic(username ?: "", password ?: "")
            httpClientBuilder
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder()
                                .header("Authorization", credentials)
                                .build())
                    }
        }
        val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl.toString())
                .client(httpClientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        api = retrofit.create(NexusApi::class.java)
    }

    fun findStagingProfileId(packageGroup: String): String? {
        val response = api.stagingProfiles.execute()
        if (!response.isSuccessful) {
            throw failure("load staging profiles", response)
        }
        return response.body()
                ?.data
                ?.filter { profile ->
                    // profile.name either matches exactly
                    // or it is a prefix of a packageGroup
                    packageGroup.startsWith(profile.name) &&
                            (packageGroup.length == profile.name.length ||
                                    packageGroup[profile.name.length] == '.')
                }
                ?.maxBy { it.name.length }
                ?.id
    }

    fun createStagingRepository(stagingProfileId: String): String {
        val response = api.startStagingRepo(stagingProfileId, Dto(Description("Created by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("create staging repository", response)
        }
        return response.body()?.data?.stagedRepositoryId ?: throw RuntimeException("No response body")
    }

    open fun closeStagingRepository(stagingRepositoryId: String) {
        val response = api.closeStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), "Closed by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("close staging repository", response)
        }
    }

    open fun releaseStagingRepository(stagingRepositoryId: String) {
        val response = api.releaseStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), "Release by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("release staging repository", response)
        }
    }

    fun getStagingRepositoryUri(stagingRepositoryId: String): URI =
            URI.create("${baseUrl.toString().removeSuffix("/")}/staging/deployByRepositoryId/$stagingRepositoryId")

    open fun getStagingRepositoryStateById(stagingRepositoryId: String): StagingRepository {
        val response = api.getStagingRepoById(stagingRepositoryId).execute()
        if (response.code() == 404 && response.errorBody()?.string()?.contains(stagingRepositoryId) == true) {
            return StagingRepository.notFound(stagingRepositoryId)
        }
        if (!response.isSuccessful) {
            throw failure("get staging repository by id", response)
        }
        val readStagingRepo: ReadStagingRepository? = response.body()
        if (readStagingRepo != null) {
            require(stagingRepositoryId == readStagingRepo.repositoryId) {
                "Unexpected read repository id ($stagingRepositoryId != ${readStagingRepo.repositoryId})"
            }
            return StagingRepository(readStagingRepo.repositoryId, StagingRepository.State.parseString(readStagingRepo.type),
                    readStagingRepo.transitioning)
        } else {
            return StagingRepository.notFound(stagingRepositoryId) //Should not happen
        }
    }

    // TODO: Cover all API calls with unified error handling (including unexpected IOExceptions)
    private fun failure(action: String, response: Response<*>): RuntimeException {
        var message = "Failed to " + action + ", server responded with status code " + response.code()
        val errorBody = response.errorBody()
        if (errorBody != null && errorBody.contentLength() > 0) {
            try {
                message += ", body: " + errorBody.string()
            } catch (e: IOException) {
                throw UncheckedIOException("Failed to read body of error response", e)
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
        fun startStagingRepo(@Path("stagingProfileId") stagingProfileId: String, @Body description: Dto<Description>):
                Call<Dto<CreatedStagingRepository>>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/close")
        fun closeStagingRepo(@Body stagingRepoToClose: Dto<StagingRepositoryToTransit>): Call<Unit>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/$RELEASE_OPERATION_NAME_IN_NEXUS")
        fun releaseStagingRepo(@Body stagingRepoToClose: Dto<StagingRepositoryToTransit>): Call<Unit>

        @Headers("Accept: application/json")
        @GET("staging/repository/{stagingRepoId}")
        fun getStagingRepoById(@Path("stagingRepoId") stagingRepoId: String): Call<ReadStagingRepository>
    }

    data class Dto<T>(var data: T)

    data class StagingProfile(var id: String, var name: String)

    data class Description(val description: String)

    data class CreatedStagingRepository(var stagedRepositoryId: String)

    data class ReadStagingRepository(var repositoryId: String, var type: String, var transitioning: Boolean)

    data class StagingRepositoryToTransit(val stagedRepositoryIds: List<String>, val description: String, val autoDropAfterRelease: Boolean = true)
}
