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
package org.eclipse.ditto.testing.system.connectivity.httppush;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.concurrent.ThreadSafe;

import org.eclipse.ditto.base.service.UriEncoding;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Query;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.japi.Pair;
import akka.util.ByteString;

/**
 * Signing of HTTP requests to authenticate at AWS.
 */
@ThreadSafe
public final class AwsRequestSigning implements HmacSigning {

    private static final DateTimeFormatter DATE_STAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Z"));
    static final DateTimeFormatter X_AMZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssz").withZone(ZoneId.of("Z"));
    private static final char[] LOWER_CASE_HEX_CHARS = "0123456789abcdef".toCharArray();

    private final String region;
    private final String service;
    private final String secretKey;
    private final String accessKey;
    private final String algorithm;
    private final boolean doubleEncodeAndNormalize;
    private final Collection<String> canonicalHeaderNames;
    private final XAmzContentSha256 xAmzContentSha256;

    static final String X_AMZ_DATE_HEADER = "x-amz-date";
    private static final String X_AMZ_CONTENT_SHA256_HEADER = "x-amz-content-sha256";
    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String HOST_HEADER = "host";

    private AwsRequestSigning(final String region, final String service, final String secretKey,
            final String accessKey, final String algorithm, final boolean doubleEncode,
            final List<String> canonicalHeaderNames, final XAmzContentSha256 xAmzContentSha256) {
        this.region = region;
        this.service = service;
        this.secretKey = secretKey;
        this.accessKey = accessKey;
        this.algorithm = algorithm;
        this.doubleEncodeAndNormalize = doubleEncode;
        this.canonicalHeaderNames = toDeduplicatedSortedLowerCase(canonicalHeaderNames);
        this.xAmzContentSha256 = xAmzContentSha256;
    }

    public static AwsRequestSigning newInstance(final String region, final String service, final String secretKey,
            final String accessKey, final String algorithm, final boolean doubleEncode,
            final List<String> canonicalHeaderNames, final XAmzContentSha256 xAmzContentSha256) {
        return new AwsRequestSigning(region, service, secretKey,
        accessKey, algorithm, doubleEncode,
        canonicalHeaderNames, xAmzContentSha256);
    }

    HttpCredentials generateSignedAuthorizationHeader(final HttpRequest request) {
        final Instant xAmzDate =
                ZonedDateTime.parse(request.getHeader(X_AMZ_DATE_HEADER).orElseThrow().value(),
                        X_AMZ_DATE_FORMATTER).toInstant();
        System.out.println("xAmzDate:" + xAmzDate);
        final byte[] key = getSigningKey(secretKey, xAmzDate);
        System.out.println("key: " + key);
        final String payloadHash = getPayloadHash(request);
        System.out.println("payloadHash: " + payloadHash);
        final String stringToSign =
                getStringToSignAws(request, xAmzDate, doubleEncodeAndNormalize, payloadHash);
        System.out.println("stringToSign:" + stringToSign);
        final String signature = toLowerCaseHex(hmacSha256(key, stringToSign));
        System.out.println("signature: " + signature);
        return renderHttpCredentials(signature, xAmzDate);

    }

    private String getStringToSignAws(final HttpRequest strictRequest, final Instant xAmzDate,
            final boolean doubleEncodeAndNormalize, final String payloadHash) {
        return String.join("\n",
                algorithm.toUpperCase(),
                X_AMZ_DATE_FORMATTER.format(xAmzDate),
                getCredentialScope(xAmzDate, region, service),
                sha256(getCanonicalRequest(strictRequest, xAmzDate, doubleEncodeAndNormalize, payloadHash)
                        .getBytes(StandardCharsets.UTF_8))
        );
    }

    private HttpCredentials renderHttpCredentials(final String signature, final Instant xAmzDate) {
        final var authParams = List.of(
                Pair.create("Credential", accessKey + "/" + getCredentialScope(xAmzDate, region, service)),
                Pair.create("SignedHeaders", getSignedHeaders()),
                Pair.create("Signature", signature)
        );
        // render without quoting: AWS can't handle quoted auth params (RFC-7235) as of 26.05.2021
        final String authParamsRenderedWithoutQuotes = renderWithoutQuotes(authParams);
        return HttpCredentials.create(algorithm.toUpperCase(), authParamsRenderedWithoutQuotes);
    }

    private static String renderWithoutQuotes(final List<Pair<String, String>> authParams) {
        return authParams.stream()
                .map(pair -> pair.first() + "=" + pair.second())
                // space after comma is obligatory
                .collect(Collectors.joining(", "));
    }

    private static String toLowerCaseHex(final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(2 * bytes.length);
        for (final byte b : bytes) {
            builder.append(LOWER_CASE_HEX_CHARS[(b & 0xF0) >> 4]);
            builder.append(LOWER_CASE_HEX_CHARS[b & 0x0F]);
        }
        return builder.toString();
    }

    private String getCanonicalRequest(final HttpRequest strictRequest, final Instant xAmzDate,
            final boolean doubleEncodeAndNormalize, final String payloadHash) {
        final String method = strictRequest.method().name();
        final String canonicalUri = getCanonicalUri(strictRequest.getUri(), doubleEncodeAndNormalize);
        final String canonicalQuery = getCanonicalQuery(strictRequest.getUri().query());
        final String canonicalHeaders = getCanonicalHeaders(strictRequest, xAmzDate, payloadHash);
        return String.join("\n", method, canonicalUri, canonicalQuery, canonicalHeaders, getSignedHeaders(),
                payloadHash);
    }

    private String getSignedHeaders() {
        return String.join(";", canonicalHeaderNames);
    }


