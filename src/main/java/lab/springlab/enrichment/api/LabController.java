package lab.springlab.enrichment.api;

import lab.springlab.enrichment.domain.EnrichmentJob;
import lab.springlab.enrichment.domain.EnrichmentReport;
import lab.springlab.enrichment.domain.EnrichmentStatus;
import lab.springlab.enrichment.dto.DispatchResult;
import lab.springlab.enrichment.dto.EnrichmentJobView;
import lab.springlab.enrichment.dto.SeedResponse;
import lab.springlab.enrichment.repository.EnrichmentJobRepository;
import lab.springlab.enrichment.service.EnrichmentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lab")
public class LabController {

    private static final Logger log = LoggerFactory.getLogger(LabController.class);
    private final EnrichmentJobRepository repository;
    private final EnrichmentService enrichmentService;

    public LabController(EnrichmentJobRepository repository, EnrichmentService enrichmentService) {
        this.repository = repository;
        this.enrichmentService = enrichmentService;
    }

    @PostMapping("/seed")
    public ResponseEntity<SeedResponse> seed(@RequestParam(defaultValue = "100") int items) {
        log.info("Seeding enrichment jobs items={}", items);
        List<EnrichmentJob> jobs = IntStream.range(0, items)
                .mapToObj(index -> new EnrichmentJob(UUID.randomUUID(),
                        "{\"jobIndex\":" + index + ",\"seededAt\":\"" + Instant.now() + "\"}"))
                .toList();
        repository.saveAll(jobs);
        return ResponseEntity.ok(new SeedResponse(jobs.size()));
    }

    @PostMapping("/run")
    public ResponseEntity<DispatchResult> run() {
        log.info("Dispatching enrichment jobs async");
        return ResponseEntity.ok(enrichmentService.dispatchPendingJobs());
    }

    @PostMapping("/run-sync")
    public ResponseEntity<DispatchResult> runSync() {
        log.info("Dispatching enrichment jobs sync");
        return ResponseEntity.ok(enrichmentService.dispatchPendingJobsSync());
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<EnrichmentJobView>> jobs(@RequestParam(required = false) EnrichmentStatus status,
                                                        @RequestParam(defaultValue = "50") int limit) {
        log.info("Listing jobs status={} limit={}", status, limit);
        List<EnrichmentJob> jobs;
        if (status == null) {
            jobs = repository.findAll(PageRequest.of(0, limit)).getContent();
        } else {
            jobs = repository.findByStatus(status, PageRequest.of(0, limit));
        }
        List<EnrichmentJobView> views = jobs.stream()
                .map(EnrichmentJobView::from)
                .toList();
        return ResponseEntity.ok(views);
    }

    @GetMapping("/report")
    public ResponseEntity<EnrichmentReport> report() {
        log.info("Reporting enrichment metrics");
        return ResponseEntity.ok(enrichmentService.report());
    }

}
