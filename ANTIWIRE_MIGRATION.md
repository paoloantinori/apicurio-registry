# ANTIWIRE_MIGRATION

Log of the branch that consumes antiwire (the pure-Java port of Square Wire 7.1.0, groupId
io.apicurio, version 0.1.0-SNAPSHOT) instead of com.squareup.wire:wire-schema 6.4.0 in the four
protobuf modules of Apicurio Registry. Goal: existing tests still pass, test logic unchanged
(only invocations, imports and exception plumbing are edited).

## Provenance

- antiwire built from commit 9d6423f59463cd6e953422e7816b203ff4c7d080 (Apicurio/antiwire main,
  2026-10-09), installed into ~/.m2 with JDK 21.0.10 Temurin, tests skipped, deploy skipped.
  Artifacts: wire-schema-java and wire-runtime-java 0.1.0-SNAPSHOT, both timestamped the night
  of the build. Rebuilt and reinstalled from 98e1460210eda18338a6bdf232ff2b0ce1f6aee7 on
  2026-10-10 for the rerun section; the sections before it describe the 9d6423f run.
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
- app and integration-tests receive the schema types transitively as well; no pom changes
  except one pattern applied at both places Confluent's protobuf serializer is declared test
  scoped (app/pom.xml and integration-tests/pom.xml): kafka-protobuf-serializer (Confluent
  8.0.0) pulls com.squareup.wire:wire-schema-jvm and wire-runtime-jvm 5.3.0 plus okio under
  the same package names antiwire ships, which put two ProtoParser implementations on one
  classpath and broke Confluent's ProtobufSchema at runtime with NoSuchFieldError on the
  Companion field. The Square Wire and okio transitives are excluded at both sites so
  antiwire is the single provider; the Confluent code path that still calls the Companion
  form is finding 5 below. integration-tests itself was never run here (no Docker), so its
  exclusion is compile verified only.
- utils/exportConfluent keeps Square Wire via its compile scoped kafka-schema-registry-client
  and is left untouched: it does not depend on the protobuf utilities module, so its Wire
  stack never shares a classpath with antiwire.

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
  through the OneOfFactory bridge, removed in 5c879c506 once antiwire 98e1460 made the
  constructor public (see the rerun section).
- ProtobufFile.java: the private instance ProtoParser constructor is not callable in
  antiwire, so toProtoFileElement uses the static ProtoParser.parse; the reserved IntRange
  branch handles the int[] pair.
- new file com/squareup/wire/schema/OneOfFactory.java in this module: package bridge to the
  package private OneOf constructor. Existed from the first run until 5c879c506, which
  deleted it; no longer part of the branch.
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
- The loader rewrite leans on JdkSchemaLoader being AutoCloseable, on
  initRoots(List<java.nio.file.Path>, List<java.nio.file.Path>) accepting the same directory
  as source and proto path, and on Schema.protoFile(String) keying on the location path
  relative to that root; compilation and the green suite prove all three together with the
  test that fetches the staged file by its expectedImportPath.

## Test evidence

Original run against antiwire 9d6423f (counts here predate the review round test and the
98e1460 rerun, see the rerun section for the current 142 and 129). JDK 21, single locked
Maven run per invocation:

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
- Baseline on the unmodified base commit with Wire 6.4.0 (worktree wt-antiwire-baseline at
  25c8b5ce0, same JDK, same locked command shape): identical results, test class by test
  class. FileDescriptorUtilsTest 37, ProtoContentTest 29, ProtobufCompatibilityCheckerTest
  11, ProtobufCompatibilityCheckerLibraryTest 32, ProtobufValidatorTest 4,
  ProtobufDeserializerFallbackTest 13, ProtobufSchemaParserTest 11, MessageIndexesUtilTest 4;
  141 total, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS. The comparison the goal asks
  for: same number of tests, same pass/fail state on both sides.
- App module protobuf coverage (ProtobufTagExtractorTest,
  ProtobufStructuredContentExtractorTest, ProtobufSerdeTest, ProtobufExportTest), run with
  the Confluent test exclusion below: ProtobufExportTest 8/8, ProtobufTagExtractorTest 7/7,
  ProtobufStructuredContentExtractorTest 7/7 green; ProtobufSerdeTest 12 run, 10 pass, 2
  errors, both testSerdeMix at line 256, both the single antiwire Companion gap (finding 5):
  java.lang.NoSuchFieldError on ProtoParser$Companion from Confluent's
  ProtobufSchema.toProtoFile. 32 of 34 green, 2 blocked on the planned antiwire Companion
  restoration. The first run, before the exclusion, failed the same 2 with the same error
  but from a classpath carrying both wire-schema 5.3.0 and antiwire.
