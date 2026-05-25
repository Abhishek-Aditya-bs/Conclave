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
