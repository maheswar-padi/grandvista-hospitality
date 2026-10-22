-- Reference data required for the catalogue to work. Demo rooms are NOT created here; they are added by
-- the optional, clearly-labelled demo seeder (gv.demo.seed-enabled).

INSERT INTO room_types (id, code, name, description, sort_order, created_at, updated_at) VALUES
 ('a1000000-0000-4000-8000-000000000001', 'CLASSIC', 'Classic Room', 'Comfortable rooms for short city stays.', 1, now(), now()),
 ('a1000000-0000-4000-8000-000000000002', 'DELUXE', 'Deluxe Room', 'Larger rooms with upgraded furnishings and views.', 2, now(), now()),
 ('a1000000-0000-4000-8000-000000000003', 'FAMILY', 'Family Room', 'Space for families with flexible bedding.', 3, now(), now()),
 ('a1000000-0000-4000-8000-000000000004', 'SUITE', 'Suite', 'Separate living area and premium amenities.', 4, now(), now()),
 ('a1000000-0000-4000-8000-000000000005', 'PRESIDENTIAL', 'Presidential Suite', 'Our most spacious accommodation.', 5, now(), now());

INSERT INTO amenities (id, code, name, icon, category, created_at) VALUES
 ('b1000000-0000-4000-8000-000000000001', 'WIFI', 'High-speed Wi-Fi', 'wifi', 'CONNECTIVITY', now()),
 ('b1000000-0000-4000-8000-000000000002', 'AIR_CONDITIONING', 'Air conditioning', 'snowflake', 'COMFORT', now()),
 ('b1000000-0000-4000-8000-000000000003', 'SMART_TV', 'Smart TV', 'tv', 'ENTERTAINMENT', now()),
 ('b1000000-0000-4000-8000-000000000004', 'MINIBAR', 'Minibar', 'wine', 'FOOD_AND_DRINK', now()),
 ('b1000000-0000-4000-8000-000000000005', 'COFFEE_MACHINE', 'Coffee machine', 'coffee', 'FOOD_AND_DRINK', now()),
 ('b1000000-0000-4000-8000-000000000006', 'BATHTUB', 'Bathtub', 'bath', 'BATHROOM', now()),
 ('b1000000-0000-4000-8000-000000000007', 'RAIN_SHOWER', 'Rain shower', 'shower-head', 'BATHROOM', now()),
 ('b1000000-0000-4000-8000-000000000008', 'BALCONY', 'Private balcony', 'sun', 'VIEW', now()),
 ('b1000000-0000-4000-8000-000000000009', 'SEA_VIEW', 'Sea view', 'waves', 'VIEW', now()),
 ('b1000000-0000-4000-8000-000000000010', 'CITY_VIEW', 'City view', 'building-2', 'VIEW', now()),
 ('b1000000-0000-4000-8000-000000000011', 'WORKSPACE', 'Work desk', 'laptop', 'BUSINESS', now()),
 ('b1000000-0000-4000-8000-000000000012', 'ROOM_SERVICE', '24-hour room service', 'concierge-bell', 'SERVICE', now()),
 ('b1000000-0000-4000-8000-000000000013', 'KITCHENETTE', 'Kitchenette', 'cooking-pot', 'FOOD_AND_DRINK', now()),
 ('b1000000-0000-4000-8000-000000000014', 'ACCESSIBLE', 'Accessible room', 'accessibility', 'ACCESSIBILITY', now()),
 ('b1000000-0000-4000-8000-000000000015', 'BUTLER', 'Butler service', 'hand-platter', 'SERVICE', now());

-- Initial pricing policy (version 1). Administrators publish new versions through the API.
INSERT INTO pricing_policies (id, version, status, weekend_days, weekend_multiplier, min_price_factor, max_price_factor,
                              service_fee_rate, tax_rate, notes, created_by, created_at)
VALUES ('c1000000-0000-4000-8000-000000000001', 1, 'ACTIVE', 'FRIDAY,SATURDAY', 1.1500, 0.8000, 1.8000, 0.0500, 0.1200,
        'Initial policy: weekend nights +15%, demand tiers by room-type occupancy, 5% service fee, 12% tax.',
        'system', now());

INSERT INTO pricing_demand_tiers (id, policy_id, min_occupancy, multiplier) VALUES
 ('c2000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 0.0000, 1.0000),
 ('c2000000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000001', 0.5000, 1.1000),
 ('c2000000-0000-4000-8000-000000000003', 'c1000000-0000-4000-8000-000000000001', 0.8000, 1.2500);

-- Example seasonal rules (inclusive date ranges); edit by publishing a new policy version.
INSERT INTO pricing_seasons (id, policy_id, name, start_date, end_date, multiplier) VALUES
 ('c3000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', 'Festive peak', '2026-12-20', '2027-01-05', 1.3000),
 ('c3000000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000001', 'Monsoon value', '2027-07-01', '2027-08-31', 0.8500);
