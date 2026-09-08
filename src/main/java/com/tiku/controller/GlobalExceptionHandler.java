package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    private final View error;

    public GlobalExceptionHandler(View error) {
        this.error = error;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoSuchElementException e) {
        return ApiResponse.error(404, e.getMessage());
    }

    //路径不存在（含已删除的接口），返回 404 而不是 500
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.error(404, "资源不存在：" + e.getResourcePath());
    }

    //路径存在但方法不匹配，返回 405 而不是 500
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.error(405, "请求方法不支持：" + e.getMethod());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    //业务状态异常（模型调用失败、AI 输出非法等）：保留真实原因给用户排查
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleState(IllegalStateException e) {
        log.error("业务状态异常", e);
        return ApiResponse.error(500, e.getMessage());
    }

    //SSE/异步流客户端断开与 SSE 超时：静默忽略，不刷 ERROR 日志。
    //注意：只能对"异步上下文或响应已提交"的 IO 中断静默——普通请求的 IOException
    //（磁盘写失败、图片读写失败等）必须如实返回 500，否则会被吞成 204"静默成功"，错误完全不可见。
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIoException(java.io.IOException e,
                                                               HttpServletRequest request,
                                                               HttpServletResponse response) {
        boolean asyncAbort = request.isAsyncStarted() || response.isCommitted()
                || org.springframework.web.context.request.async.WebAsyncUtils
                .getAsyncManager(request).hasConcurrentResult();
        if (asyncAbort) {
            log.debug("客户端连接中断（SSE 等异步流）: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        log.error("IO 异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "文件读写失败：" + e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleAsyncTimeout(org.springframework.web.context.request.async.AsyncRequestTimeoutException e) {
        log.debug("SSE 异步请求超时: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("请求处理失败", e);
        return ApiResponse.error(500, "内部服务器错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(": "));
        return ApiResponse.error(400, message);
    }

    // ==================== 客户端输入错误统一 400（此前全部落入 500"内部服务器错误"，不可诊断） ====================

    //畸形 JSON / 未知枚举等（HttpMessageNotReadableException 及其子类）
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String detail = cause != null && cause.getMessage() != null ? cause.getMessage() : e.getMessage();
        if (detail != null && detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        return ApiResponse.error(400, "请求体格式错误：" + detail);
    }

    //路径/查询参数类型不匹配（如 /jobs/abc）
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ApiResponse.error(400, "参数类型错误：" + e.getName() + "=" + e.getValue());
    }

    //缺 multipart part / 缺查询参数
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(Exception e) {
        return ApiResponse.error(400, "缺少必要参数：" + e.getMessage());
    }

    //超出上传大小限制（200MB）
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ApiResponse.error(400, "上传文件过大（超过 200MB 限制）");
    }
}
