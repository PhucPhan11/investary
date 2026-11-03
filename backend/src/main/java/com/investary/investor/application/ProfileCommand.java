package com.investary.investor.application;

import java.util.List;

import com.investary.investor.domain.ExperienceLevel;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.RiskTolerance;

public record ProfileCommand(ExperienceLevel experienceLevel, RiskTolerance riskTolerance, String objectives,
                             InvestmentHorizon investmentHorizon, List<String> preferredInstruments, String restrictions,
                             String liquidityNeeds, String notes) {
}
