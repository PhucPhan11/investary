package com.investary.investor.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.investor.application.GoalCommand;
import com.investary.investor.application.GoalView;
import com.investary.investor.application.InvestorProfileService;
import com.investary.investor.application.InvestorQueryService;
import com.investary.investor.application.ProfileCommand;
import com.investary.investor.application.ProfileView;
import com.investary.investor.domain.ExperienceLevel;
import com.investary.investor.domain.GoalPriority;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.RiskTolerance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The investor's self-declared profile and goals. Investary only stores what the user enters.
 */
@RestController
@RequestMapping("/api/v1/investor")
public class InvestorController {

    private final InvestorQueryService queries;
    private final InvestorProfileService profiles;

    public InvestorController(InvestorQueryService queries, InvestorProfileService profiles) {
        this.queries = queries;
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public ProfileView profile() {
        return queries.profile();
    }

    @PutMapping("/profile")
    public ProfileView saveProfile(@Valid @RequestBody ProfileRequest request) {
        return profiles.saveProfile(new ProfileCommand(request.experienceLevel(), request.riskTolerance(), request.objectives(),
                request.investmentHorizon(), request.preferredInstruments(), request.restrictions(), request.liquidityNeeds(),
                request.notes()));
    }

    @GetMapping("/goals")
    public List<GoalView> goals() {
        return queries.goals();
    }

    @GetMapping("/goals/{id}")
    public GoalView goal(@PathVariable UUID id) {
        return queries.goal(id);
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalView createGoal(@Valid @RequestBody GoalRequest request) {
        return profiles.createGoal(request.command());
    }

    @PutMapping("/goals/{id}")
    public GoalView updateGoal(@PathVariable UUID id, @Valid @RequestBody GoalRequest request) {
        return profiles.updateGoal(id, request.command());
    }

    @DeleteMapping("/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@PathVariable UUID id) {
        profiles.deleteGoal(id);
    }

    public record ProfileRequest(
            ExperienceLevel experienceLevel,
            RiskTolerance riskTolerance,
            @Size(max = 2000) String objectives,
            InvestmentHorizon investmentHorizon,
            @Size(max = 20) List<@NotBlank @Size(max = 50) String> preferredInstruments,
            @Size(max = 2000) String restrictions,
            @Size(max = 2000) String liquidityNeeds,
            @Size(max = 4000) String notes) {
    }

    public record GoalRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 20, fraction = 4) BigDecimal targetAmount,
            LocalDate targetDate,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String currency,
            GoalPriority priority,
            @Size(max = 2000) String notes) {

        GoalCommand command() {
            return new GoalCommand(name, description, targetAmount, targetDate, currency, priority, notes);
        }
    }
}
