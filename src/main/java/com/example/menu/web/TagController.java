package com.example.menu.web;

import com.example.menu.domain.TagRef;
import com.example.menu.service.TagService;
import com.example.menu.service.TagService.CategoryView;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class TagController {
    private final TagService tags;
    public TagController(TagService tags) { this.tags = tags; }
    @GetMapping("/api/tags")
    public List<CategoryView> catalog() { return tags.catalog(); }
    @PostMapping("/api/chef/tag-categories")
    public CategoryView addCategory(@RequestBody NameRequest request) { return tags.addCategory(request.name()); }
    @PostMapping("/api/chef/tags")
    public TagRef addTag(@RequestBody TagRequest request) { return tags.addTag(request.categoryId(), request.name()); }
    public record NameRequest(String name) { }
    public record TagRequest(Long categoryId, String name) { }
}
