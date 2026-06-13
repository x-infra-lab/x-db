package io.github.xinfra.lab.xdb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Server configuration for x-db.
 */
public class XDBConfig {

    private static final Logger log = LoggerFactory.getLogger(XDBConfig.class);

    private int port = 4000;
    private String pdAddresses = "127.0.0.1:2379";
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    private int maxConnections = 1000;

    public XDBConfig() {}

    public static XDBConfig load(String[] args) {
        XDBConfig config = loadFromYaml();
        if (args.length > 0) {
            config.port(Integer.parseInt(args[0]));
        }
        if (args.length > 1) {
            config.pdAddresses(args[1]);
        }
        return config;
    }

    private static XDBConfig loadFromYaml() {
        XDBConfig config = new XDBConfig();

        InputStream is = findConfigStream();
        if (is == null) {
            log.info("No x-db.yml found, using defaults");
            return config;
        }

        try (is) {
            applyYaml(config, is);
        } catch (Exception e) {
            log.warn("Failed to parse x-db.yml, using defaults", e);
        }

        return config;
    }

    private static InputStream findConfigStream() {
        Path filePath = Path.of("x-db.yml");
        if (Files.exists(filePath)) {
            try {
                log.info("Loading config from {}", filePath.toAbsolutePath());
                return Files.newInputStream(filePath);
            } catch (Exception e) {
                log.warn("Failed to read {}", filePath, e);
            }
        }

        InputStream cpStream = XDBConfig.class.getClassLoader().getResourceAsStream("x-db.yml");
        if (cpStream != null) {
            log.info("Loading config from classpath:x-db.yml");
        }
        return cpStream;
    }

    @SuppressWarnings("unchecked")
    private static void applyYaml(XDBConfig config, InputStream is) {
        Map<String, Object> yaml = new Yaml().load(is);
        if (yaml == null) return;
        if (yaml.containsKey("port")) config.port(((Number) yaml.get("port")).intValue());
        if (yaml.containsKey("pdAddresses")) config.pdAddresses((String) yaml.get("pdAddresses"));
        if (yaml.containsKey("workerThreads")) {
            int wt = ((Number) yaml.get("workerThreads")).intValue();
            if (wt > 0) config.workerThreads(wt);
        }
        if (yaml.containsKey("maxConnections")) config.maxConnections(((Number) yaml.get("maxConnections")).intValue());
    }

    // ---- Getters ----

    public int port() {
        return port;
    }

    public String pdAddresses() {
        return pdAddresses;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int maxConnections() {
        return maxConnections;
    }

    // ---- Builder-style setters ----

    public XDBConfig port(int port) {
        this.port = port;
        return this;
    }

    public XDBConfig pdAddresses(String pdAddresses) {
        this.pdAddresses = pdAddresses;
        return this;
    }

    public XDBConfig workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public XDBConfig maxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }
}
