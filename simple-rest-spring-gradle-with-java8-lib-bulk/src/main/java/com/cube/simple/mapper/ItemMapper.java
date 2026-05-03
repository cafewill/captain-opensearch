package com.cube.simple.mapper;

import com.cube.simple.model.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ItemMapper {
    List<Item> findAll(@Param("offset") int offset, @Param("limit") int limit);
    Item       findById(Long id);
    int        insert(Item item);
    int        update(Item item);
    int        delete(Long id);
}
