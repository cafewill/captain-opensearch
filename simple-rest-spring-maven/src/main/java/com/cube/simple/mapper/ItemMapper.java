package com.cube.simple.mapper;

import com.cube.simple.model.Item;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ItemMapper {
    List<Item> findAll();
    Item       findById(Long id);
    int        insert(Item item);
    int        update(Item item);
    int        delete(Long id);
}
