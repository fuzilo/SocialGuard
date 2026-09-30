package com.unesp.SocialGuard.controller;

import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResponse;
import com.unesp.SocialGuard.dto.BatchUpdateRequest;
import com.unesp.SocialGuard.service.BatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/batches")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;

    @PostMapping
    public ResponseEntity<BatchResponse> create(@Valid @RequestBody BatchCreateRequest request) {
        BatchResponse created = batchService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.batchId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<BatchResponse> findAll(@RequestParam(required = false) String companyId,
                                       @RequestParam(required = false) BatchStatus status) {
        return batchService.findAll(companyId, status);
    }

    @GetMapping("/{id}")
    public BatchResponse findById(@PathVariable String id) {
        return batchService.findById(id);
    }

    @PutMapping("/{id}")
    public BatchResponse update(@PathVariable String id, @Valid @RequestBody BatchUpdateRequest request) {
        return batchService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        batchService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
