package com.investary.investor.application;

import java.time.Instant;
import java.util.List;

import com.investary.investor.domain.ExperienceLevel;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.InvestorProfile;
import com.investary.investor.domain.RiskTolerance;

/**
 * Read model of the investor profile. {@code configured} is false until the user saves a profile; the
 * other fields are then empty.
 */
public record ProfileView(boolean configured, ExperienceLevel experienceLevel, RiskTolerance riskTolerance,
                          String objectives, InvestmentHorizon investmentHorizon, List<String> preferredInstruments,
                          String restrictions, String liquidityNeeds, String notes, Instant updatedAt) {

    public static final ProfileView EMPTY = new ProfileView(false, null, null, null, null, List.of(), null, null, null, null);

    static ProfileView of(InvestorProfile profile) {
        return new ProfileView(true, profile.getExperienceLevel(), profile.getRiskTolerance(), profile.getObjectives(),
                profile.getInvestmentHorizon(), profile.getPreferredInstruments(), profile.getRestrictions(),
                profile.getLiquidityNeeds(), profile.getNotes(), profile.getUpdatedAt());
    }
}
