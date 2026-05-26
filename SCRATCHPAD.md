# CONCLAVE — Scratchpad

> **Purpose.** Working notes that don't belong in [spec.md](spec.md) (which is the contract) or [PROGRESS.md](PROGRESS.md) (which is the structured tracker). This is where you jot half-formed ideas, gotchas-in-flight, debugging breadcrumbs, "things to remember when M2 happens," etc. **Date every entry** so future agents can prune stale notes.

---

## 📝 Open Notes

### 2026-05-25 — M1 scaffolding decisions worth remembering

- **Avro schema location.** Spec §10 puts schemas in `configs/{domain}/schema.avsc`. The default `avro-maven-plugin` looks at `src/main/resources/avro/`. Worked around by setting `<sourceDirectory>${project.basedir}/../configs</sourceDirectory>` and `<includes>**/schema.avsc</includes>`. If we add a second schema per domain later, glob still picks it up.
- **Namespace per domain.** Used `io.conclave.events.fraud` and `io.conclave.events.security` so the generated Java classes don't collide. `EventTopics` constants follow the same naming: `events.fraud.raw`, `events.security.raw`.
- **Why field-level `doc` on every Avro field?** The spec requires "every README claim backed by a benchmark or demo step." For Avro that translates to: schema is the source of truth, so it must self-document. Also Avro's `doc` survives into Java javadoc via the plugin.
- **Testcontainers Kafka image pin.** `confluentinc/cp-kafka:7.6.0` because it's multi-arch (Apple Silicon-safe). If we ever upgrade, verify the new tag still publishes `linux/arm64`.
- **`spring-kafka-test` vs Testcontainers.** Picked Testcontainers because it actually runs the real broker — embedded Kafka has bitten us before with subtle serdes differences. Worth the extra startup time.
- **Profile-startup test pattern.** `ProfileStartupIT` uses two nested `@Nested` classes each with `@ActiveProfiles("fraud")` / `@ActiveProfiles("security")`. Each asserts the `EventTopics` bean was wired with the right domain. Cheap, catches "I forgot to add a config-server property" regressions.

### Things to consider when starting M2

- The `FeatureSpec` interface (mentioned in spec §6 M2) should expose:
  - `String inputTopic()` and `String outputTopic()`
  - `Function<RawEvent, EnrichedEvent> transform()` — or a Kafka Streams `Topology` builder
  - `Set<String> requiredStateStores()` — for velocity / time-windowed features
- One topology shell, two `FeatureSpec` impls — `FraudFeatureSpec`, `SecurityFeatureSpec`.
- Decide: are baseline-embedding lookups (M3) done *inside* the stream job (synchronous gRPC) or in the LangGraph orchestrator (M5)? Spec §4 diagram puts them at agent time — keep them out of the stream job for now to avoid coupling.

### Things to NOT forget

- **Don't bump Spring Boot 4.0 → 4.1-M*** even though 4.1 is shipping in May 2026. Stay on 4.0.6 for reproducibility.
- **Don't add a `dev` profile.** The two profiles are `fraud` and `security` — that's the architectural statement.
- **Don't add Lombok.** Spring Boot 4 + Java 25 records cover 90% of what Lombok did, and `--enable-preview` JEP support is improving. Keep deps lean.

---

## 🐛 Active Bugs / Gotchas

### 2026-05-26 — M9 (Synthetic Data Generators) gotchas

1. **No Spring Boot for a CLI tool.** Spring autoconfig is built for
   long-lived services; for a process whose main work is "publish N events
   and exit," the 6-8s cold start dominates. The generators module uses
   plain `KafkaProducer` directly with hand-built `Properties`. Cold start
   drops to ~1s.
2. **Avro plugin works module-by-module.** Each module that wants the
   `io.conclave.events.*` classes adds the `avro-maven-plugin` to its
   `<plugins>` (no config needed; inherited from parent `<pluginManagement>`).
   Don't try to share generated classes via a JAR dep — duplication is
   cheaper than the coupling.
3. **Labels on a SIDE topic, not on the raw event.** Anything embedded in
   the raw event has to be scrubbed by M2 before emitting enriched, which
   is one more place leakage can sneak in. `events.{domain}.labels` keyed
   by `eventId` is one KTable join away when the eval pipeline lands.
