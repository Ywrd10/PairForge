package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.pairforge.api.execution.ExecutionDtos.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class ExecutionController {
    private final ExecutionService service;
    public ExecutionController(ExecutionService service) { this.service = service; }
    @PostMapping("/api/rooms/{roomId}/executions")
    ResponseEntity<Receipt> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId, @RequestBody JsonNode body) {
        var receipt = service.submit(UUID.fromString(jwt.getSubject()), roomId, body);
        return ResponseEntity.accepted().location(URI.create("/api/executions/" + receipt.executionId())).body(receipt);
    }
    @GetMapping("/api/rooms/{roomId}/executions")
    History history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.history(UUID.fromString(jwt.getSubject()), roomId, page, size);
    }
    @GetMapping("/api/executions/{executionId}")
    Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID executionId) {
        return service.detail(UUID.fromString(jwt.getSubject()), executionId);
    }
}
