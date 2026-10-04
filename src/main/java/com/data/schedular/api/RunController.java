package com.data.schedular.api;

import com.data.schedular.api.dto.DeadLetterResponse;
import com.data.schedular.api.dto.PageResponse;
import com.data.schedular.api.dto.RunResponse;
import com.data.schedular.service.RunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable Long runId) {
        return runs.get(runId);
    }

    /** Requests cancellation; the run stops after its current batch. Returns 202 with the run as it is now. */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RunResponse> cancel(@PathVariable Long runId) {
        return ResponseEntity.accepted().body(runs.cancel(runId));
    }

    @GetMapping("/{runId}/dead-letters")
    public PageResponse<DeadLetterResponse> deadLetters(@PathVariable Long runId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "50") int size) {
        return runs.deadLetters(runId, page, size);
    }
}