4. **Mirror, don't import.** The generators recreate
   `io.conclave.ingest.EventDomain` as `GeneratorDomain` and re-state the
   producer settings (`acks=all`, idempotent) rather than depending on
   the orchestrator JAR. Cross-referenced via comments in both places +
   ADR-007. A future change to producer settings touches BOTH sites.
5. **Mocked `Producer<K, V>` test pattern.** `@SuppressWarnings("unchecked")`
   on the `mock(Producer.class)` line — Mockito's `mock(Class)` can't
   carry generic type info. Acceptable in tests.
6. **`KafkaContainer` first-publish warnings are benign.** Look for
   `LEADER_NOT_AVAILABLE` on the first send to a new topic — the topic is
   being auto-created. Use `await().atMost(60s)` on consume loops so the
   container has time to settle.
7. **`exec-maven-plugin` is the painless way to wire `make m9-run-*`.**
   Adding the plugin (version 3.5.0) lets `mvn exec:java
   -Dexec.mainClass=...` run a CLI without packaging a fat JAR. Pair
   with a `make ARGS=` pattern so users can pass `--clean 5000 --rings 3`.
8. **Hand-rolled CLI parser pattern.** `while (i < args.length)` with
   `flag = args[i]; value = args[i+1]; i += 2;`. The switch case never
   has to fiddle with `i`. Six unit tests cover defaults, all-flags, help,
   unknown flag, missing value, negative count.

### 2026-05-26 — M7 (Audit & Decision API) gotchas

1. **`@TestInstance(Lifecycle.PER_CLASS)` breaks Testcontainers/`@DynamicPropertySource`
   ordering.** With PER_CLASS, JUnit makes `@BeforeAll` non-static, which shifts the
   extension lifecycle so `@DynamicPropertySource` evaluates *before* the
   `@Container` static field is started. Result:
   `IllegalStateException: Mapped port can only be obtained after the container is started`
   from `PostgreSQLContainer.getJdbcUrl()`. Fix: keep `@BeforeAll`/`@AfterAll` static
   (default `Lifecycle.PER_METHOD`) — same pattern the M6 IT uses.
2. **Spring Boot's Jackson does NOT snake_case by default.** Java records
   serialize their field names verbatim, so `eventId` becomes `"eventId"` in
   the JSON response. The M6 `DecisionPublisher` hand-builds snake_case
   keys for the Kafka payload, but the M7 controller's record DTOs would
   serialize as camelCase if you don't opt in globally. Set
   `spring.jackson.property-naming-strategy=SNAKE_CASE` in `application.yaml`
   to align both wire surfaces.
3. **Hand-rolled WHERE assembly is fine at this scope.** Eight optional
   predicates, one `appendWhere` method, list-based positional binding. The
   abstraction cost of jOOQ or Specifications would outweigh the maintenance
   savings until predicate count grows past ~12.
4. **`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`** is the idempotent
   roll-forward path for the demo (Postgres 9.6+). Avoid Flyway/Liquibase
   until the migration set actually needs ordering — one column add per
   module is cheap to apply on every startup.
5. **`spring-boot-starter-web`'s `RestClient` is the modern replacement
   for `TestRestTemplate`.** Used here in `AuditApiIT` with
   `RestClient.builder().baseUrl("http://localhost:" + port)`. Throws
   typed `HttpClientErrorException.NotFound` / `BadRequest` on 4xx by
   default — assert on those instead of polling for status codes.
6. **`@RequestParam` Optional<X> in Spring Boot 4** still works for the
   typed primitive wrappers (`Double minScore`) but the controller-side
   plumbing is cleaner if you accept the unboxed nullable type and let
   the builder wrap it. That's what `AuditController` does.
7. **springdoc-openapi 2.x targets Spring Boot 3.** No clean SB4 release
   yet; the 3.x line is milestones-only. M7 ships without auto-generated
   OpenAPI for now — the dashboard team works from ADR-006 + controller
   javadoc.
8. **Replay path piggybacks on the M6 `DeliberationClient` bean.** When
   M5 is down, replay 503s the same way live deliberation does. That's
   honest behavior but worth a "M5 unreachable" banner in the dashboard.

### 2026-05-26 — M6 (Decision Orchestrator) gotchas

1. **Avro's built-in `JsonEncoder` wraps union types.** A union of
   `[null, string]` with value `"DE"` serializes as `{"string": "DE"}`,
   not `"DE"`. Useless for a cross-language JSON contract (M5's Python
   side would have to special-case every nullable field). Write a
   schema-walking encoder that strips the wrappers — see
   `EnrichedEventJsonEncoder`. Also: Avro's `Instant`-typed
   `timestamp-millis` field maps cleanly to epoch millis (`toEpochMilli()`).
