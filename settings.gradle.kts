plugins {
    id("com.gradle.enterprise") version "3.14"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

val isCiBuild = System.getenv("CI") != null

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
        publishAlwaysIf(isCiBuild)
    }
}

rootProject.name = "gradle-nexus-publish-plugin"
