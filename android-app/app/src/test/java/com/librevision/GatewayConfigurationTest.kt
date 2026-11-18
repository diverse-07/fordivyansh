package com.librevision

import com.librevision.data.remote.GatewayConfiguration
import org.junit.Assert.*
import org.junit.Test

class GatewayConfigurationTest {
    private val deployed = "https://librevision-navigation-gateway.example.workers.dev"

    @Test
    fun installedAppUsesBundledDeploymentWithoutUserSetup() {
        assertEquals(deployed, GatewayConfiguration.resolve(deployed, false, null))
        assertEquals(deployed, GatewayConfiguration.resolve(deployed, true, null))
    }

    @Test
    fun releaseIgnoresStoredDeveloperConnections() {
        assertEquals(
            deployed,
            GatewayConfiguration.resolve(deployed, false, GatewayConfiguration.LOCAL_URL),
        )
        assertEquals(
            deployed,
            GatewayConfiguration.resolve(deployed, false, "https://other.example"),
        )
    }

    @Test
    fun debugCanOverrideForLocalTestingAndResetToDeployment() {
        assertEquals(
            GatewayConfiguration.LOCAL_URL,
            GatewayConfiguration.resolve(deployed, true, GatewayConfiguration.LOCAL_URL),
        )
        assertEquals(deployed, GatewayConfiguration.resolve(deployed, true, ""))
        assertEquals(
            deployed,
            GatewayConfiguration.resolve(deployed, true, "http://insecure.example"),
        )
    }

    @Test
    fun missingDeploymentDoesNotPretendToConnectInRelease() {
        assertEquals("", GatewayConfiguration.resolve("", false, null))
        assertEquals(GatewayConfiguration.LOCAL_URL, GatewayConfiguration.resolve("", true, null))
    }

    @Test
    fun credentialsAndApiPathsCannotBecomeGatewayOrigins() {
        for (url in
            listOf(
                "https://key@worker.example",
                "https://worker.example/assistant",
                "https://worker.example?token=key",
                "https://worker.example#key",
                "http://worker.example",
            )) {
            assertFalse(url, GatewayConfiguration.validUrl(url, false))
        }
        assertTrue(GatewayConfiguration.validUrl("$deployed/", false))
    }
}
