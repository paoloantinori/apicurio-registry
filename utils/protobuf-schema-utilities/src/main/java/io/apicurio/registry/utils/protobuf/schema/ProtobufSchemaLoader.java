package io.apicurio.registry.utils.protobuf.schema;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.squareup.wire.schema.JdkSchemaLoader;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    // The prebuilt protos are the same on every call, so they are read from the classpath once.
    // Suppliers.memoize does not cache an exception: a missing resource fails every call.
    private static final Supplier<Map<String, byte[]>> PREBUILT_PROTOS = Suppliers
            .memoize(ProtobufSchemaLoader::readPrebuiltProtos);

    private static Map<String, byte[]> readPrebuiltProtos() {
        Map<String, byte[]> protos = new LinkedHashMap<>();
        readPrebuiltProtos(protos, GOOGLE_API_PATH, GOOGLE_API_PROTOS);
        readPrebuiltProtos(protos, GOOGLE_WELLKNOWN_PATH, GOOGLE_WELLKNOWN_PROTOS);
        readPrebuiltProtos(protos, METADATA_PATH, Collections.singleton(METADATA_PROTO));
        readPrebuiltProtos(protos, DECIMAL_PATH, Collections.singleton(DECIMAL_PROTO));
        return Collections.unmodifiableMap(protos);
    }

    private static void readPrebuiltProtos(Map<String, byte[]> protos, String protoPath, Set<String> names) {
        final ClassLoader classLoader = ProtobufSchemaLoader.class.getClassLoader();
        for (String proto : names) {
            String resource = protoPath + proto;
            try (InputStream inputStream = classLoader.getResourceAsStream(resource)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Missing pre-built proto resource: " + resource);
                }
                protos.put(resource, ByteStreams.toByteArray(inputStream));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void stagePrebuiltProtos(Path rootDirectory) throws IOException {
        for (Map.Entry<String, byte[]> proto : PREBUILT_PROTOS.get().entrySet()) {
            Path target = rootDirectory.resolve(proto.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, proto.getValue());
        }
    }

    /**
     * Creates a schema loader staging the schema and its dependencies into an in-memory filesystem. This is
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

        // Always the unix configuration: Jimfs.newFileSystem() without arguments copies the host
        // platform, which is case-insensitive on macOS, so an import with the wrong case would load
        // on a macOS host and fail on Linux. Proto import paths are case-sensitive.
        try (FileSystem stagingFs = Jimfs.newFileSystem(Configuration.unix())) {
            Path rootDirectory = stagingFs.getPath("/");
            stagePrebuiltProtos(rootDirectory);

            // Step 1: Convert all .proto files to ProtoContent instances
            ProtoContent protoContent = new ProtoContent(protoFileName, schemaDefinition);
            List<ProtoContent> allDependencies = deps.entrySet().stream().map(entry ->
                    new ProtoContent(entry.getKey(), entry.getValue())).collect(Collectors.toList());

            // Step 2: Fix up the import statements in all .proto files so they point to canonical locations
            allDependencies.forEach(proto -> {
                if (proto.isImportPathMismatched()) {
                    protoContent.fixImport(proto.getImportPath(), proto.getExpectedImportPath());
                    allDependencies.forEach(proto2 -> {
                        if (proto2 != proto) {
                            proto2.fixImport(proto.getImportPath(), proto.getExpectedImportPath());
                        }
                    });
                }
            });

            // Step 3: Write out all .proto files to their correct (package based) locations
            for (ProtoContent proto : allDependencies) {
                proto.writeTo(rootDirectory);
            }
            protoContent.writeTo(rootDirectory);

            // Step 4: Load the .proto using JdkSchemaLoader
            try (JdkSchemaLoader schemaLoader = JdkSchemaLoader.forFileSystem(stagingFs)) {
                schemaLoader.initRoots(Collections.singletonList(rootDirectory),
                        Collections.singletonList(rootDirectory));

                Schema schema = schemaLoader.loadSchema();
                ProtoFile protoFile = schema.protoFile(protoContent.getExpectedImportPath());

                if (protoFile == null) {
                    throw new RuntimeException("Error loading Protobuf File: " + protoFileName);
                }

                return new ProtobufSchemaLoaderContext(schema, protoFile);
            }
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
