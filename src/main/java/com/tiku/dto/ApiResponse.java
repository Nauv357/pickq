package com.tiku.dto;

public record ApiResponse<T>(int code, T data, String message) {
    public static <T> ApiResponse<T> success(T data){
        return new ApiResponse<>(200, data, "ok");
    }

    public static <T> ApiResponse<T> error(int code, String message){
        return new ApiResponse<>(code, null, message);
    }
}
