package com.example.menu.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "dish_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "name"})) 
                                        // 注释：@UniqueConstraint(columnNames = {"category_id", "name"}) 表示在数据库中，
                                        // category_id 和 name 这两列的组合必须是唯一的。
                                        // 也就是说，在同一个类别（category_id）下，标签名称（name）不能重复。
                                        // 这有助于确保数据的一致性和完整性，防止在同一类别中出现重复的标签名称。
public class DishTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 30)
    private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private TagCategory category;

    protected DishTag() { }
    public DishTag(TagCategory category, String name) { this.category = category; this.name = name; }
    public Long getId() { return id; }
    public String getName() { return name; }
    public TagCategory getCategory() { return category; }
    public TagRef snapshot() { return new TagRef(id, name, category.getId(), category.getName()); }
}
