package com.investary.portfolio.api;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.portfolio.application.InstrumentService;
import com.investary.portfolio.application.PortfolioCommands.InstrumentCommand;
import com.investary.portfolio.application.PortfolioCommands.PriceCommand;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.InstrumentView;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.domain.Exchange;
import com.investary.portfolio.domain.InstrumentType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Instruments (entered by the user; no exchange master data) and their manual price observations.
 */
@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final PortfolioQueryService queries;
    private final InstrumentService instruments;

    public InstrumentController(PortfolioQueryService queries, InstrumentService instruments) {
        this.queries = queries;
        this.instruments = instruments;
    }

    @GetMapping
    public List<InstrumentView> list(@RequestParam(required = false) @Size(max = 100) String q) {
        return queries.instruments(q);
    }

    @GetMapping("/{id}")
    public InstrumentView get(@PathVariable UUID id) {
        return queries.instrument(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentView create(@Valid @RequestBody InstrumentRequest request) {
        return instruments.create(request.command());
    }

    @PutMapping("/{id}")
    public InstrumentView update(@PathVariable UUID id, @Valid @RequestBody InstrumentRequest request) {
        return instruments.update(id, request.command());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        instruments.delete(id);
    }

    @GetMapping("/{id}/prices")
    public List<PriceView> prices(@PathVariable UUID id) {
        return queries.prices(id);
    }

    @PostMapping("/{id}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceView addPrice(@PathVariable UUID id, @Valid @RequestBody PriceRequest request) {
        return instruments.addPrice(id, new PriceCommand(request.price(), request.asOf(), request.source(), request.note()));
    }

    @DeleteMapping("/{id}/prices/{priceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrice(@PathVariable UUID id, @PathVariable UUID priceId) {
        instruments.deletePrice(id, priceId);
    }

    public record InstrumentRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$", message = "must be a ticker of up to 20 letters or digits")
            String symbol,
            @NotBlank @Size(max = 300) String name,
            @NotNull InstrumentType instrumentType,
            Exchange exchange,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code") String currency,
            @Size(max = 10) List<@NotBlank @Size(max = 200) String> aliases) {

        InstrumentCommand command() {
            return new InstrumentCommand(symbol, name, instrumentType, exchange, currency, aliases);
        }
    }

    /**
     * @param source where the value was read, e.g. "Bảng giá HOSE" or "NAV công bố"; required
     */
    public record PriceRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6) BigDecimal price,
            @NotNull LocalDate asOf,
            @NotBlank @Size(max = 200) String source,
            @Size(max = 1000) String note) {
    }
}
