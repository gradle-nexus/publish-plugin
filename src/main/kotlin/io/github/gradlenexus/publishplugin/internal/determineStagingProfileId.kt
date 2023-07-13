package io.github.gradlenexus.publishplugin.internal

import io.github.gradlenexus.publishplugin.NexusRepository
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

internal fun determineStagingProfileId(
    client: NexusClient,
    logger: Logger,
    repository: NexusRepository,
    packageGroup: String
): String {
    var stagingProfileId = repository.stagingProfileId.orNull
    if (stagingProfileId == null) {
        logger.info("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
        stagingProfileId = client.findStagingProfileId(packageGroup)
            ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
    }
    return stagingProfileId
}
