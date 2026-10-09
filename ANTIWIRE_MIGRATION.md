# ANTIWIRE_MIGRATION

Log of the branch that consumes antiwire (the pure-Java port of Square Wire 7.1.0, groupId
io.apicurio, version 0.1.0-SNAPSHOT) instead of com.squareup.wire:wire-schema 6.4.0 in the four
protobuf modules of Apicurio Registry. Goal: existing tests still pass, test logic unchanged
(only invocations, imports and exception plumbing are edited).

## Provenance

- antiwire built from commit 9d6423f59463cd6e953422e7816b203ff4c7d080 (Apicurio/antiwire main,
  2026-10-09), installed into ~/.m2 with JDK 21.0.10 Temurin, tests skipped, deploy skipped.
  Artifacts: wire-schema-java and wire-runtime-java 0.1.0-SNAPSHOT, both timestamped the night
  of the build.
- Apicurio base commit: 25c8b5ce0 (origin/main of Apicurio/apicurio-registry, 2026-10-09).
- Branch: antiwire-integration, cut from origin/main, pushed to the fork
  paoloantinori/apicurio-registry, never to origin. No PR opened.

## Dependency changes

- Root pom: property wire-schema.version (6.4.0) replaced by antiwire.version
  (0.1.0-SNAPSHOT); dependencyManagement entries for com.squareup.wire:wire-schema and
  wire-schema-jvm replaced by io.apicurio:wire-schema-java. The okio, okio-jvm and
  okio-fakefilesystem managed entries and their okio.version property are removed: only the
  protobuf utils module used them, only for Wire choreography. The kotlin-stdlib managed entry
  and its property are removed for the same reason. The unused com.squareup.okio:okhttp
  managed entry is kept to keep this diff scoped to Wire leftovers.
- utils/protobuf-schema-utilities/pom.xml: wire-schema, wire-schema-jvm, okio-jvm and
  okio-fakefilesystem replaced by io.apicurio:wire-schema-java. Guava 32.1.3-jre is now
  declared explicitly: the module's code uses it (ImmutableList, CaseFormat, Range and
  friends) and it previously arrived transitively through wire-schema-jvm, which antiwire
  does not bring.
- serdes/generic/serde-common-protobuf/pom.xml: wire-schema replaced by
  io.apicurio:wire-schema-java.
- schema-util/protobuf and schema-validation/protobuf name no Wire dependency in their poms;
  they received it transitively and keep compiling without pom changes.
- app and integration-tests receive the schema types transitively as well; no pom changes.

## Code changes, file by file

utils/protobuf-schema-utilities:
- ProtobufSchemaLoader.java: rewritten. The FakeFileSystem/setWorkingDirectory/
  setAllowSymlinks choreography does not exist in antiwire; the loader now stages the
  prebuilt google/type, google/protobuf, metadata and additionalTypes protos plus the caller
  provided schema and dependencies into a fresh temp directory and points a JdkSchemaLoader at
  it, with the same directory as source path and proto path, mirroring the old single "/"
  root. The temp directory is deleted best effort in a finally block. The loaded ProtoFile is
  looked up by the staged path relativized against the root, matching
  Schema.protoFile(String), which keys on the location path.
- ProtoContent.java: writeTo(okio.FileSystem) became writeTo(java.nio.file.Path) staging
  under the temp root; same expectedImportPath placement. Only ProtobufSchemaLoader calls it.
- FileDescriptorUtils.java: ProtoParser.Companion.parse replaced by the static
  ProtoParser.parse at three sites; the Kotlin IntRange branches for reserved and extension
  ranges now handle the int[] {start, endInclusive} pairs that antiwire's parser emits, with
  identical endpoint arithmetic (descriptor end stays exclusive at endInclusive + 1); the
  message reserved and extension walks read the linked models messageElem.reserveds() and
  messageElem.getExtensionsList() instead of the package private toElement() round trip (their
  getValues() use the same carrier convention); the synthetic proto3 optional OneOf is built
  through the OneOfFactory bridge (see findings).
- ProtobufFile.java: the private instance ProtoParser constructor is not callable in
  antiwire, so toProtoFileElement uses the static ProtoParser.parse; the reserved IntRange
  branch handles the int[] pair.
- new file com/squareup/wire/schema/OneOfFactory.java in this module: package bridge to the
  package private OneOf constructor (see findings). Deleted as soon as antiwire exposes a
  public factory.
- FileDescriptorUtilsTest.java: seven ProtoParser.Companion.parse invocations switched to
  ProtoParser.parse. No assertion or test logic changed.

schema-util/protobuf:
- ProtobufContentCanonicalizer.java: Companion.parse to static parse.
- ProtobufFqnConflictDetector.java: instance parser to static parse; the adjacent javadoc
  reference updated to match.
