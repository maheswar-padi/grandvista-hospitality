package com.grandvista.room.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {

    public static final int MAX_IMAGES = 10;

    @Id
    private UUID id;

    @Column(name = "room_number", nullable = false, unique = true)
    private String roomNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id")
    private RoomType roomType;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "max_occupancy", nullable = false)
    private short maxOccupancy;

    @Column(name = "bed_configuration", nullable = false)
    private String bedConfiguration;

    @Column(name = "size_sq_m")
    private Short sizeSqM;

    private Short floor;

    @Column(name = "view_type")
    private String viewType;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "room_amenities", joinColumns = @JoinColumn(name = "room_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    private Set<Amenity> amenities = new HashSet<>();

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC, createdAt ASC")
    private List<RoomImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Room() {
    }

    public Room(String roomNumber, RoomType roomType, Details details, Instant now) {
        this.id = UUID.randomUUID();
        this.roomNumber = roomNumber;
        this.roomType = roomType;
        this.status = RoomStatus.ACTIVE;
        this.createdAt = now;
        apply(details, now);
    }

    /** Editable descriptive and commercial attributes (status is changed only through {@link #changeStatus}). */
    public record Details(String name, String description, short maxOccupancy, String bedConfiguration, Short sizeSqM,
                          Short floor, String viewType, BigDecimal basePrice, String currency, Collection<Amenity> amenities) {
    }

    public void update(RoomType type, Details details, Instant now) {
        this.roomType = type;
        apply(details, now);
    }

    private void apply(Details d, Instant now) {
        this.name = d.name();
        this.description = d.description();
        this.maxOccupancy = d.maxOccupancy();
        this.bedConfiguration = d.bedConfiguration();
        this.sizeSqM = d.sizeSqM();
        this.floor = d.floor();
        this.viewType = d.viewType();
        this.basePrice = d.basePrice();
        this.currency = d.currency();
        this.amenities.clear();
        this.amenities.addAll(d.amenities());
        this.updatedAt = now;
    }

    public void changeStatus(RoomStatus newStatus, String reason, Instant now) {
        this.status = newStatus;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    public RoomImage addImage(String storageKey, String contentType, int sizeBytes, String altText, Instant now) {
        if (images.size() >= MAX_IMAGES) {
            throw new IllegalStateException("A room can have at most " + MAX_IMAGES + " images");
        }
        RoomImage image = new RoomImage(this, storageKey, contentType, sizeBytes, altText, images.size(), now);
        images.add(image);
        this.updatedAt = now;
        return image;
    }

    public Optional<RoomImage> removeImage(UUID imageId, Instant now) {
        Optional<RoomImage> image = images.stream().filter(i -> i.getId().equals(imageId)).findFirst();
        image.ifPresent(i -> {
            images.remove(i);
            this.updatedAt = now;
        });
        return image;
    }

    public UUID getId() {
        return id;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public short getMaxOccupancy() {
        return maxOccupancy;
    }

    public String getBedConfiguration() {
        return bedConfiguration;
    }

    public Short getSizeSqM() {
        return sizeSqM;
    }

    public Short getFloor() {
        return floor;
    }

    public String getViewType() {
        return viewType;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public String getCurrency() {
        return currency;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Set<Amenity> getAmenities() {
        return Set.copyOf(amenities);
    }

    public List<RoomImage> getImages() {
        return List.copyOf(images);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
