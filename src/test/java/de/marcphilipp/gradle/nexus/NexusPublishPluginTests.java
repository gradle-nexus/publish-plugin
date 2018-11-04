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

    @Test
    void publishesToNexus(@TempDir Path tempDir, WireMockServer wireMockServer) throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'sample'");
        Files.write(tempDir.resolve("build.gradle"), List.of(
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

        String stagingProfileId = "someProfileId";
        String stagedRepositoryId = "orgexample-42";

        Gson gson = new Gson();
        wireMockServer.stubFor(get(urlEqualTo("/staging/profiles"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", List.of(Map.of("id", stagingProfileId, "name", "org.example")))))));
        wireMockServer.stubFor(post(urlEqualTo("/staging/profiles/" + stagingProfileId + "/start"))
                .willReturn(aResponse().withBody(gson.toJson(Map.of("data", Map.of("stagedRepositoryId", stagedRepositoryId))))));
        expectArtifactUploads(wireMockServer, stagedRepositoryId);

        BuildResult result = GradleRunner.create()
                .withProjectDir(tempDir.toFile())
                .withPluginClasspath()
                .withArguments("publishToNexus", "--info", "--stacktrace")
                .build();

        assertThat(result.task(":initializeNexusStagingRepository")).isNotNull()
                .extracting(BuildTask::getOutcome).isEqualTo(SUCCESS);
        wireMockServer.verify(
                putRequestedFor(urlEqualTo("/staging/deployByRepositoryId/" + stagedRepositoryId + "/org/example/sample/0.0.1/sample-0.0.1.pom")));
    }

    private void expectArtifactUploads(WireMockServer wireMockServer, String stagedRepositoryId) {
        wireMockServer.stubFor(put(urlMatching("/staging/deployByRepositoryId/" + stagedRepositoryId + "/.+"))
                .willReturn(aResponse().withStatus(201)));
        wireMockServer.stubFor(get(urlMatching("/staging/deployByRepositoryId/" + stagedRepositoryId + "/.+/maven-metadata.xml"))
                .willReturn(aResponse().withStatus(404)));
    }
}