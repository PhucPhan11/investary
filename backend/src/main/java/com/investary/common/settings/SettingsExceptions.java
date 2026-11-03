package com.investary.common.settings;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

/** Expected failures of the runtime settings API. */
public final class SettingsExceptions {

    private SettingsExceptions() {
    }

    public static class InvalidSettingsException extends ApiException {

        private final Map<String, String> errors;

        public InvalidSettingsException(Map<String, String> errors) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INVALID_SETTINGS, "Invalid settings", "One or more settings are invalid");
            this.errors = Map.copyOf(errors);
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("errors", errors);
        }
    }

    public static class SettingsConflictException extends ApiException {
        public SettingsConflictException(String section) {
            super(HttpStatus.CONFLICT, ErrorCode.SETTINGS_CONFLICT, "Settings changed",
                    "The " + section + " settings were changed elsewhere; reload and try again");
        }
    }

    public static class UnknownSettingsSectionException extends ApiException {
        public UnknownSettingsSectionException(String section) {
            super(HttpStatus.NOT_FOUND, ErrorCode.INVALID_SETTINGS, "Unknown settings section",
                    "There is no settings section '" + section + "'");
        }
    }
}