2. **`@KafkaListener` SpEL doesn't see @ConfigurationProperties records
   by short name.** `#{ingestProperties.domain().enrichedTopic()}` fails
   because the bean's actual name is `<prefix>-<FQCN>` (e.g.
   `conclave.ingest-io.conclave.ingest.IngestProperties`). Workaround:
   declare derived String `@Bean`s named with single tokens and reference
   them with `#{@enrichedInputTopic}` / `#{@orchestratorConsumerGroup}`.
3. **`@SpringBootTest` loads ALL @Components in the module.** Adding new
   Postgres-needing beans to M6 broke the older M1/M2 ITs that don't spin
   up Postgres. Two fixes that work:
   - Gate the new slice behind `@ConditionalOnProperty(name="...",
     matchIfMissing=true)` and set the property `=false` in the older ITs'
     `@SpringBootTest(properties = ...)`. We took this path because it's
     the smallest blast radius.
   - Stack a `no-decisions` profile and use `@ActiveProfiles({"fraud",
     "no-decisions"})`. Cleaner-feeling but tangles with the existing
     `fraud`/`security` profile vocabulary.
4. **Mockito strict stubbing breaks JdbcTemplate varargs.** The pattern
   `when(jdbc.update(anyString(), (Object[]) any())).thenReturn(1)`
   doesn't pattern-match the variadic call. If you don't actually need the
   return value (and tests usually don't — they assert on what got SENT),
   just drop the stubbing. Mockito returns `0` for the unstubbed `int`
   method, which is fine.
5. **Spring Boot 4 + Postgres JSONB: cast on insert.** Same lesson M3 hit
   with pgvector. The JDBC driver sends `String` parameters as `text`, but
   the column type is `jsonb`. Use `?::jsonb` in the prepared SQL so
   Postgres casts on insert.
6. **HikariCP doesn't validate the connection at startup.** A misconfigured
   `spring.datasource.url` won't fail until the first `JdbcTemplate.execute`.
   So `SchemaInitializer.@PostConstruct` is the actual point where Postgres
   absence surfaces — gate THAT first if you only want one conditional.
7. **`@ConfigurationProperties` records vs Validation API.** Spring Boot 4
   doesn't bundle `jakarta.validation.constraints.*` unless you pull in
   `spring-boot-starter-validation`. We follow `IngestProperties` and
   `BaselineProperties` — validate in the compact constructor. Fewer deps,
   uniform pattern, fail-fast.
8. **`KafkaTemplate<String, SpecificRecord>` + `KafkaTemplate<String, String>`
   coexist.** Two `@Bean`-declared `ProducerFactory<K, V>` pairs with
   different type parameters; Spring's generic-aware DI distinguishes them.
   No `@Primary` needed.
9. **In-process gRPC for unit tests (`InProcessChannelBuilder`/`InProcessServerBuilder`).**
   No port binding required — the channel and server share a name string
   and route directly. Fast, deterministic, and exercises the real gRPC
   stack so generated stubs are also under test. Used in
   `DeliberationClientTest`.
10. **Real Netty gRPC for ITs (`NettyServerBuilder.forAddress(...)`).**
    For full @SpringBootTest ITs where the production client (the
    `ManagedChannelBuilder.forTarget` channel) needs to actually connect
    over TCP. Bind to a free loopback port picked at runtime via
    `ServerSocket(0)`. Used in `DecisionOrchestratorIT`.
11. **Spring Boot's `KafkaListenerEndpointRegistry` is created lazily.**
    Even with `@ConditionalOnProperty` flipping off the listener, the
    auto-config still creates the bean. Costs nothing in disabled mode.

### 2026-05-25 — M5 (Python LangGraph) gotchas

1. **uv + Python 3.12 is the right combo for 2026.** `uv sync --extra dev`
   resolves and installs in ~8s. The Python deps file (`agents/pyproject.toml`)
   is the source of truth — no `requirements.txt`, no `setup.py`. `.python-version`
   pins to 3.12; uv auto-fetches that interpreter if missing.
2. **grpc_tools.protoc emits flat-namespace imports.** `baseline_pb2_grpc.py`
   contains `import baseline_pb2` at the top — not `from . import baseline_pb2`.
   So putting all three generated modules under one package directory only
   works if that directory is on `sys.path`. Solution: keep a committed
   `deliberation/_proto/__init__.py` that does `sys.path.insert(0,
   os.path.dirname(__file__))`. The generated `_pb2*.py` files themselves are
   gitignored — `gen_protos.sh` recreates them on every `m5-install`.
3. **`anthropic` SDK uses tool-use for structured output.** No
   `response_format=json_schema` (yet). The pattern is: declare a tool whose
   `input_schema` matches your output shape, then set
   `tool_choice={"type": "tool", "name": "<tool_name>"}` so the model MUST
   call it. Then read `response.content` for the `tool_use` block and pull
   `block.input` as the structured payload.
4. **Anthropic prompt-caching needs explicit opt-in.** Mark the system
   prompt as `{"type": "text", "text": "...", "cache_control": {"type":
   "ephemeral"}}`. The default is no caching. Worth doing for M5 because
   the system prompt is identical across every event.
5. **`ChatOllama(format="json")` is the lowest-common-denominator
   structured output for Ollama.** Newer models (Qwen3, Gemma 3+, Llama 3.3+)
   support `format="json_schema"` via Ollama's structured outputs API, but
   older ones don't. Stick with `format="json"` + schema-in-prompt + post-hoc
   validation. The `parse_decision_payload` function does the validation so
   the same parser serves both backends.
6. **LangGraph parallel fanout = two `add_edge(source, X)` calls with the
   same source.** Both downstream nodes run on the same super-step. Joining
   them at a downstream node is automatic IF each parallel branch writes a
   DIFFERENT state key (no reducer needed). `errors` uses
   `Annotated[list[str], operator.add]` as the reducer so the parallel
   branches can both append.
7. **TypedDict with `total=False` is the right LangGraph state shape.**
   `dataclass` doesn't work — LangGraph expects to merge partial dicts back
   into the state, and dataclass instances need every field. `TypedDict`
   with `total=False` lets each node return only the keys it owns.
8. **`grpc.RpcError` can be raised with no `.code()` method.** In tests we
   construct it as a bare `RpcError()` and monkey-patch `.code = lambda:
   StatusCode.X`. In production code, defensive coding uses
   `getattr(exc, "code", lambda: None)()`.
9. **Mock `BaselineClient` / `GraphClient` with `MagicMock(spec=...)`.**
   `spec=` constrains the mock to the real class's method signature so a
   typo in the test (e.g. `client.get_baselines(...)`) fails loudly instead
   of silently auto-creating a method.
10. **`StrEnum` (Python 3.11+) is the right base for verdict labels.**
    Inheriting from both `str` and `Enum` works but ruff (UP042) prefers
    `StrEnum`. Same string-value semantics, cleaner declaration.
11. **`pytest-cov` runs as a plugin, not a wrapper.** Configure in
    `[tool.pytest.ini_options].addopts = ["--cov=deliberation",
    "--cov-fail-under=80"]`. The `[tool.coverage.run].omit` setting strips
    generated proto modules from the coverage calculation — mirrors the
    Java JaCoCo `<excludes>` pattern.
12. **`m5-test` depends on `m5-gen-proto`, not `m5-install`.** A fresh
    clone's first `make m5-test` regenerates the protos before running
    pytest. The generated `*_pb2.py` files are gitignored so this is
    mandatory.

### 2026-05-25 — M4 (Graph) gotchas

1. **Spring Boot 4 manages `neo4j-java-driver` at version 6.0.5** (not 5.x). Don't
   override the version in the root pom — Spring Boot's BOM imports
   `neo4j-java-driver-bom` at the version you set, and an arbitrary version's BOM
   may not exist on Maven Central. Use Spring Boot's managed version, period.
2. **`ObjectMapper` is NOT auto-registered as a Spring bean with just
   `spring-boot-starter-web` in Spring Boot 4.** Three options: (a) add
   `spring-boot-starter-json` explicitly, (b) instantiate directly in the
   consumer class (M4 chose this), (c) declare an `ObjectMapper @Bean` in a
   `@Configuration`. Note: `jackson-databind` was on the classpath the whole
   time — the JAR isn't the issue, the auto-config registration is.
3. **Spring's official gRPC starter (`@GrpcService`) works identically across
   M3 and M4.** No new gotchas; same protobuf-maven-plugin pattern, same
   `${grpc.version}` + `${protobuf-java.version}` properties at root level.
4. **Cypher path quantifier `*1..2` means "exactly 1 or 2 hops"** — three-hop
   targets are NOT in the result set. Worth re-deriving when writing the
   neighborhood test assertion (got bitten: expected ≥10 neighbors, got 7).
5. **Neo4j `MERGE` is idempotent but slow on large batches** — for the latency
   test seed we use `CREATE` for nodes and `MERGE` only for relationships.
   Trade-off: re-running the seed needs a fresh DB; fine for ITs with
   per-class Testcontainers.
6. **`neo4j:5-community` Docker image is multi-arch** (Apple Silicon + linux/amd64),
   used via Testcontainers' `Neo4jContainer<>(...)`. Same Apple-Silicon-safe
   pattern as `pgvector/pgvector` in M3.

### 2026-05-25 — M3 (Baseline) gotchas

1. **Spring Boot 4 removed `TestRestTemplate`.** Use `RestClient` directly with
   `@LocalServerPort` injection. See [BaselineRestIT](baseline/src/test/java/io/conclave/baseline/rest/BaselineRestIT.java).
2. **Multiple `@Service` constructors require `@Autowired` on one.** Spring Boot 4 won't
   auto-pick a constructor when there are >1 — even when only one is `public`. We hit
   this with `BaselineService` (production ctor + package-private ctor for `Clock`
   injection in tests). Annotate the production constructor with `@Autowired`.
3. **JaCoCo `<excludes>` inside a `<rule>` doesn't filter classes out of coverage.** It
   excludes BUNDLEs / PACKAGEs from being checked against that rule — different thing.
   To exclude generated code (Avro, protobuf, etc) from the coverage calculation, put
   `<excludes>` at the TOP-LEVEL `<configuration>` block of the JaCoCo plugin. Use the
   `io/conclave/...` slash-format with `**/*` for whole packages.
4. **Generated protobuf classes need their own package.** Set `java_package` in the
   proto to a separate package (`io.conclave.baseline.proto`) so the JaCoCo exclude
   is trivial. Your handwritten gRPC service goes in a different package
   (`io.conclave.baseline.grpc`).
5. **`spring-grpc-dependencies` BOM doesn't expose its managed versions as Maven
   properties.** The `protobuf-maven-plugin` needs `${protobuf-java.version}` and
   `${grpc.version}` at config time. Define them in the root pom mirroring what the
   BOM imports (currently `protobuf-java=4.33.4`, `grpc=1.77.1`). Verify after any
   spring-grpc version bump.
6. **pgvector text literal needs an explicit `::vector` cast on insert.** Use
   `INSERT ... VALUES (?, ?, ?::vector, ?, ?)` — without the cast, the driver sends
   text and Postgres complains the column type is `vector`. See `JdbcBaselineRepository`.
7. **langchain4j ships `all-MiniLM-L6-v2` as a JAR.** Zero external dependencies — DJL
   warms the ONNX session on first call (~100ms cold start on Apple M3). Cache the
   `EmbeddingModel` instance.
8. **Records with `float[]` fields need manual `equals`/`hashCode`.** Default record
   `equals` uses reference equality on array fields, which is almost always wrong.
   Override and use `Arrays.equals(...)` / `Arrays.hashCode(...)`. See `Baseline`.
9. **pgvector Docker image is multi-arch** (`pgvector/pgvector:pg16`). Wrap in
   `DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")`
   so the official `PostgreSQLContainer` accepts it.

### 2026-05-25 — M2 (Feature Extraction) gotchas

1. **`kafka-streams` is NOT in `spring-boot-starter-kafka`.** Have to add `org.apache.kafka:kafka-streams` explicitly. Spring Boot 4's slim starters tradeoff bites here.
2. **`KafkaProperties.buildStreamsProperties()` is no-arg** (same shape as `buildProducerProperties()`). Returns a `Map<String, Object>`.
3. **`KafkaStreams` constructor wants `Properties`, not `Map`.** Easy mistake; convert with `Properties p = new Properties(); p.putAll(map);`. The other constructor takes the old `org.apache.kafka.streams.StreamsConfig` wrapper.
4. **`SpecificAvroSerde` lives in `io.confluent:kafka-streams-avro-serde`** — a separate artifact from `kafka-avro-serializer` (which gives you the producer/consumer serializers). Don't confuse them.
5. **Per-IT-class JVM forking is required once `KafkaStreams` is in the main context.** Adding `<reuseForks>false</reuseForks>` to maven-failsafe-plugin fixed it. Symptom: second `@SpringBootTest` class fails with "Kafka Send failed" / "Bootstrap broker disconnected" even though Testcontainers spins up a fresh broker. Cost: ~+5s per IT class for JVM warmup, but the alternative is silent flakes.
6. **`@Profile`-conditional `@Component` beans need exactly one match.** If you accidentally `@Profile("fraud", "security")` two beans of the same type, Spring refuses to wire either when both profiles are inactive. Document the wiring in the active-profile yaml.
7. **`processValues` (FixedKeyProcessor API) is the right replacement for the deprecated `transformValues`** in Kafka Streams 4.x. Takes a `Supplier<FixedKeyProcessor<KIn, VIn, VOut>>` + varargs state-store names. The processor receives `FixedKeyRecord<K, V>` which lets you `record.withValue(...)` for a same-key emit.
8. **Velocity counter accuracy is a unit-test concern, not an IT concern.** Kafka Streams' at-least-once semantics + RocksDB checkpointing means an event can re-emit with a slightly stale counter during recovery/rebalance. Use `TopologyTestDriver` for deterministic logic verification; assert "counter > 0" at the IT level only.
9. **State directories pollute across runs.** `target/kafka-streams-state/` and `target/test-streams-state-*` keep their RocksDB files between `mvn verify` invocations. `mvn clean` wipes them. The `Makefile` `clean` target already covers this.

### 2026-05-25 — Spring Boot 4 modularization landmines (M1)

Hit several Spring Boot 3 → 4 migration traps during M1; documenting so M2+ doesn't re-derive them.

1. **`KafkaProperties` moved package.** Was `org.springframework.boot.autoconfigure.kafka.KafkaProperties` (SB3). Now `org.springframework.boot.kafka.autoconfigure.KafkaProperties` (SB4) — lives in the new `spring-boot-kafka` artifact rather than the monolithic `spring-boot-autoconfigure`. Expect similar moves for **every** autoconfig domain (web, jdbc, data, security, etc) as those modules surface.
2. **`buildProducerProperties()` signature changed.** SB3 had `buildProducerProperties(SslBundles)`. SB4 reverted to `buildProducerProperties()` (no-arg). The SSL bundle is resolved internally now.
3. **Use the `spring-boot-starter-kafka` starter, not `spring-kafka` directly.** In SB3 either worked because the autoconfig was always on the classpath. In SB4, the autoconfig only ships via the starter — pulling `org.springframework.kafka:spring-kafka` alone gives you the library but no `KafkaTemplate` / `KafkaAdmin` autowiring.
4. **`KafkaTemplate<?, ?>` autoconfig bean ≠ `KafkaTemplate<String, SpecificRecord>` injection target.** Spring 6+ generic-aware DI refuses to match `?` against a specific type parameter. Workaround: declare our own typed `ProducerFactory<K, V>` + `KafkaTemplate<K, V>` beans. The autoconfig's `@ConditionalOnMissingBean(KafkaTemplate.class)` then disables itself. See `orchestrator/src/main/java/io/conclave/ingest/KafkaProducerConfig.java`.
5. **Avro plugin generates `Instant` for `timestamp-millis`.** Default in avro-maven-plugin 1.12.0+; not `long`. Use `Instant.now()` to populate timestamp fields, not `Instant.now().toEpochMilli()`.
6. **Docker Desktop must be running** for any `mvn verify` invocation — Testcontainers fails fast otherwise. CI's `ubuntu-latest` runner has Docker built in. Make target `m1-verify` doesn't auto-start Docker; consider adding `colima start` / `docker desktop` shim if pain accumulates.
7. **Mockito self-attaching warning under Java 25.** Loud `WARNING: A Java agent has been loaded dynamically` per test class. Not a failure but noisy. Fix later by configuring the Mockito agent in Surefire's `argLine` (see Mockito docs for the agent setup).

---

## 💡 Ideas Worth Exploring Later (don't act on without spec change)

- **Schema Registry instead of file-based Avro.** Right now schemas are static files; once we run docker-compose with Confluent Schema Registry, we get evolution checks for free. Defer until M10 demo harness.
- **Prompt caching for the judge agent.** Anthropic prompt-caching gives huge cost savings on stable system prompts. M5 should use it — leave a note in M5 design.
- **Per-tenant rate limiting.** Out of scope per spec §12 ("Single tenant"). Skip.