- The four additional Confluent touching app suites from the maintainer review
  (JsonSchemaSerdeTest 24, VersionNumberingTest 1, CCompatAuthTest 5, ConfluentClientTest
  65): 95 tests, 94 green, 1 error, ConfluentClientTest.testSerdeProtobufSchema line 132,
  the identical Companion NoSuchFieldError. Combined app tally across the eight suites: 129
  tests, 3 errors, all three the same antiwire Companion gap, nothing else. The
  integration-tests module test-compiles cleanly with its exclusion (BUILD SUCCESS) but was
  never executed here, and after the exclusion the app dependency tree contains no
  com.squareup.wire and no com.squareup.okio artifact at all, only test scoped
  kotlin-stdlib brought directly by Confluent's own providers.
- Skipped suites: the integration-tests module is not run at all (needs Docker or Kafka;
  this Mac has only podman, and the goal scopes testcontainers work out). The same applies
  to the Docker bound app tests. utils/exportConfluent is untouched and untested here; it
  keeps its own Square Wire stack via Confluent (see dependency changes).

## Findings for antiwire

1. OneOf's constructor is package private in the port. Upstream Wire 6.4.0 declares it
   Kotlin internal, which is bytecode public, and Apicurio relies on that to synthesize the
   synthetic one-of of a proto3 optional field around an already linked Field
   (FileDescriptorUtils.getProto3OptionalField). OneOf.fromElements cannot serve: it rebuilds
   Fields from FieldElements and loses the linked types. Needed: a public static factory or
   public constructor with signature (String name, String documentation, List<Field> fields,
   Location location, Options options). Workaround on this branch: the OneOfFactory bridge
   class placed in package com.squareup.wire.schema inside the utils module. [Resolved on
   98e1460, bridge deleted in 5c879c506.]
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
5. The Kotlin Companion is not only a source level convenience: third party jars compiled
   against Square Wire call it at runtime. Confluent's
   io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema.toProtoFile (kafka-schema-
   registry-client 8.0.0, ProtobufSchema.java line 681) crashed with
   java.lang.NoSuchFieldError: ProtoParser does not have member field
   'ProtoParser$Companion Companion' the moment antiwire's classes were the only ones on the
   classpath. The planned Companion restoration is therefore load bearing for any consumer
   that keeps binary compiled Wire dependencies; the exclusion experiment above is the
   reproduction. Until it ships, ProtobufSerdeTest.testSerdeMix in the app module cannot pass
   against antiwire (2 errors of 34 tests, all other 32 pass).
6. Loading has no consumer legal in memory route. The old choreography was an in memory
   FakeFileSystem; the only replacement the facade offers a consumer is real files, so this
   branch stages into a per call temp directory (as prescribed). Two consequences worth an
   antiwire side decision: (a) every schema operation now pays real filesystem work for
   process constant content, roughly 90 metadata syscalls per call on a cold tmpdir, where
   the old path touched memory only; (b) host filesystem semantics leak into schema
   resolution, and a case insensitive default filesystem (macOS APFS) makes an import with
   mismatched case resolve locally and fail on case sensitive CI or production. A
   java.nio.file.FileSystem constructor does exist on the engine SchemaLoader, but the
   loading-api-inventory explicitly scopes consumers to JdkSchemaLoader and forbids
   importing SchemaLoader, so it is not a legal consumer route. Ask: either a JdkSchemaLoader
   factory over a caller supplied java.nio FileSystem (Jimfs restores the old semantics one
   for one), or a documented blessed in memory option.

## Review round (2026-10-10 night)

Four parallel review agents (reuse, simplification, efficiency, altitude) over the branch
diff, then the accepted fixes applied: the hand rolled recursive delete replaced by Guava
MoreFiles.deleteRecursively (guava is now a direct dependency of this module), the
resolve/relativize round trip in the loader replaced by the expectedImportPath the staged
file is written to (also removing a Windows separator dependency in the lookup key), the
unreachable parent null guard and never firing REPLACE_EXISTING dropped from staging, and
ProtoContent.writeTo became void now that nothing consumes its return. Skipped with reasons:
consolidating ProtobufFile's parse fallback into FileDescriptorUtils.parseWithBinaryFallback
(predates the branch and changes exception shapes, a parity risk), a shared helper for the
six int[] range sites (their endpoint arithmetic is intentionally different: descriptor
ranges are exclusive, the dynamic message API is inclusive), and moving the guava pin from
the module pom into root dependencyManagement (a root level managed pin would also pin
guava transitively in unrelated modules, a blast radius this spike must not have; the inline
pin matches the existing precedent in examples/tools/kafkasql-topic-import). The efficiency
review's shared prebuilt root (stage the 21 constant protos once per process and pass two
roots to initRoots) and the altitude review's Jimfs based in memory root are recorded as
follow ups rather than applied: the goal prescribes the JdkSchemaLoader plus temp directory
shape, and finding 6 above is the right place for the underlying ask.

