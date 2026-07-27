package com.melon.app.controller;

import com.melon.app.config.RuntimeExecutors;
import com.melon.app.service.TokenUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * melonPaw frontend-compatible token usage API aliases.
 */
@RestController
@RequestMapping("/api/token-usage")
public class TokenUsageCompatController {

    private final TokenUsageService tokenUsageService;
    private final RuntimeExecutors executors;

    public TokenUsageCompatController(TokenUsageService tokenUsageService) {
        this(tokenUsageService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TokenUsageCompatController(TokenUsageService tokenUsageService, RuntimeExecutors executors) {
        this.tokenUsageService = tokenUsageService;
        this.executors = executors;
    }

    @GetMapping
    public Mono<ResponseEntity<Object>> summary(@RequestParam(value = "start_date", required = false) String startDate,
                                                @RequestParam(value = "end_date", required = false) String endDate,
                                                @RequestParam(value = "provider", required = false) String provider,
                                                @RequestParam(value = "model", required = false) String model) {
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) tokenUsageService.getSummary(startDate, endDate, provider, model)))
                .subscribeOn(webBlockingScheduler());
    }

    @GetMapping("/details")
    public Mono<ResponseEntity<Object>> details(@RequestParam(value = "start_date", required = false) String startDate,
                                                @RequestParam(value = "end_date", required = false) String endDate,
                                                @RequestParam(value = "provider", required = false) String provider,
                                                @RequestParam(value = "model", required = false) String model) {
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) tokenUsageService.getDetails(startDate, endDate, provider, model)))
                .subscribeOn(webBlockingScheduler());
    }

    private reactor.core.scheduler.Scheduler webBlockingScheduler() {
        return executors == null ? Schedulers.boundedElastic() : executors.webBlockingScheduler();
    }
}
