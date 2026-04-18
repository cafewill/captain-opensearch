package com.cube.simple.service;

import com.cube.simple.mapper.ItemMapper;
import com.cube.simple.model.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;

    @Transactional(readOnly = true)
    public List<Item> findAll() {
        return itemMapper.findAll();
    }

    @Transactional(readOnly = true)
    public Item findById(Long id) {
        return itemMapper.findById(id);
    }

    @Transactional
    public Item create(Item item) {
        itemMapper.insert(item);
        log.info("Item created: id={}", item.getId());
        return item;
    }

    @Transactional
    public Item update(Long id, Item item) {
        if (itemMapper.findById(id) == null) return null;
        item.setId(id);
        itemMapper.update(item);
        log.info("Item updated: id={}", id);
        return item;
    }

    @Transactional
    public boolean delete(Long id) {
        boolean deleted = itemMapper.delete(id) > 0;
        if (deleted) log.info("Item deleted: id={}", id);
        return deleted;
    }
}
