package io.apicurio.registry.noprofile;

import io.apicurio.registry.AbstractResourceTestBase;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The test clients must give up on a lost response instead of waiting for the class timeout (issue 10424).
 * The mechanism is covered in the SDK; this pins the values the test base hands to every client.
 */
@QuarkusTest
public class TestClientTimeoutsTest extends AbstractResourceTestBase {

    @Test
    public void v3ClientOptionsAreBounded() {
        RegistryClientOptions options = createV3ClientOptions(vertx);

        assertEquals(30_000, options.getConnectTimeoutMs());
        assertEquals(60_000, options.getReadIdleTimeoutMs());
        assertEquals(registryV3ApiUrl, options.getRegistryUrl());
    }

    @Test
    public void v2ClientOptionsAreBounded() {
        RegistryClientOptions options = createV2ClientOptions(vertx);

        assertEquals(30_000, options.getConnectTimeoutMs());
        assertEquals(60_000, options.getReadIdleTimeoutMs());
        assertEquals(registryV2ApiUrl, options.getRegistryUrl());
    }
}
