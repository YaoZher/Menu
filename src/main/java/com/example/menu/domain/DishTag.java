package com.example.menu.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "dish_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "name"}))
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
