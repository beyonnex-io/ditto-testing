/*
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.testing.common;

import java.util.Arrays;
import java.util.Optional;

/**
 * An enumeration of HTTP headers.
 */
public enum HttpHeader {

    /**
     * Authorization HTTP header.
     */
    AUTHORIZATION("Authorization"),

    /**
     * WWW-Authenticate HTTP header.
     */
    WWW_AUTHENTICATE("WWW-Authenticate"),

    /**
     * Date HTTP header.
     */
    DATE("Date"),

    /**
     * Host HTTP header.
     */
    HOST("Host"),

    /**
     * Location HTTP header.
     */
    LOCATION("Location"),

    /**
     * Origin HTTP header.
     */
    ORIGIN("Origin"),

    /**
     * Content-Type HTTP header.
     */
    CONTENT_TYPE("Content-Type"),

    /**
     * Timeout of messages.
     */
    TIMEOUT("Timeout"),

    /**
     * Response required HTTP header.
     */
    RESPONSE_REQUIRED("response-required"),

    /**
     * x-correlation-id HTTP header.
     */
    X_CORRELATION_ID("x-correlation-id"),

    /**
     * HTTP header for authentication already done by e.g. a reverse proxy in front of Ditto (e.g. a nginx).
     */
    X_DITTO_PRE_AUTH("x-ditto-pre-authenticated"),

    /**
     * Http header which holds the json key parameter order.
     * The order is extracted from the json keys inside the message payload.
     */
    X_THINGS_PARAMETER_ORDER("x-things-parameter-order"),

    /**
     * Header definition for allowing the policy lockout (i.e. a subject can create a policy without having WRITE
     * permission on the policy resource for itself, by default a subject making the request must have
     * WRITE permission on policy resource).
     */
    ALLOW_POLICY_LOCKOUT("allow-policy-lockout"),

    /**
     * Header definition for setting metadata relatively to the resource of a modified entity.
     */
    PUT_METADATA("put-metadata"),

    /**
     * Header definition for specifying a condition when an update should be applied.
     */
    CONDITION("condition");

    private final String name;

    HttpHeader(final String name) {
        this.name = name;
    }

    /**
     * Returns a {@code HttpHeader} from a given string representation.
     *
     * @param name the string representation.
     * @return the HttpHeader.
     */
    public static Optional<HttpHeader> fromName(final String name) {
        return Arrays.stream(values()).filter(header -> name.equals(header.toString())).findFirst();
    }

    /**
     * Returns the name of this {@link HttpHeader}.
     *
     * @return the name.
     */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
