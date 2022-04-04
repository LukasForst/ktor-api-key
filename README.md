# Ktor API Key Authentication Provider

Simple authentication provider for Ktor that verifies presence of the API key in the header. Useful if you want to use `X-Api-Key` or
similar approaches for request authentication.

## Installation

Include following in your `build.gradle.kts`:

```kotlin
implementation("dev.forst", "ktor-api-key", "1.0.0")
```

## Usage

This is minimal implementation of the Ktor app that uses API Key authentication:

```kotlin
/**
 * Minimal Ktor application with API Key authentication.
 */
fun Application.minimalExample() {
    // key that will be used to authenticate requests
    val expectedApiKey = "this-is-expected-key"

    // principal for the app
    data class AppPrincipal(val key: String) : Principal
    // now we install authentication feature
    install(Authentication) {
        // and then api key provider
        apiKey {
            // set function that is used to verify request
            validate { keyFromHeader ->
                keyFromHeader
                    .takeIf { it == expectedApiKey }
                    ?.let { AppPrincipal(it) }
            }
        }
    }

    routing {
        authenticate {
            get {
                val p = call.principal<AppPrincipal>()!!
                call.respondText("Key: ${p.key}")
            }
        }
    }
}
```

For details see [MinimalExampleApp.kt](src/test/kotlin/dev/forst/ktor/apikey/MinimalExampleApp.kt) with this example application and [TestMinimalExampleApp.kt](src/test/kotlin/dev/forst/ktor/apikey/TestMinimalExampleApp.kt) which verifies that this app works as expected.

For more advanced configuration see [TestApiKeyAuth.kt](src/test/kotlin/dev/forst/ktor/apikey/TestApiKeyAuth.kt).
