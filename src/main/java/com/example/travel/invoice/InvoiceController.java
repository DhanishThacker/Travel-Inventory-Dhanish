package com.example.travel.invoice;

import com.example.travel.web.Page;
import com.example.travel.web.PageParams;
import java.net.URI;
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

/** HTTP handlers only. */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Invoice> create(@RequestBody InvoiceRequest request) {
        Invoice invoice = service.create(request);
        return ResponseEntity.created(URI.create("/invoices/" + invoice.id())).body(invoice);
    }

    @GetMapping("/{id}")
    public Invoice get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<InvoiceSummary> list(@RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return service.list(PageParams.of(limit, offset));
    }

    @PatchMapping("/{id}")
    public Invoice replace(@PathVariable String id, @RequestBody InvoiceRequest request) {
        return service.replace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
