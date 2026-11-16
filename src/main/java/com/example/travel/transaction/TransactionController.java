package com.example.travel.transaction;

import com.example.travel.common.BadRequestException;
import com.example.travel.web.Page;
import com.example.travel.web.PageParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP handlers only: parse, delegate, shape the response. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private static final Set<String> IMMUTABLE_FIELDS =
            Set.of("id", "type", "external_id", "currency", "created_at", "updated_at");

    private final TransactionService service;
    private final ObjectMapper mapper;

    public TransactionController(TransactionService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Transaction> create(@Valid @RequestBody CreateTransactionRequest request) {
        Transaction created = service.create(request);
        return ResponseEntity.created(URI.create("/transactions/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public Transaction get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<Transaction> list(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return service.list(PageParams.of(limit, offset));
    }

    @PatchMapping("/{id}")
    public Transaction patch(@PathVariable String id, @RequestBody JsonNode body) {
        return service.patch(id, parsePatch(body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** Rejects attempts to change immutable fields instead of silently ignoring them. */
    private PatchTransactionRequest parsePatch(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BadRequestException("Request body must be a JSON object");
        }
        for (String field : IMMUTABLE_FIELDS) {
            if (body.has(field)) {
                throw new BadRequestException("Field '" + field + "' cannot be changed");
            }
        }
        try {
            return mapper.treeToValue(body, PatchTransactionRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Invalid request body: " + e.getOriginalMessage());
        }
    }
}
