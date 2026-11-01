package com.investary.common.settings;

import java.util.List;
import java.util.Map;

/**
 * Implemented by the module that owns some settings. Several contributors may add fields to the same
 * section; {@link RuntimeSettings} merges them.
 */
public interface SettingsContributor {

    List<SettingField> fields();

    /**
     * Cross-field or database checks after type and bound validation.
     *
     * @param section the section being saved
     * @param values  the section's effective override values after the change (field name to value)
     * @param errors  add {@code field -> message} for every problem
     */
    default void validate(String section, Map<String, Object> values, Map<String, String> errors) {
    }
}
