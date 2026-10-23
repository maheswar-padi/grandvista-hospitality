package com.grandvista.room.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A hotel in the GrandVista portfolio. Room types, rooms and pricing policies belong to exactly one property;
 * stay dates of a property's rooms are calendar dates in the property's time zone.
 */
@Entity
@Table(name = "properties")
public class Property {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Property() {
    }

    public Property(UUID id, String code, String name, String city, String country, ZoneId timeZone, Instant now) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.code = code;
        this.name = name;
        this.city = city;
        this.country = country;
        this.timeZone = timeZone.getId();
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String city, String country, ZoneId timeZone, boolean active, Instant now) {
        this.name = name;
        this.city = city;
        this.country = country;
        this.timeZone = timeZone.getId();
        this.active = active;
        this.updatedAt = now;
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

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
