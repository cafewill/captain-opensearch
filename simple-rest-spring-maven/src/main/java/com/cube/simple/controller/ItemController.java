package com.cube.simple.controller;

import com.cube.simple.dto.ItemRequest;
import com.cube.simple.dto.ItemResponse;
import com.cube.simple.model.Item;
import com.cube.simple.service.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @GetMapping
    public ResponseEntity<ItemResponse> findAll() {
        try {
            List<Item> items = itemService.findAll();
            log.info("GET /api/items → {} 건", items.size());
            return ResponseEntity.ok(ItemResponse.ok("조회 성공", items));
        } catch (Exception e) {
            log.error("GET /api/items error", e);
            return ResponseEntity.internalServerError().body(ItemResponse.fail(e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> findById(@PathVariable Long id) {
        try {
            Item item = itemService.findById(id);
            if (item == null) {
                log.warn("GET /api/items/{} → 404", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ItemResponse.fail("Item not found: " + id));
            }
            log.info("GET /api/items/{}", id);
            return ResponseEntity.ok(ItemResponse.ok("조회 성공", item));
        } catch (Exception e) {
            log.error("GET /api/items/{} error", id, e);
            return ResponseEntity.internalServerError().body(ItemResponse.fail(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@RequestBody @Valid ItemRequest request) {
        try {
            Item item = itemService.create(request.toItem());
            log.info("POST /api/items → id={}", item.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(ItemResponse.ok("등록 성공", item));
        } catch (Exception e) {
            log.error("POST /api/items error", e);
            return ResponseEntity.internalServerError().body(ItemResponse.fail(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(@PathVariable Long id,
                                               @RequestBody @Valid ItemRequest request) {
        try {
            Item item = itemService.update(id, request.toItem());
            if (item == null) {
                log.warn("PUT /api/items/{} → 404", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ItemResponse.fail("Item not found: " + id));
            }
            log.info("PUT /api/items/{}", id);
            return ResponseEntity.ok(ItemResponse.ok("수정 성공", item));
        } catch (Exception e) {
            log.error("PUT /api/items/{} error", id, e);
            return ResponseEntity.internalServerError().body(ItemResponse.fail(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ItemResponse> delete(@PathVariable Long id) {
        try {
            if (!itemService.delete(id)) {
                log.warn("DELETE /api/items/{} → 404", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ItemResponse.fail("Item not found: " + id));
            }
            log.info("DELETE /api/items/{}", id);
            return ResponseEntity.ok(ItemResponse.ok("삭제 성공", null));
        } catch (Exception e) {
            log.error("DELETE /api/items/{} error", id, e);
            return ResponseEntity.internalServerError().body(ItemResponse.fail(e.getMessage()));
        }
    }
}
