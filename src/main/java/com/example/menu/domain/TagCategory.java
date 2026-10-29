package com.example.menu.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "tag_categories", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class TagCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 30)
    private String name;

    protected TagCategory() { }
    public TagCategory(String name) { this.name = name; }
    public Long getId() { return id; }
    public String getName() { return name; }
}
