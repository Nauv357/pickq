package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.StatsDetailResponse;
import com.tiku.dto.StatsSummaryResponse;
import com.tiku.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习统计（A6）：GET /api/stats/summary 核心四块聚合；GET /api/stats/detail 二期
 * D 题库×掌握度 / E 错题治愈 / F 最近练习。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public ApiResponse<StatsSummaryResponse> summary() {
        return ApiResponse.success(statsService.summary());
    }

    @GetMapping("/detail")
    public ApiResponse<StatsDetailResponse> detail() {
        return ApiResponse.success(statsService.detail());
    }
}
