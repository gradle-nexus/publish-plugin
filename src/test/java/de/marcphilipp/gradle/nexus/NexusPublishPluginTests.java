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
package de.marcphilipp.gradle.nexus;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.gson.Gson;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.TempDirectory;
import org.junitpioneer.jupiter.TempDirectory.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

@ExtendWith(TempDirectory.class)
@ExtendWith(WireMockExtension.class)
class NexusPublishPluginTests {

    private static final String STAGING_PROFILE_ID = "someProfileId";
    private static final String STAGED_REPOSITORY_ID = "orgexample-42";

    private Gson gson = new Gson();

    @Test
    void publishesToNexus(@TempDir Path projectDir, WireMockServer wireMockServer) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'");
        Files.write(projectDir.resolve("build.gradle"), List.of(
                "plugins {",
                "    id('java-library')",
                "    id('de.marcphilipp.nexus-publish')",
                "}",
                "group = 'org.example'",
                "version = '0.0.1'",
                "publishing {",
                "    publications {",
                "        mavenJava(MavenPublication) {",
                "            from(components.java)",
                "        }",
                "    }",
                "}",
                "nexusPublishing {",
                "    serverUrl = uri('" + wireMockServer.baseUrl() + "')",
                "    username = 'username'",
                "    password = 'password'",
                "}"));

        stubStagingProfileRequest(wireMockServer, Map.of("id", STAGING_PROFILE_ID, "name", "org.example"));
        stubCreateStagingRepoRequest(wireMockServer);
        expectArtifactUploads(wireMockServer);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertThat(result.task(":initializeNexusStagingRepository")).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(SUCCESS);
        assertUploadedToStagingRepo(wireMockServer, "/org/example/sample/0.0.1/sample-0.0.1.pom");
        assertUploadedToStagingRepo(wireMockServer, "/org/example/sample/0.0.1/sample-0.0.1.jar");
    }

    @Test
    void canBeUsedWithGradlePluginDevelopmentPlugin(@TempDir Path projectDir, WireMockServer wireMockServer) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'");
        Files.write(projectDir.resolve("build.gradle"), List.of(
                "plugins {",
                "    id('java-gradle-plugin')",
                "    id('de.marcphilipp.nexus-publish')",
                "}",
                "gradlePlugin {",
                "    plugins {",
                "        'foo' {",
                "            id = 'org.example.foo'",
                "            implementationClass = 'org.example.FooPlugin'",
                "        }",
                "    }",
                "}",
                "group = 'org.example'",
                "version = '0.0.1'",
                "nexusPublishing {",
                "    serverUrl = uri('" + wireMockServer.baseUrl() + "')",
                "    stagingProfileId = '" + STAGING_PROFILE_ID + "'",
                "    username = 'username'",
                "    password = 'password'",
                "}"));

        Path srcDir = Files.createDirectories(projectDir.resolve("src/main/java/org/example/"));
        Files.write(srcDir.resolve("FooPlugin.java"), List.of(
                "import org.gradle.api.*;",
                "public class FooPlugin implements Plugin<Project> {",
                "    public void apply(Project p) {}",
                "}"));

        stubCreateStagingRepoRequest(wireMockServer);
        expectArtifactUploads(wireMockServer);

        BuildResult result = runGradleBuild(projectDir, "publishToNexus");

        assertThat(result.task(":initializeNexusStagingRepository")).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(SUCCESS);
        assertUploadedToStagingRepo(wireMockServer, "/org/example/sample/0.0.1/sample-0.0.1.pom");
        assertUploadedToStagingRepo(wireMockServer, "/org/example/foo/org.example.foo.gradle.plugin/0.0.1/org.example.foo.gradle.plugin-0.0.1.pom");
    }

    private BuildResult runGradleBuild(@TempDir Path projectDir, String task) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(task, "--info", "--stacktrace")
                .build();
    }

    private static void assertUploadedToStagingRepo(WireMockServer wireMockServer, String path) {
        wireMockServer.verify(putRequestedFor(urlEqualTo("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + path)));
    }

    @SafeVarargs
    private void stubStagingProfileRequest(WireMockServer wireMockServer, Map<String, String>... stagingProfiles) {
        wireMockServer.stubFor(get(urlEqualTo("/staging/profiles"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", List.of(stagingProfiles))))));
    }

    private void stubCreateStagingRepoRequest(WireMockServer wireMockServer) {
        wireMockServer.stubFor(post(urlEqualTo("/staging/profiles/" + STAGING_PROFILE_ID + "/start"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", Map.of("stagedRepositoryId", STAGED_REPOSITORY_ID))))));
    }

    private void expectArtifactUploads(WireMockServer wireMockServer) {
        wireMockServer.stubFor(put(urlMatching("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + "/.+"))
                .willReturn(aResponse().withStatus(201)));
        wireMockServer.stubFor(get(urlMatching("/staging/deployByRepositoryId/" + STAGED_REPOSITORY_ID + "/.+/maven-metadata.xml"))
                .willReturn(aResponse().withStatus(404)));
    }
}