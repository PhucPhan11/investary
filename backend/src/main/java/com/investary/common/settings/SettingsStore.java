package com.investary.common.settings;

import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app_settings}: one row per section holding the user's overrides as a JSON object, with a version for
 * optimistic concurrency.
 */
@Repository
public class SettingsStore {

    private final NamedParameterJdbcTemplate jdbc;

    public SettingsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(String section) {
        return jdbc.query("select value::text as value, version from app_settings where section = :section",
                new MapSqlParameterSource("section", section),
                (rs, i) -> new Row(rs.getString("value"), rs.getInt("version"))).stream().findFirst();
    }

    /**
     * Writes the section's overrides if its version is still {@code expectedVersion} (0 when no row exists yet).
     *
     * @return the new version, or empty if the section was changed concurrently
     */
    public Optional<Integer> write(String section, String json, int expectedVersion) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("section", section).addValue("value", json)
                .addValue("expected", expectedVersion);
        if (expectedVersion == 0) {
            int inserted = jdbc.update("""
                    insert into app_settings (section, value, version, updated_at) values (:section, cast(:value as jsonb), 1, now())
                    on conflict (section) do nothing
                    """, params);
            if (inserted == 1) {
                return Optional.of(1);
            }
        }
        int updated = jdbc.update("""
                update app_settings set value = cast(:value as jsonb), version = version + 1, updated_at = now()
                where section = :section and version = :expected
                """, params);
        return updated == 1 ? Optional.of(expectedVersion + 1) : Optional.empty();
    }

    public record Row(String json, int version) {
    }
}
