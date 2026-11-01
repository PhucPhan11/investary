package com.investary.common.settings;

/** Value types of runtime settings; each is stored as a JSON scalar. */
public enum SettingType {
    BOOLEAN,
    /** Whole number with optional bounds. */
    INTEGER,
    /** Local time {@code HH:mm}. */
    TIME,
    /** IANA time-zone id. */
    ZONE,
    UUID,
    /** One of {@link SettingField#options()}. */
    ENUM,
    /** Free text; only used for read-only fields. */
    TEXT
}
