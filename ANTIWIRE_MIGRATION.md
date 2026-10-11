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

## Rerun against antiwire 62c8646 (2026-10-10, TASK-33.3 unchecked IOException)

antiwire main moved to 62c8646, which removes checked IOException from public signatures
where real Wire 7.1.0 declares no throws (they throw java.io.UncheckedIOException with the
original as cause; methods upstream declares with @Throws keep it). Rebuilt and installed
from a clean fast forward of ~/data/repo/work/antiwire.

The predicted breakage did not occur: zero compile errors of the form "exception
java.io.IOException is never thrown in body of corresponding try statement", zero source
changes on this branch, zero test files touched. test-compile of the four protobuf modules,
app and integration-tests (the latter with the dependency unpack step skipped, because that
plugin execution needs a packaged reactor artifact and is unrelated to this change) all
succeed.

Why nothing needed to change, per handler, checked against the new installed jar with javap
and against the antiwire sources rather than assumed. Every IOException in this branch's
migrated code guards a call that still declares it:

- ProtobufSchemaLoader.java, loadSchema, the try with resources around JdkSchemaLoader:
  JdkSchemaLoader.initRoots, loadSchema and close, and the static forClasspath, all still
  declare throws IOException in the new jar. JdkSchemaLoader is antiwire's own facade, not a
  member of the upstream Wire API, so TASK-33.3 left its signatures alone.
- ProtobufSchemaLoader.java, the best effort cleanup catch: guards Guava
  MoreFiles.deleteRecursively, a Guava method.
- ProtoContent.writeTo and ProtobufSchemaLoader.stagePrebuiltProtos: java.nio Files calls
  and a classloader stream copy, nothing from antiwire.
- FileDescriptorUtils.java, the two readAllBytes catches (around the main file and around
  each dependency): java.nio Files.readAllBytes, nothing from antiwire.
- ProtobufDereferencer.java: guards FileDescriptorProto.writeTo(OutputStream), a protobuf
  java method.
- ProtobufFile.java and the test helpers that mention IOException: Guava Files.readLines and
  protobuf parseFrom, all outside antiwire.

The calls this branch makes into antiwire that were affected by 62c8646 are the parser
entry point and the schema model getters, and none of them ever declared IOException
(ProtoParser.parse and Companion.parse, the element constructors, Schema.protoFile, the
getX methods), so no handler of ours could be invalidated by it.

Test results on 62c8646, same commands and JDK 21 as before:

- four protobuf modules: utils 67, schema-util 43, validation 4, serde 28, 142 in total,
  0 failures, 0 errors, 0 skipped, BUILD SUCCESS;
- app regression set (the same eight suites): 129 tests, 0 failures, 0 errors, 0 skipped,
  BUILD SUCCESS;
- checkstyle:check on the four modules, BUILD SUCCESS.

The Confluent range gap from the previous section was re-probed on 62c8646 with the same
probe and this branch's app test classpath: unchanged, proto3 and "reserved 4;" print,
"reserved 5 to 9;" and "extensions 100 to 199;" still throw IllegalArgumentException in
ProtobufSchemaUtils.toString.

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
- Rerun report for antiwire 98e1460, 2026-10-10. The herdr delivery to antiwire-83 failed
  (bird.lan unreachable over SSH, 100 percent packet loss), so this is the same text as the
  message: PASS ProtobufSerdeTest.testSerdeMix [1] and [2] and
  ConfluentClientTest.testSerdeProtobufSchema; regression set 129 of 129 (126 of 129
  before); four protobuf modules 142 green; no remaining exception in any suite. The
  IntRange hypothesis is not the cause of any failure because both tests use TestCmmn.UUID,
  proto3 with one message and no reserved or extension ranges (counted from the
  descriptor). The hazard is real and reproduced with a plain probe on this branch's
  classpath: Confluent ProtobufSchema.canonicalString() throws IllegalArgumentException at
  ProtobufSchemaUtils.java:726 for "reserved 5 to 9;" and at :761 for "extensions 100 to
  199;"; "reserved 4;" and proto3 print fine. No suite covers ranges (REG-447 on the
  Apicurio side). OneOfFactory removed in commit 5c879c506. Branch tip at the time of
  writing is the commit that contains this paragraph on the fork, never origin.

## Integration tests under podman (TASK-37)

Request from the antiwire session: run the Apicurio integration tests that touch protobuf
schemas on this branch with podman as the container runtime, then the same classes on the
base commit with the original Wire. Result: they run under podman, and branch and base give
identical counts. No source or test file was changed and nothing was pushed to origin.

Environment, 2026-10-11:

- Host: macOS 26.7.1 arm64, JDK 21.0.10 Temurin, Maven wrapper 3.9.8, antiwire built from
  62c8646 and installed in ~/.m2.
- podman client 6.1.3, podman engine 6.0.0 (Docker compatible API 1.44, minimum 1.24),
  machine podman-machine-default, applehv, 4 CPUs, 8 GiB, rootless. A second machine
  ("crack", libkrun) was also running and was not used.
- Testcontainers 2.0.5 and strimzi-test-container 0.117.0, which starts the Kafka container
  quay.io/strimzi-test-container/test-container:0.117.0-kafka-4.3.1.
