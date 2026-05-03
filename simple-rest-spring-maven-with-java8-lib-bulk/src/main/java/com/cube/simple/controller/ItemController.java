package com.cube.simple.controller;

import com.cube.simple.dto.ItemRequest;
import com.cube.simple.dto.ItemResponse;
import com.cube.simple.model.Item;
import com.cube.simple.service.ItemService;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private static final double DELAY_PROBABILITY = 0.1;
    private static final long   DELAY_MIN_MS      = 1_000L;
    private static final long   DELAY_MAX_MS      = 5_000L;

    @Value("${opensearch.name:app-default-rest}")
    private String appName;

    private final ItemService itemService;

    @GetMapping
    public ResponseEntity<ItemResponse> findAll(
            @RequestParam(defaultValue = "0")  int offset,
            @RequestParam(defaultValue = "20") int limit) {
        applyRandomDelay();
        List<Item> items = itemService.findAll(offset, limit);
        log.info("OS : Just do api by [{}] GET /api/items → {} 건", appName, items.size());
        return ResponseEntity.ok(ItemResponse.ok("조회 성공", items));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> findById(@PathVariable Long id) {
        applyRandomDelay();
        return itemService.findById(id)
                .map(item -> {
                    log.info("OS : Just do api by [{}] GET /api/items/{}", appName, id);
                    return ResponseEntity.ok(ItemResponse.ok("조회 성공", item));
                })
                .orElseGet(() -> {
                    log.warn("OS : Just do api by [{}] GET /api/items/{} → 404", appName, id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ItemResponse.fail("Item not found: " + id));
                });
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@RequestBody @Valid ItemRequest request) {
        Item item = itemService.create(request.toItem());
        log.info("OS : Just do api by [{}] POST /api/items → id={}", appName, item.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ItemResponse.ok("등록 성공", item));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(@PathVariable Long id,
                                               @RequestBody @Valid ItemRequest request) {
        return itemService.update(id, request.toItem())
                .map(item -> {
                    log.info("OS : Just do api by [{}] PUT /api/items/{}", appName, id);
                    return ResponseEntity.ok(ItemResponse.ok("수정 성공", item));
                })
                .orElseGet(() -> {
                    log.warn("OS : Just do api by [{}] PUT /api/items/{} → 404", appName, id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ItemResponse.fail("Item not found: " + id));
                });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ItemResponse> delete(@PathVariable Long id) {
        if (!itemService.delete(id)) {
            log.warn("OS : Just do api by [{}] DELETE /api/items/{} → 404", appName, id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ItemResponse.fail("Item not found: " + id));
        }
        log.info("OS : Just do api by [{}] DELETE /api/items/{}", appName, id);
        return ResponseEntity.ok(ItemResponse.ok("삭제 성공", null));
    }

    /** 10% 확률로 1~5초 랜덤 지연. MDC에 delayed / delay_ms 기록 → OpenSearch 추적 가능. */
    private void applyRandomDelay() {
        if (ThreadLocalRandom.current().nextDouble() < DELAY_PROBABILITY) {
            long delayMs = ThreadLocalRandom.current().nextLong(DELAY_MIN_MS, DELAY_MAX_MS + 1);
            MDC.put("delayed",  "true");
            MDC.put("delay_ms", String.valueOf(delayMs));
            log.debug("Simulated delay: {}ms", delayMs);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
