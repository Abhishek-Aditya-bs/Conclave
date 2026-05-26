package io.conclave.generators;

import java.util.Arrays;

/**
 * Minimal {@code --flag value} parser. Avoids picocli to keep the dependency
 * footprint trivial for a CLI tool.
 *
 * <p>Recognised flags (all optional):
 * <ul>
 *   <li>{@code --bootstrap} — Kafka bootstrap servers; defaults to {@code localhost:9092}
 *       or the {@code KAFKA_BOOTSTRAP_SERVERS} env var.</li>
 *   <li>{@code --schema-registry} — Schema Registry URL; defaults to
 *       {@code mock://conclave-default} or {@code SCHEMA_REGISTRY_URL}.</li>
 *   <li>{@code --seed} — RNG seed for deterministic runs. Defaults to 42.</li>
 *   <li>{@code --clean} — count of clean events. Domain-specific default.</li>
 *   <li>{@code --rings} — number of adversarial rings/campaigns. Domain-specific default.</li>
 *   <li>{@code --ato} — number of ATO campaigns.</li>
 *   <li>{@code --extra} — number of bust-out (fraud) / exfil (security) campaigns.</li>
 * </ul>
 */
public final class CliOptions {

    private final String bootstrapServers;
    private final String schemaRegistryUrl;
    private final long seed;
    private final int cleanCount;
    private final int rings;
    private final int atoCampaigns;
    private final int extraCampaigns;

    private CliOptions(String bootstrapServers, String schemaRegistryUrl, long seed,
                       int cleanCount, int rings, int atoCampaigns, int extraCampaigns) {
        this.bootstrapServers = bootstrapServers;
        this.schemaRegistryUrl = schemaRegistryUrl;
        this.seed = seed;
        this.cleanCount = cleanCount;
        this.rings = rings;
        this.atoCampaigns = atoCampaigns;
        this.extraCampaigns = extraCampaigns;
    }

    public static CliOptions parse(String[] args, Defaults defaults) {
        String bootstrap = envOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String registry = envOrDefault("SCHEMA_REGISTRY_URL", "mock://conclave-default");
        long seed = 42L;
        int cleanCount = defaults.cleanCount();
        int rings = defaults.rings();
        int ato = defaults.atoCampaigns();
        int extra = defaults.extraCampaigns();

        int i = 0;
        while (i < args.length) {
            String flag = args[i];
            if ("--help".equals(flag) || "-h".equals(flag)) {
                printHelp(defaults);
                throw new HelpRequested();
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + flag + " requires a value");
            }
            String value = args[i + 1];
            switch (flag) {
                case "--bootstrap" -> bootstrap = value;
                case "--schema-registry" -> registry = value;
                case "--seed" -> seed = Long.parseLong(value);
                case "--clean" -> cleanCount = nonNegative(flag, value);
                case "--rings" -> rings = nonNegative(flag, value);
                case "--ato" -> ato = nonNegative(flag, value);
                case "--extra" -> extra = nonNegative(flag, value);
                default -> throw new IllegalArgumentException(
                        "unknown argument: " + flag + " — try --help. all args: " + Arrays.toString(args));
            }
            i += 2;
        }

        return new CliOptions(bootstrap, registry, seed, cleanCount, rings, ato, extra);
    }

    private static int nonNegative(String flag, String raw) {
        int v = Integer.parseInt(raw);
        if (v < 0) {
            throw new IllegalArgumentException(flag + " must be >= 0, got " + v);
        }
        return v;
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void printHelp(Defaults d) {
        System.out.println("""
                conclave generator
                  --bootstrap <servers>        Kafka bootstrap servers
                  --schema-registry <url>      Confluent Schema Registry URL
                  --seed <long>                RNG seed (deterministic runs)
                  --clean <int>                count of clean events    (default %d)
                  --rings <int>                ring/campaign count       (default %d)
                  --ato <int>                  ATO campaign count        (default %d)
                  --extra <int>                bust-out / exfil count    (default %d)
                """.formatted(d.cleanCount(), d.rings(), d.atoCampaigns(), d.extraCampaigns()));
    }

    public String bootstrapServers() { return bootstrapServers; }
    public String schemaRegistryUrl() { return schemaRegistryUrl; }
    public long seed() { return seed; }
    public int cleanCount() { return cleanCount; }
    public int rings() { return rings; }
    public int atoCampaigns() { return atoCampaigns; }
    public int extraCampaigns() { return extraCampaigns; }

    public record Defaults(int cleanCount, int rings, int atoCampaigns, int extraCampaigns) {}

    /** Signal that {@code --help} was requested. The main() catches this and exits 0. */
    public static class HelpRequested extends RuntimeException {}
}
