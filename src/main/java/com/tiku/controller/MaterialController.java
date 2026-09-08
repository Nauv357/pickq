package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.model.Material;
import com.tiku.service.MaterialService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 共享材料（资料分析大题干）管理。
 */
@RestController
@RequestMapping("/api/banks/{bankId}/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public ApiResponse<List<Material>> list(@PathVariable Long bankId) {
        return ApiResponse.success(materialService.listByBank(bankId));
    }

    @PostMapping
    public ApiResponse<Long> create(@PathVariable Long bankId, @RequestBody Map<String, String> body) {
        return ApiResponse.success(materialService.create(bankId, body.get("content")));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long bankId, @PathVariable Long id, @RequestBody Map<String, String> body) {
        materialService.update(bankId, id, body.get("content"));
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long bankId, @PathVariable Long id) {
        materialService.delete(bankId, id);
        return ApiResponse.success(null);
    }
}
