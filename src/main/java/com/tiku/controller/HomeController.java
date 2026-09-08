package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.HomeOverviewResponse;
import com.tiku.service.QuestionBankService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主页概览（题库列表页的"学习首页"卡带数据源）
 */
@RestController
@RequestMapping("/api/home")
public class HomeController {

    private final QuestionBankService questionBankService;

    public HomeController(QuestionBankService questionBankService) {
        this.questionBankService = questionBankService;
    }

    @GetMapping("/overview")
    public ApiResponse<HomeOverviewResponse> overview() {
        return ApiResponse.success(questionBankService.getHomeOverview());
    }
}
