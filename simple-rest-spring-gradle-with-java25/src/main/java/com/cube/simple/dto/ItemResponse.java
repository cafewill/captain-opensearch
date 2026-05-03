package com.cube.simple.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemResponse {

    private boolean status;
    private String  message;
    private Object  data;

    public static ItemResponse ok(String message, Object data) {
        return ItemResponse.builder().status(true).message(message).data(data).build();
    }

    public static ItemResponse fail(String message) {
        return ItemResponse.builder().status(false).message(message).build();
    }
}
