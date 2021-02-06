plugins {
    id("com.gradle.enterprise") version "3.5"
}

gradleEnterprise {
    buildScan {
        termsOfServiceUrl = "https://gradle.com/terms-of-service"
        termsOfServiceAgree = "yes"
    }
}

// this needs to stay this way since it's used as the plugin's artifact id
// for generating the plugin marker
rootProject.name = "publish-plugin"
