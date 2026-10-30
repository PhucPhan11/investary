package com.investary.investor.application;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.investor.domain.GoalPriority;
import com.investary.investor.domain.InvestorGoal;
import com.investary.investor.domain.InvestorProfile;
import com.investary.investor.persistence.InvestorGoalRepository;
import com.investary.investor.persistence.InvestorProfileRepository;

/**
 * Changes the investor profile and goals. Only the user, through the REST API, calls this; no AI
 * component may depend on it (enforced by {@code ArchitectureTest}).
 */
@Service
@Transactional
public class InvestorProfileService {

    private final InvestorProfileRepository profiles;
    private final InvestorGoalRepository goals;
    private final Clock clock;

    public InvestorProfileService(InvestorProfileRepository profiles, InvestorGoalRepository goals, Clock clock) {
        this.profiles = profiles;
        this.goals = goals;
        this.clock = clock;
    }

    public ProfileView saveProfile(ProfileCommand command) {
        InvestorProfile profile = profiles.findFirstByOrderByCreatedAtAsc().orElseGet(() -> new InvestorProfile(clock.instant()));
        profile.update(command.experienceLevel(), command.riskTolerance(), clean(command.objectives()),
                command.investmentHorizon(), cleanList(command.preferredInstruments()), clean(command.restrictions()),
                clean(command.liquidityNeeds()), clean(command.notes()), clock.instant());
        return ProfileView.of(profiles.saveAndFlush(profile));
    }

    public GoalView createGoal(GoalCommand command) {
        InvestorGoal goal = new InvestorGoal(clock.instant());
        apply(goal, command);
        return GoalView.of(goals.saveAndFlush(goal));
    }

    public GoalView updateGoal(UUID id, GoalCommand command) {
        InvestorGoal goal = goals.findById(id).orElseThrow(() -> new GoalNotFoundException(id));
        apply(goal, command);
        return GoalView.of(goals.saveAndFlush(goal));
    }

    public void deleteGoal(UUID id) {
        InvestorGoal goal = goals.findById(id).orElseThrow(() -> new GoalNotFoundException(id));
        goals.delete(goal);
    }

    private void apply(InvestorGoal goal, GoalCommand command) {
        String currency = command.currency() == null || command.currency().isBlank() ? "VND"
                : command.currency().strip().toUpperCase(Locale.ROOT);
        goal.update(command.name().strip(), clean(command.description()), command.targetAmount(), command.targetDate(),
                currency, command.priority() == null ? GoalPriority.MEDIUM : command.priority(), clean(command.notes()),
                clock.instant());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().map(InvestorProfileService::clean).filter(Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }
}
