package com.investary.investor.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.investary.investor.domain.GoalPriority;
import com.investary.investor.domain.InvestorGoal;

public record GoalView(UUID id, String name, String description, BigDecimal targetAmount, LocalDate targetDate,
                       String currency, GoalPriority priority, String notes, Instant createdAt, Instant updatedAt) {

    static GoalView of(InvestorGoal goal) {
        return new GoalView(goal.getId(), goal.getName(), goal.getDescription(), goal.getTargetAmount(), goal.getTargetDate(),
                goal.getCurrency(), goal.getPriority(), goal.getNotes(), goal.getCreatedAt(), goal.getUpdatedAt());
    }
}
