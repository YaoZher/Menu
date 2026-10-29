package com.example.menu.service;

import com.example.menu.domain.*;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
public class TagCatalogMigration implements ApplicationRunner {
    private final TagCategoryRepository categories;
    private final DishTagRepository tags;
    private final JdbcTemplate jdbc;
    private final TagRefsConverter converter = new TagRefsConverter();

    public TagCatalogMigration(TagCategoryRepository categories, DishTagRepository tags, JdbcTemplate jdbc) {
        this.categories = categories; this.tags = tags; this.jdbc = jdbc;
    }

    @Override @Transactional
    public void run(ApplicationArguments arguments) { seedAndBackfill(); }

    @Transactional
    public int seedAndBackfill() {
        for (List<String> group : List.of(
                List.of("菜品类型", "主食", "前菜", "主菜", "配菜", "水果", "汤", "甜品", "饮品"),
                List.of("营养构成", "碳水", "蛋白质", "蔬菜", "高纤维"),
                List.of("烹饪方式", "清蒸", "炖煮", "炒菜", "凉拌", "烧烤", "煎炸", "烘焙"),
                List.of("饮食特点", "清淡", "高油", "低脂", "素食", "辛辣"),
                List.of("其他", "未分类"))) {
            TagCategory category = categories.findByNameIgnoreCase(group.getFirst())
                    .orElseGet(() -> categories.saveAndFlush(new TagCategory(group.getFirst())));
            for (String name : group.subList(1, group.size())) {
                if (tags.findByCategoryIdAndNameIgnoreCase(category.getId(), name).isEmpty()) tags.save(new DishTag(category, name));
            }
            tags.flush();
        }
        TagCategory fallback = categories.findByNameIgnoreCase("其他").orElseThrow();
        String value = converter.convertToDatabaseColumn(List.of(tags.findByCategoryIdAndNameIgnoreCase(fallback.getId(), "未分类").orElseThrow().snapshot()));
        // Add labels only; never alter original dish versions/timestamps or create absent review snapshots.
        return jdbc.update("update dishes set tags = ? where tags is null", value)
                + jdbc.update("update submissions set before_tags = ? where before_tags is null and before_name is not null", value)
                + jdbc.update("update submissions set after_tags = ? where after_tags is null and after_name is not null", value);
    }
}