## Maintainer review round (2026-10-10 night)

Two more review passes ran on the final diff: an adversarial maintainer review in the
carlesarnal, EricWittmann and jsenko personas, and a high effort correctness review. Fixed
in response: the same Square Wire and okio exclusion applied to integration-tests' copy of
the kafka-protobuf-serializer dependency (only app had it, which was indefensible), the
long standing allDepencencies typo in the loader renamed, a cleanup test pinning the loader's
temp directory lifecycle added to FileDescriptorUtilsTest, and the app Confluent touching
suites that had escaped the first reported set (JsonSchemaSerdeTest, VersionNumberingTest,
ConfluentClientTest, CCompatAuthTest) added to the recorded runs. Filed as REG-444 (the
post parity cleanups: OneOfFactory deletion, exclusion removal, loader staging revisit, the
fate of this log) and REG-445 (a pre-existing off by one in toReserved for multi tag enum
reserved ranges, identical on main, preserved verbatim here). Answered with reasoning
rather than changed: removing the kotlin-stdlib and okio properties from the root pom does
not orphan anything real, because those entries only ever mediated the wire stack's own
transitives and no module depends on okhttp directly; the guava pin stays inline in the
utilities module for the blast radius reason recorded in the simplify round; and the claim
that the dependency staging choreography has no unit coverage is incorrect, since
testParseProtoFileWithBinaryDependencies and
testParseProtoFileAndDependenciesOnDifferentPackagesAndKnownType both drive loadSchema with
populated dependency maps through the rewritten code.

## Rerun against antiwire 98e1460 (2026-10-10)

antiwire main moved to 98e1460 (TASK-33.2: Kotlin Companion form with the binary shape,
public OneOf constructor, public MessageType.toElement(), DEC-15 on range elements).
Rebuilt and installed from a clean fast forward of ~/data/repo/work/antiwire, then verified
with javap before running anything: ProtoParser has the public static Companion field and
Companion.parse(Location, String), OneOf has a public five argument constructor, and
MessageType.toElement() is public.

Results, same commands and same JDK 21 as the original run:

- The three tests that failed on the Companion all pass: ProtobufSerdeTest.testSerdeMix [1]
  and [2] (12 of 12 in the class), ConfluentClientTest.testSerdeProtobufSchema (65 of 65 in
  the class). Per-testcase entries read from the fresh surefire XML, zero error or failure
  elements in either file.
- The regression set is green: ProtobufSerdeTest, ConfluentClientTest, JsonSchemaSerdeTest,
  VersionNumberingTest, CCompatAuthTest, ProtobufTagExtractorTest,
  ProtobufStructuredContentExtractorTest and ProtobufExportTest total 129 tests, 0 failures,
  0 errors, 0 skipped, BUILD SUCCESS, versus 126 of 129 before. The Confluent exclusions in
  app/pom.xml and integration-tests/pom.xml were left in place for this run.
- No remaining exception in any suite run, so the three Companion failures were the whole
  story for those tests and kotlin.ranges.IntRange did not cause any of them. Evidence on
  why: the schema both Confluent tests serialize, TestCmmn.UUID, is proto3 with a single
  message and has zero reserved ranges, zero reserved names and zero extension ranges
  (checked by loading the generated descriptor and counting, not by reading the source), so
  neither test can reach a range branch.
- The IntRange hazard is nevertheless real and reproduced here, outside the test suite.
  Using the exact app test classpath of this branch (antiwire 98e1460 plus Confluent
  kafka-protobuf-provider 8.0.0, no Square Wire), a plain Java probe builds a
  io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema and calls canonicalString():
  proto3 with no ranges and proto2 with "reserved 4;" print correctly;
  "reserved 5 to 9;" throws IllegalArgumentException at ProtobufSchemaUtils.java:726 and
  "extensions 100 to 199;" throws IllegalArgumentException at ProtobufSchemaUtils.java:761.
  The cause is in Confluent's bytecode: the private ProtobufSchemaUtils.toString overloads
  for ReservedElement and ExtensionsElement test instanceof kotlin/ranges/IntRange and
  call getStart and getEndInclusive on it, while antiwire returns int[] {start,
  endInclusive} pairs (DEC-15 on the antiwire side, which measured the same thing). Only
  schemas that declare a range are affected, in proto2 or proto3, and only when Confluent
  code formats them. No suite in this repository exercises that path, so a green run says
  nothing about it; it is an open consumer limit, not a test failure here.
