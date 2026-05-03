package com.cube.simple.service;

import com.cube.simple.mapper.ItemMapper;
import com.cube.simple.model.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;

    @Transactional(readOnly = true)
    public List<Item> findAll(int offset, int limit) {
        return itemMapper.findAll(offset, limit);
    }

    @Transactional(readOnly = true)
    public Optional<Item> findById(Long id) {
        return Optional.ofNullable(itemMapper.findById(id));
    }

    @Transactional
    public Item create(Item item) {
        itemMapper.insert(item);
        log.info("Item created: id={}", item.getId());
        return item;
    }

    @Transactional
    public Optional<Item> update(Long id, Item item) {
        if (itemMapper.findById(id) == null) return Optional.empty();
        item.setId(id);
        itemMapper.update(item);
        log.info("Item updated: id={}", id);
        return Optional.of(item);
    }

    @Transactional
    public boolean delete(Long id) {
        boolean deleted = itemMapper.delete(id) > 0;
        if (deleted) log.info("Item deleted: id={}", id);
        return deleted;
    }
}