- ProtobufCompatibilityChecker.java: six decorative org.jetbrains.annotations.NotNull
  markers and the import removed; they arrived transitively through kotlin-stdlib, which is
  gone, and nothing enforces them.
- ProtobufCompatibilityCheckerLibraryTest.java: two Companion.parse invocations switched to
  the static form. No assertion changed.

schema-validation/protobuf:
- ProtobufSchemaParser.java: Companion.parse to static parse.

serdes/generic/serde-common-protobuf:
- ProtobufSchemaParser.java (registry serde one): Companion.parse to static parse.

app:
- AbstractResource.java and ProtobufTagExtractor.java: Companion.parse to static parse.

integration-tests:
- No source change needed; the two files that import wire parser types compile unchanged
  against antiwire because the package and class names are identical.

## Verified API surface notes (javap against the installed jar)

- ProtoParser exposes only the static parse(Location, String); the instance constructor is
  private and there is no Companion.
- Reserved and extension values are Integer tags, String names, or int[] {start, end}
  inclusive pairs, documented on Reserved.getValues(); "to max" resolves to
  SchemaUtil.MAX_TAG_VALUE inside the parser, matching Kotlin IntRange semantics one to one.
- Service keeps name()/rpcs(); Rpc, Field, MessageType, EnumType, ProtoFile, Options,
  ProtoType and Location expose the getX()/isX() forms this code already used.
- All parser element constructors (ProtoFileElement, MessageElement, FieldElement,
  EnumElement, EnumConstantElement, ServiceElement, RpcElement, OneOfElement, OptionElement,
  ReservedElement, ExtensionsElement) match the arity and order of the existing call sites.

## Test evidence

After migration, JDK 21, single locked Maven run per invocation:

- utils/protobuf-schema-utilities: Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
  (FileDescriptorUtilsTest 37, ProtoContentTest 29), BUILD SUCCESS. This includes every
  descriptor round trip through the rewritten temp directory loader and the proto3 optional
  synthetic oneof path through the bridge.
- schema-util/protobuf: Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
  (ProtobufCompatibilityCheckerTest 11, ProtobufCompatibilityCheckerLibraryTest 32).
- schema-validation/protobuf: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
  (ProtobufValidatorTest).
- serdes/generic/serde-common-protobuf: Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
  (ProtobufDeserializerFallbackTest 13, ProtobufSchemaParserTest 11, MessageIndexesUtilTest
  4).
- Reactor total across the four modules: 141 tests, 0 failures, 0 errors, 0 skipped, BUILD
  SUCCESS, single locked run from a worktree whose inter module dependencies resolved inside
  the reactor (so the utils classes under test are the migrated ones, not a stale local
  artifact).
- Baseline on the unmodified base commit with Wire 6.4.0: recorded below when complete.

## Findings for antiwire

1. OneOf's constructor is package private in the port. Upstream Wire 6.4.0 declares it
   Kotlin internal, which is bytecode public, and Apicurio relies on that to synthesize the
   synthetic one-of of a proto3 optional field around an already linked Field
   (FileDescriptorUtils.getProto3OptionalField). OneOf.fromElements cannot serve: it rebuilds
   Fields from FieldElements and loses the linked types. Needed: a public static factory or
   public constructor with signature (String name, String documentation, List<Field> fields,
   Location location, Options options). Workaround on this branch: the OneOfFactory bridge
   class placed in package com.squareup.wire.schema inside the utils module.
2. MessageType.toElement() is package private while the upstream 6.4.0 surface exposed it.
   Not blocking: the public reserveds() and getExtensionsList() linked models carry the same
   values. Exposing toElement() would still shorten ports of code shaped like
   FileDescriptorUtils.
3. ReservedElement.getValues() and ExtensionsElement.getValues() do not document their value
   types the way Reserved.getValues() does ("Integers are tags; int[] pairs are inclusive
   ranges; strings are names"). A one line javadoc on the parser elements would prevent every
   future consumer from rediscovering this by javap.
4. Companion.parse: known and coming; consumers use the static form meanwhile. No action
   needed beyond what is already planned on the antiwire side.

## Open problems

- None known so far. The app module's protobuf touching tests and the Docker bound suites
  are still to be triaged (no Docker on this Mac, only podman; testcontainers suites are out
  of scope and will be listed here).

## Messages to the antiwire session

- Build green, 2026-10-09 night: the four protobuf modules compile against
  io.apicurio:wire-schema-java 0.1.0-SNAPSHOT from 9d6423f; utils tests 66/66 green on the
  first run after the migration. Branch antiwire-integration on paoloantinori/apicurio-
  registry. One blocking-grade finding so far (the OneOf constructor, bridged locally).
