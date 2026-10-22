package com.grandvista.auth.domain;

/** Roles a person can hold. {@code SERVICE} is deliberately absent: it is never assigned to a user. */
public enum Role {
    CUSTOMER,
    STAFF,
    ADMIN
}
