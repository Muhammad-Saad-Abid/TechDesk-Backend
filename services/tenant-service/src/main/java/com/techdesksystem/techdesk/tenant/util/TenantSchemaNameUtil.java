package com.techdesksystem.techdesk.tenant.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TenantSchemaNameUtil {

    private static final int MAX_SLUG_LENGTH = 80;
    private static final int MAX_SCHEMA_SLUG_LENGTH = 56;
    private static final Pattern VALID_SLUG =
            Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");

    private TenantSchemaNameUtil() {
    }

    public static String normalizeSlug(String rawSlug) {
        if (rawSlug == null || rawSlug.isBlank()) {
            throw new IllegalArgumentException("Tenant slug is required.");
        }

        String slug = Normalizer.normalize(rawSlug, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (slug.length() > MAX_SLUG_LENGTH
                || !VALID_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Tenant slug must start with a letter and contain only letters, numbers, or hyphens."
            );
        }

        return slug;
    }

    public static String toSchemaName(String normalizedSlug) {
        String schemaSlug = normalizedSlug.replace('-', '_');

        if (schemaSlug.length() > MAX_SCHEMA_SLUG_LENGTH) {
            String hash = sha256(normalizedSlug).substring(0, 8);
            schemaSlug = schemaSlug.substring(0, 47) + "_" + hash;
        }

        return "tenant_" + schemaSlug;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
