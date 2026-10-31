package com.investary.automation.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.automation.application.SourceRuleService;
import com.investary.automation.application.SourceRuleService.SourceRuleCommand;
import com.investary.automation.application.SourceRuleService.SourceRuleView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Brokerage source rules: which verified senders Gmail sync may import. Local-only.
 */
@RestController
@RequestMapping("/api/v1/automation/sources")
public class SourceRuleController {

    private final SourceRuleService rules;

    public SourceRuleController(SourceRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<SourceRuleView> list() {
        return rules.list();
    }

    @GetMapping("/{id}")
    public SourceRuleView get(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping
    public ResponseEntity<SourceRuleView> create(@Valid @RequestBody SourceRuleRequest request) {
        SourceRuleView created = rules.create(request.command());
        return ResponseEntity.created(URI.create("/api/v1/automation/sources/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public SourceRuleView update(@PathVariable UUID id, @Valid @RequestBody SourceRuleRequest request) {
        return rules.update(id, request.command());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rules.delete(id);
    }

    /**
     * Lists may hold blank entries (ignored); detailed checks (addresses, domains, Gmail query) are in the service.
     */
    public record SourceRuleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) List<@Size(max = 320) String> senderAddresses,
            @Size(max = 100) List<@Size(max = 253) String> senderDomains,
            @Size(max = 40) List<@Size(max = 200) String> subjectKeywords,
            @Size(max = 500) String gmailQuery,
            Boolean enabled,
            Boolean autoImport,
            Boolean autoPdfDownload) {

        SourceRuleCommand command() {
            return new SourceRuleCommand(name, senderAddresses, senderDomains, subjectKeywords, gmailQuery, enabled,
                    autoImport, autoPdfDownload);
        }
    }
}
