package com.investary.investor.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.investor.persistence.InvestorGoalRepository;
import com.investary.investor.persistence.InvestorProfileRepository;

/**
 * Read-only access to the investor profile and goals. This is the only investor service other modules
 * (the portfolio intelligence assistant) may use.
 */
@Service
@Transactional(readOnly = true)
public class InvestorQueryService {

    private static final Comparator<GoalView> GOAL_ORDER = Comparator.comparing(GoalView::priority)
            .thenComparing(GoalView::targetDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(GoalView::name, String.CASE_INSENSITIVE_ORDER);

    private final InvestorProfileRepository profiles;
    private final InvestorGoalRepository goals;

    public InvestorQueryService(InvestorProfileRepository profiles, InvestorGoalRepository goals) {
        this.profiles = profiles;
        this.goals = goals;
    }

    public ProfileView profile() {
        return profiles.findFirstByOrderByCreatedAtAsc().map(ProfileView::of).orElse(ProfileView.EMPTY);
    }

    /** Goals by priority (HIGH first), then target date, then name. */
    public List<GoalView> goals() {
        return goals.findAll().stream().map(GoalView::of).sorted(GOAL_ORDER).toList();
    }

    public GoalView goal(UUID id) {
        return goals.findById(id).map(GoalView::of).orElseThrow(() -> new GoalNotFoundException(id));
    }
}
