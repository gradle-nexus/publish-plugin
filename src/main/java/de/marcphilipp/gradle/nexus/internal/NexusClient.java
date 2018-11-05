/*
 * Copyright 2018 the original author or authors.
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
package de.marcphilipp.gradle.nexus.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class NexusClient {

    private final URI baseUrl;
    private final NexusApi api;

    public NexusClient(URI baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(1, TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()))
                .build();
        Gson gson = new GsonBuilder()
                .registerTypeAdapterFactory(new WrappingTypeAdapterFactory())
                .create();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl.toString())
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        api = retrofit.create(NexusApi.class);
    }

    public Optional<String> findStagingProfileId(String packageGroup) {
        try {
            Response<List<StagingProfile>> response = api.getStagingProfiles().execute();
            if (!response.isSuccessful()) {
                throw failure("load staging profiles", response);
            }
            return response.body().stream()
                    .filter(profile -> profile.getName().equals(packageGroup))
                    .map(StagingProfile::getId)
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public URI createStagingRepository(String stagingProfileId) {
        try {
            Response<StagingRepository> response = api.startStagingRepo(stagingProfileId, new Description("publishing")).execute();
            if (!response.isSuccessful()) {
                throw failure("create staging repository", response);
            }
            String baseUrl = this.baseUrl.toString();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            return URI.create(baseUrl + "staging/deployByRepositoryId/" + response.body().getStagedRepositoryId());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RuntimeException failure(String action, Response<?> response) {
        String message = "Failed to " + action + ", server responded with status code " + response.code();
        if (response.errorBody() != null && response.errorBody().contentLength() > 0) {
            try {
                message += ", body: " + response.errorBody().string();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read body of error response", e);
            }
        }
        return new RuntimeException(message);
    }

    private interface NexusApi {

        @Headers("Accept: application/json")
        @GET("staging/profiles")
        Call<List<StagingProfile>> getStagingProfiles();

        @Headers("Content-Type: application/json")
        @POST("staging/profiles/{stagingProfileId}/start")
        Call<StagingRepository> startStagingRepo(@Path("stagingProfileId") String stagingProfileId, @Body Description description);

    }

    public static class StagingProfile {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Description {
        private final String description;

        public Description(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class StagingRepository {
        private String stagedRepositoryId;

        public String getStagedRepositoryId() {
            return stagedRepositoryId;
        }

        public void setStagedRepositoryId(String stagedRepositoryId) {
            this.stagedRepositoryId = stagedRepositoryId;
        }
    }

    private static class WrappingTypeAdapterFactory implements TypeAdapterFactory {

        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
            return new TypeAdapter<T>() {
                public void write(JsonWriter out, T value) throws IOException {
                    JsonObject jsonObject = new JsonObject();
                    jsonObject.add("data", delegate.toJsonTree(value));
                    elementAdapter.write(out, jsonObject);
                }

                public T read(JsonReader in) throws IOException {
                    JsonElement jsonElement = elementAdapter.read(in);
                    if (jsonElement.isJsonObject()) {
                        JsonObject jsonObject = jsonElement.getAsJsonObject();
                        if (jsonObject.has("data")) {
                            jsonElement = jsonObject.get("data");
                        }
                    }
                    return delegate.fromJsonTree(jsonElement);
                }
            }.nullSafe();
        }
    }

}
