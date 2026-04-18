package com.cube.simple.dto;

import com.cube.simple.model.Item;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ItemRequest {

    @NotBlank(message = "이름은 필수입니다")
    private String name;

    private String description;

    @NotNull(message = "가격은 필수입니다")
    private Long price;

    public Item toItem() {
        return Item.builder()
                .name(name)
                .description(description)
                .price(price)
                .build();
    }
}
