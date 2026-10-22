package com.grandvista.platform.security;

/** Role names issued by the auth-service in the {@code roles} JWT claim. */
public final class Roles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";
    public static final String ADMIN = "ADMIN";
    /** Only issued to backend services through the client-credentials grant; never to people. */
    public static final String SERVICE = "SERVICE";

    private Roles() {
    }
}
