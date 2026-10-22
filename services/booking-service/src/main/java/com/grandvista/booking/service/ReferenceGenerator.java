package com.grandvista.booking.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Human-friendly booking references such as {@code GV-7KQ2MX} (no ambiguous characters like 0/O, 1/I). */
@Component
public class ReferenceGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("GV-");
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
