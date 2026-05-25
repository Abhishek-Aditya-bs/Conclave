# ADR-001: FeatureSpec abstraction for domain-agnostic feature extraction

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-05-25 |
| **Module** | M2 — Feature Extraction Stream Job |
| **Spec references** | [spec.md §1](../../spec.md), [§4 design decision #6](../../spec.md), [§6 M2 contract](../../spec.md) |

---

## Context

CONCLAVE's headline architectural claim is "**one architecture, two domains**" — the fraud
and SOC configurations must boot from the same binary, share the same code paths, and
differ *only* in declarative config. The spec calls out feature extraction as one of two
places where per-domain code is allowed to live (the other being graph templates in M4).

We need a way to express the per-domain bits of the Kafka Streams topology — input/output
topic names, raw/enriched schemas, serdes, state stores, enrichment logic — without
duplicating the surrounding topology shell (source, sink, key serialization, lifecycle
management). The shell is the same for both domains; only the middle box differs.

Three approaches were considered:

1. **Two fully-separate topologies, one per domain.** Simple but violates the spec's
   "one architecture" rule and means M3/M4 integration would have to wire twice.
2. **Subclassing pattern** — abstract `AbstractFeatureExtractor` with `enrich()` /
   `configureStores()` template methods. Works, but couples the implementations to a base
   class and makes per-task instantiation under Kafka Streams (which builds `Topology`
   instances, not Spring beans) awkward to test.
3. **Interface-driven composition** — a `FeatureSpec` interface that exposes everything
   the shared topology builder needs (topics, serdes, store config, enrichment function).
   A static `FeatureExtractionTopology.build(spec)` utility composes these into a real
   `Topology`. Implementations are Spring `@Component @Profile("...")` beans.

## Decision

We adopt approach #3.

**Contract:** [`FeatureSpec<R, E>`](../../orchestrator/src/main/java/io/conclave/stream/FeatureSpec.java)
parameterized over the raw Avro record type `R` and the enriched type `E`. Required
members:

- `EventDomain domain()` — which configuration this spec serves
- `inputTopic()` / `outputTopic()` — defaulted from the domain
- `rawType()` / `enrichedType()` — for serde construction and test wiring
- `rawSerde()` / `enrichedSerde()` — configured once at construction (Schema Registry URL)
- `configureStores(StreamsBuilder)` — declare any state stores (default: none)
- `enrich(KStream<String, R>) → KStream<String, E>` — the per-domain enrichment

**Shell:** [`FeatureExtractionTopology.build(spec)`](../../orchestrator/src/main/java/io/conclave/stream/FeatureExtractionTopology.java)
takes a `FeatureSpec`, calls `configureStores`, builds the source `KStream`, hands it to
`enrich`, and writes the result to `outputTopic`. Pure function — no state held.

**Wiring:** Each `FeatureSpec` implementation is a `@Component` with a profile guard
(`@Profile("fraud")` / `@Profile("security")`). Spring injects the single active spec
into [`KafkaStreamsConfig`](../../orchestrator/src/main/java/io/conclave/stream/KafkaStreamsConfig.java),
which builds the topology and starts a `KafkaStreams` instance.

## Consequences

**Positive**
- Adding a new domain (forbidden by the spec but worth understanding the cost) means
  one schema file, one `FeatureSpec` implementation, and one `application-<domain>.yaml`.
  No changes to the shell or to any other module.
- Tests get two flavors for free: per-domain unit tests using `TopologyTestDriver`
  (no broker), and per-profile integration tests using `@SpringBootTest` + Testcontainers.
- Profile-conditional beans surface bad config early — Spring fails fast if no
  `FeatureSpec` matches the active profile.

**Negative**
- The `enrich(KStream) → KStream` signature is awkward when implementations need to
  re-key the stream for aggregation. The convention "re-key internally for grouping,
  re-key back to `eventId` before returning" is documented but easy to violate.
- The serdes are configured eagerly at construction with the Schema Registry URL.
  Tests must inject `mock://...` URLs explicitly; the URL can't change at runtime.
- Each domain's stateful processors get their own state-store names. Spring profiles
  prevent cross-domain bean collisions, but if we ever ran both pipelines in the same
  JVM (we don't), store names would need namespacing.

**Neutral**
- `EnrichmentProcessor` is duplicated between the two specs (different field shapes).
  Trying to genericize it added more code than it saved; we accept the duplication.

## Alternatives rejected

- **AbstractFeatureExtractor base class.** Rejected because the template-method pattern
  couples implementations to the parent's lifecycle and makes ad-hoc topology
  experimentation harder in tests.
- **Single topology with runtime branching.** A single topology that branches on a
  `domain` header would technically share more code, but kills the "domain knowledge is
  declarative" property: every change to one domain risks breaking the other.

## References

- spec.md §4 (architecture diagram) and §6 (M2 contract)
- [Kafka Streams DSL — KStream.processValues](https://kafka.apache.org/40/javadoc/org/apache/kafka/streams/kstream/KStream.html#processValues(org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier,java.lang.String...))
- [SCRATCHPAD entry from 2026-05-25 on M2 design](../../SCRATCHPAD.md)
