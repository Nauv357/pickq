package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.service.RoadmapService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 学习路线（学习路径引擎阶段 2）：今天做什么 / 还缺什么 / 路线图。
 * 设计见 docs/learning-path-design.md §7.1、§7.2。
 *
 * 这一层**不调模型**：全部由公式算出（掌握度、门控、外缘顺序、每日题量），所以随便刷新、零 token。
 * 一键开始练习走已有的会话接口（`POST /api/banks/{id}/sessions` + `mode=SKILL` + `nodeIds`）。
 */
@RestController
@RequestMapping("/api/roadmap")
public class RoadmapController {

    private final RoadmapService roadmapService;

    public RoadmapController(RoadmapService roadmapService) {
        this.roadmapService = roadmapService;
    }

    /** 整条路线：阶段 → 节点状态 + 外缘（下一步）+ 缺口（还缺什么） */
    @GetMapping
    public ApiResponse<RoadmapService.Roadmap> roadmap(@RequestParam Long bankId,
                                                       @RequestParam String templateId) {
        return ApiResponse.success(roadmapService.roadmap(bankId, templateId));
    }

    /** 今天的任务（外缘主攻节点的新题 + 到期复习），当天冻结、完成度按今天实际作答算 */
    @GetMapping("/today")
    public ApiResponse<RoadmapService.TodayView> today(@RequestParam Long bankId,
                                                       @RequestParam String templateId) {
        return ApiResponse.success(roadmapService.today(bankId, templateId));
    }

    /** 目标与每日题量（个体输入） */
    @GetMapping("/profile")
    public ApiResponse<RoadmapService.ProfileView> profile() {
        return ApiResponse.success(roadmapService.profileView());
    }

    @PutMapping("/profile")
    public ApiResponse<RoadmapService.ProfileView> saveProfile(@RequestBody Map<String, Object> body) {
        Integer dailyQuestions = intOrNull(body.get("dailyQuestions"));
        Integer dailyMinutes = intOrNull(body.get("dailyMinutes"));
        roadmapService.saveProfile(str(body.get("templateId")), str(body.get("goalText")),
                dailyQuestions, dailyMinutes, str(body.get("targetDate")));
        return ApiResponse.success(roadmapService.profileView());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
