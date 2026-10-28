package com.example.menu.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

@Entity
@Table(name = "shopping_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_name", "dish_id"}))
public class ShoppingEntry {
    @Id
    private UUID id;
    @Column(name = "owner_name", nullable = false, length = 255)
    private String owner;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dish_id", nullable = false)
    private Dish dish;
    @Column(nullable = false)
    private Instant addedAt;
    @Version
    private long version;
    @ElementCollection
    @CollectionTable(name = "shopping_ingredients", joinColumns = @JoinColumn(name = "entry_id"))
    @OrderColumn(name = "line_order")
    private List<ShoppingIngredient> ingredients = new ArrayList<>();

    protected ShoppingEntry() { }

    public ShoppingEntry(String owner, Dish dish) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.dish = dish;
        this.addedAt = Instant.now();
        synchronizeIngredients();
    }

    public void synchronizeIngredients() {
        Map<String, Queue<ShoppingIngredient>> previous = new HashMap<>();
        for (ShoppingIngredient ingredient : ingredients) {
            previous.computeIfAbsent(ingredient.getText(), ignored -> new ArrayDeque<>()).add(ingredient);
        }
        List<ShoppingIngredient> next = new ArrayList<>();
        for (NoteLine line : dish.getNoteLines()) {
            if (line.type() != NoteLine.Type.INGREDIENT) continue;
            Queue<ShoppingIngredient> matches = previous.get(line.text());
            next.add(matches != null && !matches.isEmpty() ? matches.remove() : new ShoppingIngredient(line.text()));
        }
        ingredients.clear();
        ingredients.addAll(next);
    }

    public UUID getId() { return id; }
    public String getOwner() { return owner; }
    public Dish getDish() { return dish; }
    public Instant getAddedAt() { return addedAt; }
    public List<ShoppingIngredient> getIngredients() { return ingredients; }
}
