package com.investary.common.settings;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.investary.common.settings.SettingsExceptions.InvalidSettingsException;
import com.investary.common.settings.SettingsExceptions.SettingsConflictException;
import com.investary.common.settings.SettingsExceptions.UnknownSettingsSectionException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective runtime settings: the configured defaults plus the user's overrides from {@code app_settings}
 * (ADR 0012). Owning modules declare their fields with {@link SettingsContributor} and read overrides through
 * the typed getters, falling back to their own configuration. Overrides are cached for a few seconds and the
 * cache is dropped on every write; a {@link SettingsChangedEvent} is published after a successful write.
 */
@Service
public class RuntimeSettings {

    static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):[0-5]\\d");

    private final SettingsStore store;
    private final ObjectProvider<SettingsContributor> contributors;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RuntimeSettings(SettingsStore store, ObjectProvider<SettingsContributor> contributors, ApplicationEventPublisher events,
                           JsonMapper json) {
        this.store = store;
        this.contributors = contributors;
        this.events = events;
        this.json = json;
    }

    // ---- typed reads (consumers fall back to their configured value) ----

    public Optional<Boolean> bool(String section, String name) {
        return value(section, name).filter(JsonNode::isBoolean).map(JsonNode::booleanValue);
    }

    public Optional<Long> number(String section, String name) {
        return value(section, name).filter(JsonNode::isIntegralNumber).map(JsonNode::longValue);
    }

    public Optional<String> text(String section, String name) {
        return value(section, name).filter(JsonNode::isString).map(JsonNode::asString);
    }

    public Optional<UUID> uuid(String section, String name) {
        return text(section, name).flatMap(value -> {
            try {
                return Optional.of(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    public int integer(String section, String name, int fallback) {
        return number(section, name).map(Long::intValue).orElse(fallback);
    }

    public boolean bool(String section, String name, boolean fallback) {
        return bool(section, name).orElse(fallback);
    }

    private Optional<JsonNode> value(String section, String name) {
        JsonNode node = overrides(section).values().get(name);
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node);
    }

    /** Drops cached overrides, e.g. after the table was changed outside this service. */
    public void invalidate() {
        cache.clear();
    }

    // ---- views and writes (Automation Center settings API) ----

    public List<SectionView> sections() {
        return fieldsBySection().keySet().stream().map(this::section).toList();
    }

    public SectionView section(String section) {
        List<SettingField> fields = fields(section);
        Cached current = overrides(section);
        List<FieldView> views = fields.stream().map(field -> {
            JsonNode node = current.values().get(field.name());
            boolean overridden = node != null && !node.isNull();
            Object defaultValue = field.defaultValue() == null ? null : field.defaultValue().get();
            Object value = overridden ? scalar(node) : defaultValue;
            return new FieldView(field.name(), field.label(), field.help(), field.type().name(), value, defaultValue, overridden,
                    field.min(), field.max(), field.options(), field.nullable(), field.appliesAfterRestart(), field.readOnly());
        }).toList();
        return new SectionView(section, current.version(), views);
    }

    /**
     * Saves some fields of a section. A JSON null removes that override (back to the default).
     *
     * @throws InvalidSettingsException  422 with field errors
     * @throws SettingsConflictException 409 if the section changed since {@code version}
     */
    public SectionView update(String section, int version, Map<String, JsonNode> values) {
        List<SettingField> fields = fields(section);
        Map<String, SettingField> byName = new LinkedHashMap<>();
        fields.forEach(field -> byName.put(field.name(), field));
        Map<String, String> errors = new LinkedHashMap<>();
        Cached current = load(section);
        ObjectNode next = current.values().deepCopy();
        values.forEach((name, node) -> {
            SettingField field = byName.get(name);
            if (field == null) {
                errors.put(name, "is not a setting of section " + section);
            } else if (field.readOnly()) {
                errors.put(name, "can only be changed in the configuration");
            } else if (node == null || node.isNull() || node.isString() && node.asString().isBlank() && field.type() != SettingType.TEXT) {
                next.remove(name);
            } else {
                Optional<String> error = check(field, node);
                if (error.isPresent()) {
                    errors.put(name, error.get());
                } else if (field.type() == SettingType.TIME) {
                    String[] parts = node.asString().split(":");
                    next.put(name, LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])).toString());
                } else {
                    next.set(name, node);
                }
            }
        });
        if (errors.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>();
            next.properties().forEach(entry -> merged.put(entry.getKey(), scalar(entry.getValue())));
            contributors.orderedStream().forEach(contributor -> contributor.validate(section, merged, errors));
        }
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }
        write(section, next, version);
        return section(section);
    }

    /** Removes one override; the field returns to its configured default. */
    public SectionView reset(String section, String name) {
        boolean known = fields(section).stream().anyMatch(field -> field.name().equals(name));
        if (!known) {
            throw new InvalidSettingsException(Map.of(name, "is not a setting of section " + section));
        }
        Cached current = load(section);
        ObjectNode next = current.values().deepCopy();
        next.remove(name);
        write(section, next, current.version());
        return section(section);
    }

    private void write(String section, ObjectNode values, int expectedVersion) {
        Optional<Integer> written = store.write(section, json.writeValueAsString(values), expectedVersion);
        cache.remove(section);
        if (written.isEmpty()) {
            throw new SettingsConflictException(section);
        }
        events.publishEvent(new SettingsChangedEvent(section));
    }

    private static Optional<String> check(SettingField field, JsonNode node) {
        switch (field.type()) {
            case BOOLEAN -> {
                return node.isBoolean() ? Optional.empty() : Optional.of("must be true or false");
            }
            case INTEGER -> {
                if (!node.isIntegralNumber()) {
                    return Optional.of("must be a whole number");
                }
                long value = node.longValue();
                if (field.min() != null && value < field.min() || field.max() != null && value > field.max()) {
                    return Optional.of("must be between " + field.min() + " and " + field.max());
                }
                return Optional.empty();
            }
            case TIME -> {
                return node.isString() && TIME.matcher(node.asString()).matches() ? Optional.empty() : Optional.of("must be a time HH:mm");
            }
            case ZONE -> {
                if (!node.isString()) {
                    return Optional.of("must be an IANA time zone");
                }
                try {
                    ZoneId zone = ZoneId.of(node.asString());
                    return zone.getId().contains("/") || zone.getId().equals("UTC") ? Optional.empty()
                            : Optional.of("must be an IANA time zone such as Asia/Ho_Chi_Minh");
                } catch (DateTimeException e) {
                    return Optional.of("is not a known IANA time zone");
                }
            }
            case UUID -> {
                try {
                    if (node.isString()) {
                        UUID.fromString(node.asString());
                        return Optional.empty();
                    }
                } catch (IllegalArgumentException e) {
                    // fall through
                }
                return Optional.of("must be an id");
            }
            case ENUM -> {
                return node.isString() && field.options().contains(node.asString()) ? Optional.empty()
                        : Optional.of("must be one of " + String.join(", ", field.options()));
            }
            default -> {
                return node.isString() && node.asString().length() <= 200 ? Optional.empty() : Optional.of("must be text");
            }
        }
    }

    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        return node.asString();
    }

    private List<SettingField> fields(String section) {
        List<SettingField> fields = fieldsBySection().get(section);
        if (fields == null) {
            throw new UnknownSettingsSectionException(section);
        }
        return fields;
    }

    private Map<String, List<SettingField>> fieldsBySection() {
        List<SettingField> all = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> all.addAll(contributor.fields()));
        all.sort(Comparator.comparing(SettingField::section, Comparator.comparingInt(RuntimeSettings::sectionOrder))
                .thenComparingInt(SettingField::order));
        Map<String, List<SettingField>> bySection = new LinkedHashMap<>();
        all.forEach(field -> bySection.computeIfAbsent(field.section(), s -> new ArrayList<>()).add(field));
        return bySection;
    }

    private static int sectionOrder(String section) {
        return switch (section) {
            case "gmail" -> 0;
            case "processing" -> 1;
            case "brief" -> 2;
            case "ai" -> 3;
            default -> 4;
        };
    }

    private Cached overrides(String section) {
        Cached cached = cache.get(section);
        if (cached != null && System.nanoTime() - cached.loadedAt() < CACHE_TTL.toNanos()) {
            return cached;
        }
        return load(section);
    }

    private Cached load(String section) {
        Cached loaded = store.find(section).map(row -> {
            JsonNode node = json.readTree(row.json());
            ObjectNode object = node.isObject() ? (ObjectNode) node : json.createObjectNode();
            return new Cached(object, row.version(), System.nanoTime());
        }).orElseGet(() -> new Cached(json.createObjectNode(), 0, System.nanoTime()));
        cache.put(section, loaded);
        return loaded;
    }

    private record Cached(ObjectNode values, int version, long loadedAt) {
    }

    /**
     * @param version pass back when saving; a concurrent change makes the save fail with 409
     */
    public record SectionView(String section, int version, List<FieldView> fields) {
    }

    /**
     * @param value        the effective value (override or default)
     * @param defaultValue the configured default
     */
    public record FieldView(String name, String label, String help, String type, Object value, Object defaultValue, boolean overridden,
                            Long min, Long max, List<String> options, boolean nullable, boolean appliesAfterRestart, boolean readOnly) {
    }
}
