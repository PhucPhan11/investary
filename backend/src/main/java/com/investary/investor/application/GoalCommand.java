package com.investary.investor.application;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.investary.investor.domain.GoalPriority;

/**
 * @param currency defaults to VND
 * @param priority defaults to MEDIUM
 */
public record GoalCommand(String name, String description, BigDecimal targetAmount, LocalDate targetDate, String currency,
                          GoalPriority priority, String notes) {
}
