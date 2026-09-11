package com.tiku.service;

import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.regex.Pattern;

/** 将 AI 导入执行异常归类为稳定错误码，并产出不含敏感内容的用户提示与日志摘要。 */
@Component
public class AiImportFailureClassifier {

    private static final Pattern API_KEY_VALUE = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{8,}|api[_ -]?key\\s*[:=]\\s*)[^\\s,;)}\\]]+");

    public Failure classify(Throwable failure) {
        String message = collectMessages(failure).toLowerCase(Locale.ROOT);
        if (hasCause(failure, HttpTimeoutException.class) || hasCause(failure, SocketTimeoutException.class)
                || message.contains("timeout") || message.contains("超时")) {
            return failure("MODEL_TIMEOUT", "模型响应超时，请稍后重试或缩小导入文件范围", failure);
        }
        if (message.contains("http 429") || message.contains("rate limit") || message.contains("限流")
                || message.contains("请求过于频繁")) {
            return failure("MODEL_RATE_LIMITED", "模型服务请求过于频繁，请稍后重试", failure);
        }
        if (hasCause(failure, ConnectException.class) || hasCause(failure, UnknownHostException.class)
                || message.contains("无法连接") || message.contains("connection refused")) {
            return failure("MODEL_CONNECTION_FAILED", "无法连接模型服务，请检查网络、服务地址和 API 配置", failure);
        }
        if (message.contains("ai 输出不是合法 json") || message.contains("模型返回空白")
                || message.contains("响应格式异常") || message.contains("模型端点持续返回空白")) {
            return failure("MODEL_RESPONSE_INVALID", "模型返回内容无法解析，请重试或更换模型", failure);
        }
        if (message.contains("mineru") || message.contains("文档解析") || message.contains("pdf")
                || message.contains("docx") || hasCause(failure, java.io.IOException.class)) {
            return failure("DOCUMENT_PARSE_FAILED", "文档解析失败，请检查文件格式或更换解析方式后重试", failure);
        }
        return failure("IMPORT_PROCESSING_FAILED", "AI 导入处理失败，请重试；若持续失败请检查导入设置", failure);
    }

    private Failure failure(String code, String userMessage, Throwable cause) {
        return new Failure(code, userMessage, diagnosticSummary(cause));
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String collectMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private String diagnosticSummary(Throwable failure) {
        Throwable current = failure;
        String type = current == null ? "Unknown" : current.getClass().getSimpleName();
        String detail = current == null || current.getMessage() == null ? "" : current.getMessage();
        String sanitized = API_KEY_VALUE.matcher(detail).replaceAll("***").replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.length() > 180) {
            sanitized = sanitized.substring(0, 180) + "…";
        }
        return sanitized.isBlank() ? type : type + ": " + sanitized;
    }

    public record Failure(String code, String userMessage, String diagnosticSummary) {
    }
}