- The OneOfFactory bridge is gone (separate commit, 5c879c506): the proto3 optional
  synthetic one of is built with the public constructor directly. The four protobuf modules
  stay green with one more test than the original run, the review round cleanup test:
  utils 67 (FileDescriptorUtilsTest 38, ProtoContentTest 29), schema-util 43, validation 4,
  serde 28, 142 in total against the 141 baseline, plus checkstyle:check on the utils
  module, BUILD SUCCESS.

Finding status on 98e1460: finding 1 (public OneOf constructor) is resolved, finding 2
(MessageType.toElement()) is resolved, finding 3 (documenting the range value types) is
resolved by antiwire's javadoc on ReservedElement.getValues() and
ExtensionsElement.getValues(), finding 5 (Companion) is resolved, finding 4 needs nothing,
and finding 6 (no in memory loading route) stands as written. The original text of findings
1 to 6 above is kept as the record of the first run.

## Open problems

- Confluent's ProtobufSchema.canonicalString() throws IllegalArgumentException for any
  schema that declares a reserved or extension range ("reserved 5 to 9;", "extensions 100
  to 199;") when running against antiwire, because Confluent 8.0.0 tests
  instanceof kotlin.ranges.IntRange and antiwire returns int[] pairs (DEC-15). Reproduced
  with a plain Java probe on this branch's test classpath, see the rerun section. No suite
  here covers it, so this is invisible to the green runs. Impact is on any consumer that
  formats range bearing schemas through Confluent code, for example the ccompat endpoints
  when a client registers such a schema through Confluent's own client library; Apicurio's
  own code is adapted and unaffected. The decision belongs to antiwire (DEC-15 is marked
  open to reconsideration); a regression test on the Apicurio side should wait for that
  decision so it asserts the final behavior.
- This branch does not build from a clean checkout: io.apicurio:wire-schema-java
  0.1.0-SNAPSHOT exists only in the local ~/.m2, installed from antiwire (9d6423f for the
  original run, 98e1460 for the rerun above; nothing is published anywhere, by
  design). Building this branch requires cloning antiwire and running its install first;
  this is inherent to the spike, not a defect of it.
- The three tests that were red on 9d6423f (ProtobufSerdeTest.testSerdeMix, 2
  parameterizations, and ConfluentClientTest.testSerdeProtobufSchema) pass on 98e1460; see
  the rerun section. No known red test remains in the suites run.
- The app module's Confluent touching suites beyond the four first reported
  (JsonSchemaSerdeTest, VersionNumberingTest, ConfluentClientTest, CCompatAuthTest) are now
  included in the recorded runs; testcontainers bound suites remain out of scope on this
  Mac (no Docker, only podman) and the integration-tests module was compile verified but
  never executed.
- The staging directory of the new loader inherits host filesystem semantics: on a case
  insensitive host (macOS APFS) a dependency whose name differs only in case from a staged
  prebuilt proto collides, and today's only traversal defense for dependency names is
  extractFilename keeping the last path segment; the in-memory FakeFileSystem had neither
  property. Both are recorded in finding 6 for the antiwire side decision.

## Messages to the antiwire session

- Build green, 2026-10-09 night: the four protobuf modules compile against
  io.apicurio:wire-schema-java 0.1.0-SNAPSHOT from 9d6423f; utils tests 66/66 green on the
  first run after the migration. Branch antiwire-integration on paoloantinori/apicurio-
  registry. One blocking-grade finding so far (the OneOf constructor, bridged locally).
- Final handoff, delivered through herdr 2026-10-10 01:05 CEST: goal met and pushed, branch
  antiwire-integration on paoloantinori/apicurio-registry at 00ce12ada (base 25c8b5ce0),
  log in this file, antiwire built from 9d6423f. All four protobuf modules compile against
  io.apicurio:wire-schema-java and their tests match the Wire baseline exactly, 141 tests
  green with per-class counts identical. App module: 129 tests run, 3 errors, all one
  cause, Confluent 8.0.0 calling the Kotlin Companion (ProtoParser$Companion) at runtime
  through ProtobufSchema.toProtoFile line 681, so the planned Companion restoration is load
  bearing for binary consumers. Findings: the OneOf constructor gap bridged locally via
  OneOfFactory, the ReservedElement.getValues documentation gap, the Companion issue, and
  the absence of a consumer legal in-memory loading route (temp directory staging cost and
  case-sensitivity leak). integration-tests and exportConfluent decisions documented. No PR
  opened, nothing pushed to origin.
