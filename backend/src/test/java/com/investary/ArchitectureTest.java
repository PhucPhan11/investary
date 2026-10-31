package com.investary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the module dependency direction {@code knowledge → email → research → document → common}
 * (see AGENTS.md and docs/failures/0003-module-dependency-cycle.md) by scanning main-source imports.
 * Fix the offending dependency instead of relaxing these rules.
 */
class ArchitectureTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/investary");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?com\\.investary\\.([a-z]+)\\.([a-z]+)", Pattern.MULTILINE);

    /** Feature modules each module may depend on (common is always allowed). */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "common", Set.of(),
            "document", Set.of(),
            "research", Set.of("document"),
            "email", Set.of("research", "document"),
            "knowledge", Set.of("email", "research", "document"));

    /** Packages that are internal to their module. */
    private static final Set<String> INTERNAL_LAYERS = Set.of("api", "persistence", "storage");

    private static List<Dependency> dependencies;

    @BeforeAll
    static void scan() throws IOException {
        dependencies = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Path relative = MAIN_SOURCES.relativize(file);
                if (relative.getNameCount() < 2) {
                    continue; // InvestaryApplication
                }
                String module = relative.getName(0).toString();
                Matcher matcher = IMPORT.matcher(Files.readString(file));
                while (matcher.find()) {
                    String target = matcher.group(1);
                    if (!target.equals(module)) {
                        dependencies.add(new Dependency(relative.toString().replace('\\', '/'), module, target, matcher.group(2)));
                    }
                }
            }
        }
        assertThat(dependencies).as("source scan found cross-module imports").isNotEmpty();
    }

    @Test
    void modulesDependOnlyInTheAllowedDirection() {
        List<String> violations = dependencies.stream()
                .filter(d -> !d.target().equals("common"))
                .filter(d -> !ALLOWED.getOrDefault(d.module(), Set.of()).contains(d.target()))
                .map(d -> d.file() + " imports com.investary." + d.target() + "." + d.layer()
                        + " (allowed for '" + d.module() + "': common" + ALLOWED.getOrDefault(d.module(), Set.of()) + ")")
                .toList();

        assertThat(violations)
                .as("Dependency direction is email -> research -> document -> common. Move shared code to common "
                        + "or call the other module's application service.")
                .isEmpty();
    }

    @Test
    void modulesDoNotReachIntoOtherModulesInternals() {
        List<String> violations = dependencies.stream()
                .filter(d -> !d.target().equals("common"))
                .filter(d -> INTERNAL_LAYERS.contains(d.layer()))
                .map(d -> d.file() + " imports com.investary." + d.target() + "." + d.layer())
                .toList();

        assertThat(violations)
                .as("Use the other module's application services instead of its api/persistence/storage packages.")
                .isEmpty();
    }

    @Test
    void commonDependsOnNoFeatureModule() {
        assertThat(dependencies).filteredOn(d -> d.module().equals("common")).isEmpty();
    }

    private record Dependency(String file, String module, String target, String layer) {
    }
}
