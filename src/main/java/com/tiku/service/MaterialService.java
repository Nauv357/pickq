package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Material;
import com.tiku.model.Question;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 共享材料（资料分析大题干）管理：随题库生命周期，删除时组内题解除引用。
 */
@Service
public class MaterialService {

    private final MaterialMapper materialMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBankService questionBankService;

    public MaterialService(MaterialMapper materialMapper, QuestionMapper questionMapper,
                           QuestionBankService questionBankService) {
        this.materialMapper = materialMapper;
        this.questionMapper = questionMapper;
        this.questionBankService = questionBankService;
    }

    public List<Material> listByBank(Long bankId) {
        questionBankService.findByIdOrThrow(bankId);
        return materialMapper.selectList(new LambdaQueryWrapper<Material>()
                .eq(Material::getBankId, bankId)
                .orderByAsc(Material::getSortOrder)
                .orderByAsc(Material::getId));
    }

    /**
     * 校验材料属于指定题库后返回（update/delete 走路径 bankId 校验归属，
     * 防止携带他库 URL 的 id 跨题库改删材料）
     */
    private Material getByIdInBank(Long bankId, Long id) {
        Material material = materialMapper.selectById(id);
        if (material == null) {
            throw new NoSuchElementException("材料不存在：" + id);
        }
        if (!material.getBankId().equals(bankId)) {
            throw new NoSuchElementException("材料不存在：" + id);
        }
        return material;
    }

    public Long create(Long bankId, String content) {
        questionBankService.findByIdOrThrow(bankId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("材料内容不能为空");
        }
        Material material = new Material();
        material.setBankId(bankId);
        material.setContent(content);
        material.setSortOrder(0);
        materialMapper.insert(material);
        return material.getId();
    }

    public void update(Long bankId, Long id, String content) {
        Material material = getByIdInBank(bankId, id);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("材料内容不能为空");
        }
        material.setContent(content);
        materialMapper.updateById(material);
    }

    /** 删除材料：组内题解除 material_id 引用（题保留，退回普通题） */
    @Transactional
    public void delete(Long bankId, Long id) {
        getByIdInBank(bankId, id);
        questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                .eq(Question::getMaterialId, id)
                .set(Question::getMaterialId, null));
        materialMapper.deleteById(id);
    }
}
