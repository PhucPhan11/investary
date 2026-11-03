package com.investary.portfolio.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

import com.investary.common.web.PageResponse;
import com.investary.portfolio.application.PortfolioCommands.PortfolioCommand;
import com.investary.portfolio.application.PortfolioCommands.TransactionCommand;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioService;
import com.investary.portfolio.application.PortfolioViews.Allocation;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;
import com.investary.portfolio.application.PortfolioViews.PortfolioView;
import com.investary.portfolio.application.PortfolioViews.TransactionView;
import com.investary.portfolio.domain.TransactionType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Portfolios, their transactions and the computed holdings, summary and allocation. Holdings are
 * derived from transactions on every request; market values come only from manual price observations.
 */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

    private final PortfolioQueryService queries;
    private final PortfolioService portfolios;

    public PortfolioController(PortfolioQueryService queries, PortfolioService portfolios) {
        this.queries = queries;
        this.portfolios = portfolios;
    }

    @GetMapping
    public List<PortfolioView> list() {
        return queries.portfolios();
    }

    @GetMapping("/{id}")
    public PortfolioView get(@PathVariable UUID id) {
        return queries.portfolio(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioView create(@Valid @RequestBody PortfolioRequest request) {
        return portfolios.create(request.command());
    }

    @PutMapping("/{id}")
    public PortfolioView update(@PathVariable UUID id, @Valid @RequestBody PortfolioRequest request) {
        return portfolios.update(id, request.command());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        portfolios.delete(id);
    }

    @GetMapping("/{id}/summary")
    public PortfolioSummary summary(@PathVariable UUID id) {
        return queries.summary(id);
    }

    @GetMapping("/{id}/holdings")
    public List<HoldingView> holdings(@PathVariable UUID id) {
        return queries.holdings(id);
    }

    @GetMapping("/{id}/allocation")
    public Allocation allocation(@PathVariable UUID id) {
        return queries.allocation(id);
    }

    @GetMapping("/{id}/transactions")
    public PageResponse<TransactionView> transactions(@PathVariable UUID id,
            @PageableDefault(size = 50, sort = {"tradeDate", "createdAt"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(queries.transactions(id, pageable), Function.identity());
    }

    @PostMapping("/{id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView addTransaction(@PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
        return portfolios.addTransaction(id, request.command());
    }

    @PutMapping("/{id}/transactions/{transactionId}")
    public TransactionView updateTransaction(@PathVariable UUID id, @PathVariable UUID transactionId,
                                             @Valid @RequestBody TransactionRequest request) {
        return portfolios.updateTransaction(id, transactionId, request.command());
    }

    @DeleteMapping("/{id}/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable UUID id, @PathVariable UUID transactionId) {
        portfolios.deleteTransaction(id, transactionId);
    }

    public record PortfolioRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String baseCurrency) {

        PortfolioCommand command() {
            return new PortfolioCommand(name, description, baseCurrency);
        }
    }

    /**
     * @param price unit price; for OPENING_POSITION the cost per unit the position was acquired at
     */
    public record TransactionRequest(
            @NotNull UUID instrumentId,
            @NotNull TransactionType type,
            @NotNull LocalDate tradeDate,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 6) BigDecimal price,
            @DecimalMin("0") @Digits(integer = 18, fraction = 6) BigDecimal fees,
            @DecimalMin("0") @Digits(integer = 18, fraction = 6) BigDecimal taxes,
            @Size(max = 2000) String notes) {

        TransactionCommand command() {
            return new TransactionCommand(instrumentId, type, tradeDate, quantity, price, fees, taxes, notes);
        }
    }
}
