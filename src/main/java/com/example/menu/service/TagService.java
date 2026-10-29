package com.example.menu.service;

import com.example.menu.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {
    private final TagCategoryRepository categories;
    private final DishTagRepository tags;
    private final ObjectMapper mapper;

    public TagService(TagCategoryRepository categories, DishTagRepository tags, ObjectMapper mapper) {
        this.categories = categories; this.tags = tags; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> catalog() {
        return categories.findAllByOrderByIdAsc().stream().map(category -> new CategoryView(category.getId(),
                category.getName(), tags.findByCategoryIdOrderByIdAsc(category.getId()).stream()
                .map(tag -> new TagChoice(tag.getId(), tag.getName())).toList())).toList();
    }

    @Transactional
    public CategoryView addCategory(String value) {
        String name = cleanName(value, "分类");
        if (categories.findByNameIgnoreCase(name).isPresent()) throw ApiException.conflict("此分类已存在");
        try {
            TagCategory category = categories.saveAndFlush(new TagCategory(name));
            return new CategoryView(category.getId(), category.getName(), List.of());
        } catch (DataIntegrityViolationException error) { throw ApiException.conflict("此分类已存在"); }
    }

    @Transactional
    public TagRef addTag(Long categoryId, String value) {
        String name = cleanName(value, "标签");
        if (categoryId == null || categoryId < 1) throw ApiException.badRequest("请选择标签分类");
        TagCategory category = categories.findForUpdate(categoryId).orElseThrow(() -> ApiException.badRequest("标签分类不存在"));
        if (tags.findByCategoryIdAndNameIgnoreCase(categoryId, name).isPresent()) throw ApiException.conflict("此分类中已存在同名标签");
        try { return tags.saveAndFlush(new DishTag(category, name)).snapshot(); }
        catch (DataIntegrityViolationException error) { throw ApiException.conflict("此分类中已存在同名标签"); }
    }

    @Transactional(readOnly = true)
    public List<TagRef> resolve(String raw, List<TagRef> previous) {
        if (raw == null) return previous == null || previous.isEmpty() ? defaultTags() : List.copyOf(previous);
        if (raw.length() > 2048) throw ApiException.badRequest("标签选择内容过长");
        JsonNode values;
        try { values = mapper.readTree(raw); }
        catch (JsonProcessingException error) { throw ApiException.badRequest("标签选择格式不正确"); }
        if (values == null || !values.isArray()) throw ApiException.badRequest("标签选择格式不正确");
        var ids = new LinkedHashSet<Long>();
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) throw ApiException.badRequest("标签编号无效");
            ids.add(value.asLong());
            if (ids.size() > 12) throw ApiException.badRequest("请选择不超过 12 个标签");
        }
        if (ids.isEmpty()) return defaultTags();
        var selected = new ArrayList<TagRef>();
        for (Long id : ids) selected.add(tags.findById(id).orElseThrow(() -> ApiException.badRequest("标签不存在，请刷新后重试")).snapshot());
        if (selected.size() > 1) {
            Long fallbackId = defaultTags().getFirst().id();
            selected.removeIf(tag -> tag.id().equals(fallbackId));
        }
        return List.copyOf(selected);
    }

    @Transactional(readOnly = true)
    public List<TagRef> defaultTags() {
        TagCategory category = categories.findByNameIgnoreCase("其他").orElseThrow(() -> new IllegalStateException("Default tag category missing"));
        return List.of(tags.findByCategoryIdAndNameIgnoreCase(category.getId(), "未分类")
                .orElseThrow(() -> new IllegalStateException("Default tag missing")).snapshot());
    }

    private String cleanName(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("请填写" + label + "名称");
        String name = value.strip();
        if (name.length() > 30 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw ApiException.badRequest(label + "名称不能超过 30 个字符或包含控制字符");
        }
        return name;
    }

    public record TagChoice(Long id, String name) { }
    public record CategoryView(Long id, String name, List<TagChoice> tags) { }
}
