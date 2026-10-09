package io.apicurio.registry.utils.protobuf.schema;

import com.google.common.collect.ImmutableSet;
import com.squareup.wire.schema.JdkSchemaLoader;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProtobufSchemaLoader {

    private static final String GOOGLE_API_PATH = "google/type/";
    private static final String GOOGLE_WELLKNOWN_PATH = "google/protobuf/";
    private static final String METADATA_PATH = "metadata/";
    private static final String DECIMAL_PATH = "additionalTypes/";
    // Adding pre-built support for commonly used Google API Protos,
    // https://github.com/googleapis/googleapis
    // These files need to be manually staged into the loader roots
    // as antiwire doesn't support them by default.
    private final static Set<String> GOOGLE_API_PROTOS = ImmutableSet.<String> builder().add("money.proto")
            .add("timeofday.proto").add("date.proto").add("calendar_period.proto").add("color.proto")
            .add("dayofweek.proto").add("latlng.proto").add("fraction.proto").add("month.proto")
            .add("phone_number.proto").add("postal_address.proto").add("localized_text.proto")
            .add("interval.proto").add("expr.proto").add("quaternion.proto").build();
    // Adding support for Protobuf well-known types under package google.protobuf that are not covered by
    // antiwire
    // https://developers.google.com/protocol-buffers/docs/reference/google.protobuf
    // These files need to be manually staged into the loader roots
    // as antiwire doesn't support them by default.
    private final static Set<String> GOOGLE_WELLKNOWN_PROTOS = ImmutableSet.<String> builder()
            .add("api.proto").add("field_mask.proto").add("source_context.proto").add("struct.proto")
            .add("type.proto").build();

    private final static String METADATA_PROTO = "metadata.proto";
    private final static String DECIMAL_PROTO = "decimal.proto";

    private static void stagePrebuiltProtos(Path rootDirectory, String protoPath, Set<String> protos)
            throws IOException {
        final ClassLoader classLoader = ProtobufSchemaLoader.class.getClassLoader();
        for (String proto : protos) {
            Path target = rootDirectory.resolve(protoPath + proto);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            try (InputStream inputStream = classLoader.getResourceAsStream(protoPath + proto)) {
                if (inputStream == null) {
                    throw new IOException("Missing pre-built proto resource: " + protoPath + proto);
                }
                Files.copy(inputStream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Creates a schema loader staging the schema and its dependencies into a temporary directory. This is
     * required for the antiwire schema parser and linker to load the types correctly. See
     * https://github.com/square/wire/issues/2024# As of now this only supports reading one .proto file but
     * can be extended to support reading multiple files.
     *
     * @param fileName Name of the .proto file.
     * @param schemaDefinition Schema Definition to parse.
     * @param deps Schema Definitions of dependencies, keyed by import path.
     * @return ProtobufSchemaLoaderContext - parsed and properly linked Schema.
     */
    public static ProtobufSchemaLoaderContext loadSchema(String fileName, String schemaDefinition, Map<String, String> deps) throws IOException {
        String protoFileName = fileName.endsWith(".proto") ? fileName : fileName + ".proto";

        Path rootDirectory = Files.createTempDirectory("apicurio-protobuf-schema-");
        try {
            stagePrebuiltProtos(rootDirectory, GOOGLE_API_PATH, GOOGLE_API_PROTOS);
            stagePrebuiltProtos(rootDirectory, GOOGLE_WELLKNOWN_PATH, GOOGLE_WELLKNOWN_PROTOS);
            stagePrebuiltProtos(rootDirectory, METADATA_PATH, Collections.singleton(METADATA_PROTO));
            stagePrebuiltProtos(rootDirectory, DECIMAL_PATH, Collections.singleton(DECIMAL_PROTO));

            // Step 1: Convert all .proto files to ProtoContent instances
            ProtoContent protoContent = new ProtoContent(protoFileName, schemaDefinition);
            List<ProtoContent> allDepencencies = deps.entrySet().stream().map(entry ->
                    new ProtoContent(entry.getKey(), entry.getValue())).collect(Collectors.toList());

            // Step 2: Fix up the import statements in all .proto files so they point to canonical locations
            allDepencencies.forEach(proto -> {
                if (proto.isImportPathMismatched()) {
                    protoContent.fixImport(proto.getImportPath(), proto.getExpectedImportPath());
                    allDepencencies.forEach(proto2 -> {
                        if (proto2 != proto) {
                            proto2.fixImport(proto.getImportPath(), proto.getExpectedImportPath());
                        }
                    });
                }
            });

            // Step 3: Write out all .proto files to their correct (package based) locations
            for (ProtoContent proto : allDepencencies) {
                proto.writeTo(rootDirectory);
            }
            Path path = protoContent.writeTo(rootDirectory);

            // Step 4: Load the .proto using JdkSchemaLoader
            try (JdkSchemaLoader schemaLoader = new JdkSchemaLoader()) {
                schemaLoader.initRoots(Collections.singletonList(rootDirectory),
                        Collections.singletonList(rootDirectory));

                Schema schema = schemaLoader.loadSchema();
                String protoFilePath = rootDirectory.relativize(path).toString();
                ProtoFile protoFile = schema.protoFile(protoFilePath);

                if (protoFile == null) {
                    throw new RuntimeException("Error loading Protobuf File: " + protoFileName);
                }

                return new ProtobufSchemaLoaderContext(schema, protoFile);
            }
        } finally {
            deleteRecursively(rootDirectory);
        }
    }

    private static void deleteRecursively(Path directory) {
        // Best effort cleanup: a failed delete must not mask a schema error.
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    // ignored
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // ignored
        }
    }

    protected static class ProtobufSchemaLoaderContext {
        private final Schema schema;
        private final ProtoFile protoFile;

        public Schema getSchema() {
            return schema;
        }

        public ProtoFile getProtoFile() {
            return protoFile;
        }

        public ProtobufSchemaLoaderContext(Schema schema, ProtoFile protoFile) {
            this.schema = schema;
            this.protoFile = protoFile;
        }
    }
}
