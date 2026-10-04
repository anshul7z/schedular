package com.data.schedular.api;

import com.data.schedular.api.dto.JobRequest;
import com.data.schedular.api.dto.JobResponse;
import com.data.schedular.api.dto.PageResponse;
import com.data.schedular.api.dto.RunResponse;
import com.data.schedular.service.JobService;
import com.data.schedular.service.RunService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final RunService runs;

    public JobController(JobService jobs, RunService runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    @GetMapping
    public List<JobResponse> list() {
        return jobs.list();
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable Long id) {
        return jobs.get(id);
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody JobRequest request) {
        JobResponse created = jobs.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public JobResponse update(@PathVariable Long id, @Valid @RequestBody JobRequest request) {
        return jobs.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        jobs.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Starts a run now. Returns 202 with the new run; poll its Location for progress. */
    @PostMapping("/{id}/run")
    public ResponseEntity<RunResponse> run(@PathVariable Long id) {
        RunResponse run = jobs.runNow(id);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/runs/{runId}").buildAndExpand(run.id()).toUri();
        return ResponseEntity.accepted().location(location).body(run);
    }

    @PostMapping("/{id}/pause")
    public JobResponse pause(@PathVariable Long id) {
        return jobs.pause(id);
    }

    @PostMapping("/{id}/resume")
    public JobResponse resume(@PathVariable Long id) {
        return jobs.resume(id);
    }

    /** Clears saved progress so the next run starts from the beginning of every collection. */
    @PostMapping("/{id}/reset-checkpoint")
    public ResponseEntity<Void> resetCheckpoint(@PathVariable Long id) {
        jobs.resetCheckpoints(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/runs")
    public PageResponse<RunResponse> runs(@PathVariable Long id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return runs.forJob(id, page, size);
    }
}
