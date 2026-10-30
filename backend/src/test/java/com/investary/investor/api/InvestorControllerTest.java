package com.investary.investor.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.investor.application.GoalNotFoundException;
import com.investary.investor.application.InvestorProfileService;
import com.investary.investor.application.InvestorQueryService;
import com.investary.investor.application.ProfileCommand;
import com.investary.investor.application.ProfileView;
import com.investary.investor.domain.InvestmentHorizon;
import com.investary.investor.domain.RiskTolerance;

@WebMvcTest(InvestorController.class)
class InvestorControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InvestorQueryService queries;

    @MockitoBean
    InvestorProfileService profiles;

    @Test
    void savesTheProfile() throws Exception {
        when(profiles.saveProfile(any())).thenReturn(new ProfileView(true, null, RiskTolerance.LOW, null, InvestmentHorizon.LONG,
                List.of("ETF"), null, null, null, null));

        mvc.perform(put("/api/v1/investor/profile").contentType(MediaType.APPLICATION_JSON).content("""
                        {"riskTolerance": "LOW", "investmentHorizon": "LONG", "preferredInstruments": ["ETF"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured", is(true)))
                .andExpect(jsonPath("$.riskTolerance", is("LOW")));
        verify(profiles).saveProfile(new ProfileCommand(null, RiskTolerance.LOW, null, InvestmentHorizon.LONG, List.of("ETF"),
                null, null, null));
    }

    @Test
    void rejectsUnknownRiskToleranceAndOverlongText() throws Exception {
        // An unknown enum value is unreadable JSON: a plain 400 from the framework handler.
        mvc.perform(put("/api/v1/investor/profile").contentType(MediaType.APPLICATION_JSON).content("{\"riskTolerance\": \"YOLO\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/investor/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectives\": \"" + "x".repeat(2001) + "\", \"preferredInstruments\": [\" \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("objectives")));
        verifyNoInteractions(profiles);
    }

    @Test
    void validatesGoals() throws Exception {
        mvc.perform(post("/api/v1/investor/goals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\", \"targetAmount\": -1, \"currency\": \"VN\", \"priority\": \"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("name")))
                .andExpect(jsonPath("$.errors", hasKey("targetAmount")))
                .andExpect(jsonPath("$.errors", hasKey("currency")));
        verifyNoInteractions(profiles);
    }

    @Test
    void missingGoalIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new GoalNotFoundException(id)).when(profiles).deleteGoal(eq(id));

        mvc.perform(delete("/api/v1/investor/goals/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("GOAL_NOT_FOUND")));
    }

    @Test
    void createsGoals() throws Exception {
        mvc.perform(post("/api/v1/investor/goals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Mua nhà\", \"targetAmount\": 3000000000, \"targetDate\": \"2032-01-01\"}"))
                .andExpect(status().isCreated());
        verify(profiles).createGoal(eq(new com.investary.investor.application.GoalCommand("Mua nhà", null,
                new BigDecimal("3000000000"), java.time.LocalDate.parse("2032-01-01"), null, null, null)));
    }
}
