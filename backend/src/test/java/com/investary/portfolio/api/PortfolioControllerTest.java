package com.investary.portfolio.api;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.portfolio.application.HoldingCalculator;
import com.investary.portfolio.application.PortfolioExceptions.CurrencyMismatchException;
import com.investary.portfolio.application.PortfolioExceptions.InsufficientHoldingsException;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioService;
import com.investary.portfolio.domain.TransactionType;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

    private static final UUID PORTFOLIO = new UUID(1, 1);
    private static final UUID INSTRUMENT = new UUID(2, 2);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortfolioQueryService queries;

    @MockitoBean
    PortfolioService portfolios;

    @Test
    void rejectsInvalidTransactions() throws Exception {
        mvc.perform(post("/api/v1/portfolios/{id}/transactions", PORTFOLIO).contentType(MediaType.APPLICATION_JSON).content("""
                        {"instrumentId": null, "type": "BUY", "tradeDate": null, "quantity": 0, "price": -1, "fees": -5}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors", hasKey("instrumentId")))
                .andExpect(jsonPath("$.errors", hasKey("tradeDate")))
                .andExpect(jsonPath("$.errors", hasKey("quantity")))
                .andExpect(jsonPath("$.errors", hasKey("price")))
                .andExpect(jsonPath("$.errors", hasKey("fees")));
        verifyNoInteractions(portfolios);
    }

    @Test
    void oversellIsReportedWithTheConflictingTrade() throws Exception {
        HoldingCalculator.Trade trade = new HoldingCalculator.Trade(UUID.randomUUID(), INSTRUMENT, "FPT", TransactionType.SELL,
                LocalDate.parse("2026-03-01"), Instant.EPOCH, new BigDecimal("150"), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
        HoldingCalculator.Oversell oversell = null;
        try {
            HoldingCalculator.calculate(java.util.List.of(trade));
        } catch (HoldingCalculator.Oversell e) {
            oversell = e;
        }
        when(portfolios.addTransaction(eq(PORTFOLIO), any())).thenThrow(new InsufficientHoldingsException(oversell));

        mvc.perform(post("/api/v1/portfolios/{id}/transactions", PORTFOLIO).contentType(MediaType.APPLICATION_JSON).content("""
                        {"instrumentId": "%s", "type": "SELL", "tradeDate": "2026-03-01", "quantity": 150, "price": 10}""".formatted(INSTRUMENT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_HOLDINGS")))
                .andExpect(jsonPath("$.symbol", is("FPT")))
                .andExpect(jsonPath("$.tradeDate", is("2026-03-01")))
                .andExpect(jsonPath("$.available", is(0)));
    }

    @Test
    void currencyMismatchIsUnprocessable() throws Exception {
        when(portfolios.addTransaction(eq(PORTFOLIO), any())).thenThrow(new CurrencyMismatchException("AAPL", "USD", "VND"));

        mvc.perform(post("/api/v1/portfolios/{id}/transactions", PORTFOLIO).contentType(MediaType.APPLICATION_JSON).content("""
                        {"instrumentId": "%s", "type": "BUY", "tradeDate": "2026-03-01", "quantity": 1, "price": 10}""".formatted(INSTRUMENT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));
    }

    @Test
    void rejectsInvalidPortfolios() throws Exception {
        mvc.perform(post("/api/v1/portfolios").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \" \", \"baseCurrency\": \"DONG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("name")))
                .andExpect(jsonPath("$.errors", hasKey("baseCurrency")));
    }
}
