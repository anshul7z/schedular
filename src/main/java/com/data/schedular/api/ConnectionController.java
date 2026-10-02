package com.data.schedular.api;

import com.data.schedular.api.dto.ConnectionRequest;
import com.data.schedular.api.dto.ConnectionResponse;
import com.data.schedular.domain.ConnectionDef;
import com.data.schedular.service.ConnectionService;
import com.data.schedular.service.connectivity.ConnectionTestResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectionResponse> list() {
        return service.list().stream().map(ConnectionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ConnectionResponse get(@PathVariable Long id) {
        return ConnectionResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> create(@Valid @RequestBody ConnectionRequest request) {
        ConnectionDef created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(ConnectionResponse.from(created));
    }

    @PutMapping("/{id}")
    public ConnectionResponse update(@PathVariable Long id, @Valid @RequestBody ConnectionRequest request) {
        return ConnectionResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Tests a saved connection. A failed connection is reported in the body with {@code success=false}. */
    @PostMapping("/{id}/test")
    public ConnectionTestResult test(@PathVariable Long id) {
        return service.test(id);
    }

    /** Tests connection settings without saving them. */
    @PostMapping("/test")
    public ConnectionTestResult testUnsaved(@Valid @RequestBody ConnectionRequest request) {
        return service.test(request);
    }

    /** Lists collections (MongoDB) or tables (SQL) reachable through the connection. */
    @GetMapping("/{id}/collections")
    public List<String> collections(@PathVariable Long id) {
        return service.listObjects(id);
    }
}
