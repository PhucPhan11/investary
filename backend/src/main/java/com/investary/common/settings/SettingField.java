package com.investary.common.settings;

import java.util.List;
import java.util.function.Supplier;

/**
 * One user-editable setting. The default comes from {@code application.yml} / environment; an override saved
 * in {@code app_settings} replaces it until it is reset.
 *
 * @param section             settings section ({@code gmail}, {@code processing}, {@code brief}, {@code ai})
 * @param name                field name inside the section
 * @param type                value type and validation
 * @param label               short label for the UI
 * @param help                one sentence for the UI, or null
 * @param defaultValue        the configured default (display only; consumers apply it themselves)
 * @param min                 lower bound for INTEGER fields, or null
 * @param max                 upper bound for INTEGER fields, or null
 * @param options             allowed values of ENUM fields
 * @param nullable            the field may be empty (no value means "off" or "not set")
 * @param appliesAfterRestart the value is only read at start-up
 * @param readOnly            shown, but changeable only through configuration
 * @param order               position in the section
 */
public record SettingField(String section, String name, SettingType type, String label, String help, Supplier<Object> defaultValue,
                           Long min, Long max, List<String> options, boolean nullable, boolean appliesAfterRestart, boolean readOnly,
                           int order) {

    public static SettingField bool(String section, String name, int order, String label, String help, Supplier<Object> defaultValue) {
        return new SettingField(section, name, SettingType.BOOLEAN, label, help, defaultValue, null, null, List.of(), false, false, false, order);
    }

    public static SettingField integer(String section, String name, int order, String label, String help, Supplier<Object> defaultValue,
                                       long min, long max) {
        return new SettingField(section, name, SettingType.INTEGER, label, help, defaultValue, min, max, List.of(), false, false, false, order);
    }

    public static SettingField of(String section, String name, SettingType type, int order, String label, String help,
                                  Supplier<Object> defaultValue) {
        return new SettingField(section, name, type, label, help, defaultValue, null, null, List.of(), false, false, false, order);
    }

    public SettingField withNullable() {
        return new SettingField(section, name, type, label, help, defaultValue, min, max, options, true, appliesAfterRestart, readOnly, order);
    }

    public SettingField withRestart() {
        return new SettingField(section, name, type, label, help, defaultValue, min, max, options, nullable, true, readOnly, order);
    }

    public SettingField withReadOnly() {
        return new SettingField(section, name, type, label, help, defaultValue, min, max, options, nullable, appliesAfterRestart, true, order);
    }

    public SettingField withOptions(List<String> values) {
        return new SettingField(section, name, type, label, help, defaultValue, min, max, List.copyOf(values), nullable, appliesAfterRestart,
                readOnly, order);
    }
}
