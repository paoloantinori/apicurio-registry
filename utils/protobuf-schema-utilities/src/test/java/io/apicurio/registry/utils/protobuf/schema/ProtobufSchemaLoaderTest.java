package io.apicurio.registry.utils.protobuf.schema;

import com.squareup.wire.schema.MessageType;
import com.squareup.wire.schema.SchemaException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProtobufSchemaLoaderTest {

    private static final String DEPENDENCY_PATH = "com/example/common/v1/address.proto";

    private static final String DEPENDENCY = """
            syntax = "proto3";
            package com.example.common.v1;
            message Address {
              string city = 1;
            }
            """;

    private static String orderImporting(String importPath) {
        return """
                syntax = "proto3";
                package com.example.orders.v1;
                import "%s";
                message Order {
                  com.example.common.v1.Address shipping = 1;
                }
                """.formatted(importPath);
    }

    @Test
    public void testImportWithMatchingCaseLoads() throws Exception {
        var context = ProtobufSchemaLoader.loadSchema("order.proto", orderImporting(DEPENDENCY_PATH),
                Map.of(DEPENDENCY_PATH, DEPENDENCY));

        assertEquals("com/example/orders/v1/order.proto", context.getProtoFile().getLocation().getPath());
        var order = (MessageType) context.getSchema().getType("com.example.orders.v1.Order");
        assertEquals("com.example.common.v1.Address", order.field("shipping").getType().toString());
    }

    /**
     * Proto import paths are case-sensitive on Linux, where the registry runs. The staging filesystem is
     * case-sensitive on every host, so a macOS build cannot accept an import that production rejects. On
     * Linux the old disk staging was case-sensitive too, so only a case-insensitive host (macOS) tells
     * the two apart; there is no Linux behaviour for this test to catch.
     */
    @Test
    public void testImportWithWrongCaseFailsOnEveryHost() {
        var schema = orderImporting("com/example/common/v1/Address.proto");

        var e = assertThrows(SchemaException.class,
                () -> ProtobufSchemaLoader.loadSchema("order.proto", schema, Map.of(DEPENDENCY_PATH, DEPENDENCY)));
        assertTrue(e.getMessage().contains("com/example/common/v1/Address.proto"), e.getMessage());
    }
}