    private String getCanonicalHeaders(final HttpRequest request, final Instant xAmzDate,
            final String payloadHash) {
        return canonicalHeaderNames.stream()
                .map(key -> {
                    switch (key) {
                        case HOST_HEADER:
                            return HOST_HEADER + ":" + request.getUri().host().address() + "\n";
                        case CONTENT_TYPE_HEADER:
                            return getContentTypeAsCanonicalHeader(request);
                        case X_AMZ_CONTENT_SHA256_HEADER:
                            return X_AMZ_CONTENT_SHA256_HEADER + ":" + payloadHash + "\n";
                        case X_AMZ_DATE_HEADER:
                            return X_AMZ_DATE_HEADER + ":" + X_AMZ_DATE_FORMATTER.format(xAmzDate) + "\n";
                        default:
                            return key + streamHeaders(request, key)
                                    .map(akka.http.javadsl.model.HttpHeader::value)
                                    .map(AwsRequestSigning::trimHeaderValue)
                                    .collect(Collectors.joining(",", ":", "\n"));
                    }
                })
                .collect(Collectors.joining());
    }

    private static String trimHeaderValue(final String headerValue) {
        return headerValue.strip().replaceAll("\\s+", " ");
    }

    private static Stream<HttpHeader> streamHeaders(final HttpRequest request, final String lowerCaseHeaderName) {
        return StreamSupport.stream(request.getHeaders().spliterator(), false)
                .filter(header -> header.lowercaseName().equals(lowerCaseHeaderName));
    }

    private static String getContentTypeAsCanonicalHeader(final HttpRequest request) {
        final ContentType contentType =
                request.getHeader(akka.http.javadsl.model.headers.ContentType.class)
                        .map(akka.http.javadsl.model.headers.ContentType::contentType)
                        .orElse(request.entity().getContentType());
        return CONTENT_TYPE_HEADER + ":" +
                contentType.mediaType() +
                contentType.getCharsetOption().map(charset -> "; charset=" + charset.value()).orElse("") +
                "\n";
    }

    private static String getCanonicalUri(final Uri uri, final boolean doubleEncodeAndNormalize) {
        return doubleEncodeAndNormalize ? encodeAndNormalizePathSegments(uri) : getPathStringOrSlashWhenEmpty(uri);
    }

    private static String getPathStringOrSlashWhenEmpty(final Uri uri) {
        final String pathString = uri.getPathString();
        return pathString.isEmpty() ? "/" : pathString;
    }

    private static String encodeAndNormalizePathSegments(final Uri uri) {
        final String slash = "/";
        final String trailingSeparator = uri.getPathString().endsWith(slash) ? slash : "";
        return StreamSupport.stream(uri.pathSegments().spliterator(), false)
                .filter(s -> !s.isEmpty())
                // encode path segment twice as required for all AWS services except S3
                .map(UriEncoding::encodePathSegment)
                .map(UriEncoding::encodePathSegment)
                .collect(Collectors.joining(slash, slash, trailingSeparator));
    }

    private static String getCanonicalQuery(final Query query) {
        return query.toMultiMap()
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> {
                    final String encodedKey = UriEncoding.encodeAllButUnreserved(entry.getKey());
                    return encodeQueryParameterValues(entry.getValue()).map(value -> encodedKey + "=" + value);
                })
                .collect(Collectors.joining("&"));
    }

    private static Stream<String> encodeQueryParameterValues(final List<String> values) {
        return values.stream()
                // values are sorted by code point first
                .sorted()
                // '=' must be double-encoded
                .map(s -> s.replace("=", "%3D"))
                // all non-unreserved characters are single-encoded
                .map(UriEncoding::encodeAllButUnreserved);
    }

    private static String sha256(final byte[] bytes) {
        return toLowerCaseHex(getSha256().digest(bytes));
    }

    /**
     * Get a SHA-256 implementation.
     *
     * @return the implementation.
     */
    private static MessageDigest getSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            // impossible - all JVM must support SHA-256.
            throw new CompletionException(e);
        }
    }

    private static String getCredentialScope(final Instant xAmzDate, final String region, final String service) {
        return String.join("/", DATE_STAMP_FORMATTER.format(xAmzDate), region, service, "aws4_request");
    }

    private String getPayloadHash(final HttpRequest strictRequest) {
        final ByteString payload = ((HttpEntity.Strict) strictRequest.entity()).getData();
        return getPayloadHash(payload);
    }

    private String getPayloadHash(final ByteString payload) {
        return xAmzContentSha256 == XAmzContentSha256.UNSIGNED ? "UNSIGNED-PAYLOAD" : sha256(payload.toArray());
    }

    private byte[] getSigningKey(final String secretKey, final Instant timestamp) {
        return getKSigning(getKService(getKRegion(getKDate(getKSecret(secretKey), timestamp), region), service));
    }

    private static byte[] getKSecret(final String secretKey) {
        return ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] getKDate(final byte[] kSecret, final Instant xAmzDate) {
        final String dateStamp = DATE_STAMP_FORMATTER.format(xAmzDate);
        return hmacSha256(kSecret, dateStamp);
    }

    private byte[] getKRegion(final byte[] kDate, final String region) {
        return hmacSha256(kDate, region);
    }

    private byte[] getKService(final byte[] kRegion, final String service) {
        return hmacSha256(kRegion, service);
    }

    private byte[] getKSigning(final byte[] kService) {
        return hmacSha256(kService, "aws4_request");
    }

    private static Collection<String> toDeduplicatedSortedLowerCase(final List<String> strings) {
        return strings.stream().map(String::strip)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    enum XAmzContentSha256 {
        INCLUDED,
        EXCLUDED,
        UNSIGNED
    }
}
