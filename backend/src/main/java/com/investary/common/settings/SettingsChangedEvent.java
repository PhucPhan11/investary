package com.investary.common.settings;

/** Published after a settings section was saved or reset. */
public record SettingsChangedEvent(String section) {
}
