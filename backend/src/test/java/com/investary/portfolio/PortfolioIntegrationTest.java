package com.investary.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.investary.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio, instrument, price, profile and goal APIs end to end on PostgreSQL. All values are synthetic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PortfolioIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @BeforeEach
    void clean() {
        jdbc.update("delete from portfolios");
        jdbc.update("delete from instruments");
        jdbc.update("delete from investor_goals");
        jdbc.update("delete from investor_profile");
    }

    @Test
    void transactionsDriveHoldingsValuationAndAllocation() throws Exception {
        String portfolio = id(create("/api/v1/portfolios", "{\"name\": \"Danh mục dài hạn\"}"));
        String fpt = id(create("/api/v1/instruments", """
                {"symbol": "fpt", "name": "Công ty Cổ phần FPT (tổng hợp)", "instrumentType": "STOCK", "exchange": "HOSE",
                 "aliases": ["FPT Corp", " FPT Corp "]}"""));
        String etf = id(create("/api/v1/instruments", """
                {"symbol": "E1VFVN30", "name": "Quỹ ETF VN30 (tổng hợp)", "instrumentType": "ETF"}"""));

        transaction(portfolio, fpt, "OPENING_POSITION", today.minusDays(100), "100", "80000", "0").andExpect(status().isCreated());
        transaction(portfolio, fpt, "BUY", today.minusDays(50), "100", "100000", "150").andExpect(status().isCreated());
        transaction(portfolio, etf, "BUY", today.minusDays(40), "1000", "20000", "0").andExpect(status().isCreated());
        transaction(portfolio, fpt, "SELL", today.minusDays(10), "50", "120000", "60").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/portfolios/{id}/holdings", portfolio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].symbol", is("E1VFVN30")))
                .andExpect(jsonPath("$[1].symbol", is("FPT")))
                .andExpect(jsonPath("$[1].quantity", is(150)))
                // cost 18 000 150 for 200 → avg 90 000.75; 50 sold removes 4 500 037.5
                .andExpect(jsonPath("$[1].costBasis", is(13500113)))
                .andExpect(jsonPath("$[1].averageCost", is(90000.75)))
                .andExpect(jsonPath("$[1].valuationStatus", is("UNAVAILABLE")))
                .andExpect(jsonPath("$[1].marketValue", nullValue()))
                .andExpect(jsonPath("$[1].unrealizedPnl", nullValue()));

        mvc.perform(get("/api/v1/portfolios/{id}/summary", portfolio))
                .andExpect(jsonPath("$.valuationStatus", is("UNAVAILABLE")))
                .andExpect(jsonPath("$.totalMarketValue", nullValue()))
                .andExpect(jsonPath("$.realizedPnl", is(1499903)))
                .andExpect(jsonPath("$.transactionCount", is(4)));

        create("/api/v1/instruments/" + fpt + "/prices", "{\"price\": 110000, \"asOf\": \"" + today.minusDays(1)
                + "\", \"source\": \"Bảng giá HOSE (nhập tay)\"}");
        mvc.perform(get("/api/v1/portfolios/{id}/summary", portfolio))
                .andExpect(jsonPath("$.valuationStatus", is("PARTIAL")))
                .andExpect(jsonPath("$.totalMarketValue", nullValue()))
                .andExpect(jsonPath("$.pricedMarketValue", is(16500000)));
        mvc.perform(get("/api/v1/portfolios/{id}/allocation", portfolio))
                .andExpect(jsonPath("$.costBasis.basis", is("COST_BASIS")))
                .andExpect(jsonPath("$.marketValue", nullValue()))
                .andExpect(jsonPath("$.costBasis.byInstrumentType", hasSize(2)));

        create("/api/v1/instruments/" + etf + "/prices", "{\"price\": 21000, \"asOf\": \"" + today.minusDays(20)
                + "\", \"source\": \"NAV công bố (nhập tay)\"}");
        mvc.perform(get("/api/v1/portfolios/{id}/summary", portfolio))
                .andExpect(jsonPath("$.valuationStatus", is("STALE")))
                .andExpect(jsonPath("$.totalMarketValue", is(37500000)));
        mvc.perform(get("/api/v1/portfolios/{id}/allocation", portfolio))
                .andExpect(jsonPath("$.marketValue.basis", is("MARKET_VALUE")));
    }

    @Test
    void invalidHistoryChangesAreRejectedAndRolledBack() throws Exception {
        String portfolio = id(create("/api/v1/portfolios", "{\"name\": \"Thử nghiệm\"}"));
        String scs = id(create("/api/v1/instruments", "{\"symbol\": \"SCS\", \"name\": \"SCS (tổng hợp)\", \"instrumentType\": \"STOCK\"}"));
        String buy = id(json.readTree(transaction(portfolio, scs, "BUY", today.minusDays(30), "100", "50000", "0")
                .andReturn().getResponse().getContentAsString()));
        transaction(portfolio, scs, "SELL", today.minusDays(10), "80", "60000", "0").andExpect(status().isCreated());

        transaction(portfolio, scs, "SELL", today.minusDays(5), "30", "60000", "0")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_HOLDINGS")))
                .andExpect(jsonPath("$.available", is(20)));

        // Lowering the earlier buy below the later sell is rejected; the stored buy keeps its quantity.
        mvc.perform(put("/api/v1/portfolios/{p}/transactions/{t}", portfolio, buy).contentType(MediaType.APPLICATION_JSON)
                        .content(body(scs, "BUY", today.minusDays(30), "50", "50000", "0")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(delete("/api/v1/portfolios/{p}/transactions/{t}", portfolio, buy))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("select quantity from portfolio_transactions where id = ?::uuid", java.math.BigDecimal.class, buy))
                .isEqualByComparingTo("100");

        // Moving the buy after the sell is rejected too: sells are checked on their own date.
        mvc.perform(put("/api/v1/portfolios/{p}/transactions/{t}", portfolio, buy).contentType(MediaType.APPLICATION_JSON)
                        .content(body(scs, "BUY", today.minusDays(1), "100", "50000", "0")))
                .andExpect(status().isUnprocessableContent());

        // A valid edit recalculates.
        mvc.perform(put("/api/v1/portfolios/{p}/transactions/{t}", portfolio, buy).contentType(MediaType.APPLICATION_JSON)
                        .content(body(scs, "BUY", today.minusDays(30), "200", "50000", "0")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/portfolios/{id}/holdings", portfolio)).andExpect(jsonPath("$[0].quantity", is(120)));

        mvc.perform(delete("/api/v1/instruments/{id}", scs))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INSTRUMENT_IN_USE")));
        mvc.perform(get("/api/v1/portfolios/{id}/transactions", portfolio))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].type", is("SELL")));
    }

    @Test
    void duplicatesAndCurrencyMismatchesAreRejected() throws Exception {
        String portfolio = id(create("/api/v1/portfolios", "{\"name\": \"VND\"}"));
        mvc.perform(post("/api/v1/portfolios").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"vnd\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_PORTFOLIO")));
        String usd = id(create("/api/v1/instruments", "{\"symbol\": \"XYZ\", \"name\": \"Synthetic USD\", \"instrumentType\": \"OTHER\", \"currency\": \"USD\"}"));
        mvc.perform(post("/api/v1/instruments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\": \"xyz\", \"name\": \"Again\", \"instrumentType\": \"OTHER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_INSTRUMENT")));
        transaction(portfolio, usd, "BUY", today, "1", "10", "0")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("CURRENCY_MISMATCH")));
        String vnd = id(create("/api/v1/instruments", "{\"symbol\": \"ABC\", \"name\": \"Synthetic VND\", \"instrumentType\": \"STOCK\"}"));
        transaction(portfolio, vnd, "BUY", today.plusDays(2), "1", "10", "0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.errors.tradeDate", is("must not be in the future")));
        String price = "{\"price\": 10, \"asOf\": \"" + today + "\", \"source\": \"Synthetic\"}";
        create("/api/v1/instruments/" + usd + "/prices", price);
        mvc.perform(post("/api/v1/instruments/{id}/prices", usd).contentType(MediaType.APPLICATION_JSON).content(price))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_PRICE")));
    }

    @Test
    void profileAndGoals() throws Exception {
        mvc.perform(get("/api/v1/investor/profile")).andExpect(jsonPath("$.configured", is(false)));
        mvc.perform(put("/api/v1/investor/profile").contentType(MediaType.APPLICATION_JSON).content("""
                        {"experienceLevel": "INTERMEDIATE", "riskTolerance": "MODERATE", "investmentHorizon": "LONG",
                         "preferredInstruments": ["STOCK", "ETF", "STOCK"], "restrictions": "Không dùng margin"}"""))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/investor/profile").contentType(MediaType.APPLICATION_JSON).content("{\"riskTolerance\": \"LOW\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from investor_profile", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/investor/profile"))
                .andExpect(jsonPath("$.riskTolerance", is("LOW")))
                .andExpect(jsonPath("$.preferredInstruments", hasSize(0)));

        String low = id(create("/api/v1/investor/goals", "{\"name\": \"Du lịch\", \"priority\": \"LOW\"}"));
        create("/api/v1/investor/goals", "{\"name\": \"Hưu trí\", \"priority\": \"HIGH\", \"targetAmount\": 5000000000, \"targetDate\": \"2045-01-01\"}");
        mvc.perform(get("/api/v1/investor/goals"))
                .andExpect(jsonPath("$[0].name", is("Hưu trí")))
                .andExpect(jsonPath("$[0].currency", is("VND")))
                .andExpect(jsonPath("$[1].name", is("Du lịch")));
        mvc.perform(delete("/api/v1/investor/goals/{id}", low)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/investor/goals")).andExpect(jsonPath("$", hasSize(1)));
    }

    private ResultActions transaction(String portfolio, String instrument, String type, LocalDate date, String quantity, String price,
                                      String fees) throws Exception {
        return mvc.perform(post("/api/v1/portfolios/{id}/transactions", portfolio).contentType(MediaType.APPLICATION_JSON)
                .content(body(instrument, type, date, quantity, price, fees)));
    }

    private static String body(String instrument, String type, LocalDate date, String quantity, String price, String fees) {
        return """
                {"instrumentId": "%s", "type": "%s", "tradeDate": "%s", "quantity": %s, "price": %s, "fees": %s}"""
                .formatted(instrument, type, date, quantity, price, fees);
    }

    private JsonNode create(String path, String body) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static String id(JsonNode node) {
        return node.path("id").asString();
    }
}
