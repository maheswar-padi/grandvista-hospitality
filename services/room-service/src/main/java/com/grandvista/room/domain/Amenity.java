package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "amenities")
public class Amenity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String icon;

    @Column(nullable = false)
    private String category;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Amenity() {
    }

    public Amenity(String code, String name, String icon, String category, Instant now) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.icon = icon;
        this.category = category;
        this.createdAt = now;
    }

    public void update(String name, String icon, String category) {
        this.name = name;
        this.icon = icon;
        this.category = category;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public String getCategory() {
        return category;
    }
}