- Branch worktree wt-antiwire-it at 2e5158fed (antiwire 62c8646 on the classpath); base
  worktree wt-antiwire-baseline at 25c8b5ce0 (Square Wire 6.4.0, okio 3.18.2).

Setup. The podman machine already exposes a Docker compatible API socket on the host, so
`podman system service` did not have to be started by hand. The path that
`podman info` prints (/run/user/501/podman/podman.sock) is inside the VM; the host side
path comes from `podman machine inspect podman-machine-default --format
'{{.ConnectionInfo.PodmanSocket.Path}}'` and answers /_ping with OK. The environment used
for every run (kept in /tmp/podman-env.sh):

    export DOCKER_HOST="unix://$(podman machine inspect podman-machine-default \
        --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
    export TESTCONTAINERS_RYUK_DISABLED=true
    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/501/podman/podman.sock
    export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem

TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE was set as a precaution; whether it is required was
not tested separately. An existing ~/.testcontainers.properties already selected the unix
socket client strategy. Ryuk is disabled, which Testcontainers logs as a warning on every
start; the containers were stopped by the framework and podman showed none left over.

Selecting the classes. `grep -rli protobuf integration-tests/src/test` lists generated
message classes plus three test classes that exercise the migrated code at runtime:
ProtobufSerdeIT (17 tests, group serdes, starts a Kafka container), SerdesPerformanceIT (6
tests, Apicurio and Confluent protobuf serializers) and AllArtifactTypesIT (11 tests, group
smoke, registers and compares a protobuf artifact). JsonSchemaSerdeIT only mentions protobuf
in a commented line and was not run.

Commands, identical on both worktrees except for the working directory:

    . /tmp/podman-env.sh
    ./mvnw -B -Pintegration-tests verify -pl integration-tests -am \
        -Dit.test='ProtobufSerdeIT,AllArtifactTypesIT,SerdesPerformanceIT' \
        -Dtest=NoSuchUnitTest -DfailIfNoTests=false \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dgroups='smoke | serdes' -Dmdep.skip=true

Notes on the flags. `-am` is required: without it the module cannot resolve its sibling
SNAPSHOT dependencies and fails in dependency resolution before any test starts, which is
what the first attempt of this task hit. `-Dtest=NoSuchUnitTest` stops the unit tests of
the upstream modules from running under the same `-Dit.test` filter. `-Dmdep.skip=true`
skips the `unpack-static-resources` plugin execution, which otherwise fails with
"Artifact has not been packaged yet" (MDEP-98) in a reactor that has not run `package`
first. The branch was packaged once beforehand with `-Pintegration-tests -DskipTests
-DskipITs package -pl integration-tests -am`.

Counts, same JDK, same podman, run one after another under the Maven lock:

| Class | Branch (antiwire 62c8646) | Base (Wire 6.4.0) |
|---|---|---|
| ProtobufSerdeIT | 17 run, 0 failures, 0 errors, 0 skipped, 121.5 s | 17 run, 0 failures, 0 errors, 0 skipped, 69.7 s |
| SerdesPerformanceIT | 6 run, 0 failures, 0 errors, 0 skipped, 23.9 s | 6 run, 0 failures, 0 errors, 0 skipped, 26.5 s |
| AllArtifactTypesIT | 11 run, 0 failures, 0 errors, 0 skipped, 36.0 s | 11 run, 0 failures, 0 errors, 0 skipped, 34.7 s |
| Total | 34 run, 0 failures | 34 run, 0 failures |

The branch ran ProtobufSerdeIT on its own first (17 of 17), then the other two classes
together (17 of 17); the base ran all three in one invocation (34 of 34). The 121.5 s
against 69.7 s for ProtobufSerdeIT is one sample each on a shared machine, includes the
first pull of the Kafka image on the branch run, and is not evidence of a performance
difference.

Evidence that the runs used podman and the intended Wire stack, read from the artifacts and
not assumed:

- The podman event log for the run shows the Kafka container created and started from
  quay.io/strimzi-test-container/test-container:0.117.0-kafka-4.3.1, and the KafkaFacade
  log line "Starting kafka container (attempt 1/2)" at 10:14:46.
- The packaged registry used by the integration tests carries
  io.apicurio.wire-schema-java-0.1.0-SNAPSHOT and wire-runtime-java-0.1.0-SNAPSHOT and no
  com.squareup.wire jar on the branch; on the base it carries wire-schema 6.4.0, wire-runtime
  6.4.0 and okio 3.18.2.
- On the branch the integration tests' own classpath contains okio 2.7.0 (from the Confluent
  test dependencies) next to antiwire and no Square Wire, which is the result of the
  exclusions in integration-tests/pom.xml. These were previously compile verified only; they
  are now exercised at runtime by ProtobufSerdeIT and SerdesPerformanceIT, both of which use
  the Confluent protobuf serializer, with no NoSuchFieldError and no duplicate class
  problem.

What this does not cover. Only the three classes above were run, not the whole
integration-tests module, and not the groups that need Keycloak, Elasticsearch,
Debezium, a second registry or a Kubernetes cluster. The default storage variant of the
local jar run was used; the other storage variants were not exercised. The schemas used by
these tests have no reserved or extension ranges, so the Confluent range gap recorded under
Open problems (REG-447) is not touched by them. No test failed, so there are no stack
frames to record.
