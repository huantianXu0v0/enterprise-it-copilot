package com.xht.itcopilot.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private Boolean success;
    private String errorMsg;
    private T data;

    // 请求成功，无返回数据
    public static Result<Void> ok() {
        return new Result<>(true, null, null);
    }

    // 请求成功，有返回数据
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, null, data);
    }

    // 请求失败
    public static <T> Result<T> fail(String errorMsg) {
        return new Result<>(false, errorMsg, null);
    }
}

