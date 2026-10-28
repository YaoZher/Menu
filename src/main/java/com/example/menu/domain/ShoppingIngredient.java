package com.example.menu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public class ShoppingIngredient {
    @Column(name = "ingredient_id", nullable = false)
    private UUID id;
    @Column(name = "ingredient_text", nullable = false, length = 2000)
    private String text;
    @Column(nullable = false)
    private boolean checked;

    protected ShoppingIngredient() { }

    public ShoppingIngredient(String text) {
        this.id = UUID.randomUUID();
        this.text = text;
    }

    public UUID getId() { return id; }
    public String getText() { return text; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }
}
