package io.github.near.sweats.logging.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;

/**
 * Builds Discord webhook payloads using classic embed format (compatible with all webhooks).
 * Classic embeds support: title, description, color, fields, footer, timestamp, author, thumbnail, image.
 */
public final class DiscordLogPayload {

    private static final int MAX_EMBED_DESCRIPTION = 4096;
    private static final int MAX_EMBED_FIELD_VALUE = 1024;
    private static final int MAX_EMBED_FIELD_NAME = 256;
    private static final int MAX_EMBED_FOOTER = 2048;
    private static final int MAX_EMBED_AUTHOR_NAME = 256;

    private DiscordLogPayload() {}

    /**
     * Creates a classic embed payload with description text.
     *
     * @param description the message content (already sanitized)
     * @param color the embed color (hex integer, e.g., 0x99AAB5 for grey)
     * @param title optional title
     * @param thumbnailUrl optional thumbnail URL (e.g., player avatar)
     * @param fields optional fields (name, value, inline)
     * @param footer optional footer text
     * @return JSON string ready for webhook POST
     */
    public static String embed(
            final String description,
            final int color,
            final String title,
            final String thumbnailUrl,
            final Iterable<Field> fields,
            final String footer) {

        final JsonObject embed = new JsonObject();

        if (title != null && !title.isBlank()) {
            embed.addProperty("title", truncate(title, MAX_EMBED_AUTHOR_NAME));
        }

        if (description != null && !description.isBlank()) {
            embed.addProperty("description", truncate(description, MAX_EMBED_DESCRIPTION));
        }

        embed.addProperty("color", color);

        if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
            final JsonObject thumb = new JsonObject();
            thumb.addProperty("url", thumbnailUrl);
            embed.add("thumbnail", thumb);
        }

        if (fields != null) {
            final JsonArray fieldsArray = new JsonArray();
            for (final Field field : fields) {
                final JsonObject fieldObj = new JsonObject();
                fieldObj.addProperty("name", truncate(field.name(), MAX_EMBED_FIELD_NAME));
                fieldObj.addProperty("value", truncate(field.value(), MAX_EMBED_FIELD_VALUE));
                fieldObj.addProperty("inline", field.inline());
                fieldsArray.add(fieldObj);
            }
            if (!fieldsArray.isEmpty()) {
                embed.add("fields", fieldsArray);
            }
        }

        if (footer != null && !footer.isBlank()) {
            final JsonObject footerObj = new JsonObject();
            footerObj.addProperty("text", truncate(footer, MAX_EMBED_FOOTER));
            embed.add("footer", footerObj);
        }

        // Add timestamp
        embed.addProperty("timestamp", java.time.Instant.now().toString());

        final JsonObject root = new JsonObject();
        final JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);

        return root.toString();
    }

    /**
     * Creates a simple embed with just description text.
     */
    public static String simple(final String description, final int color) {
        return embed(description, color, null, null, null, null);
    }

    /**
     * Creates an embed with title and description.
     */
    public static String titled(final String title, final String description, final int color) {
        return embed(description, color, title, null, null, null);
    }

    /**
     * Creates an embed with title, description, and thumbnail.
     */
    public static String withThumbnail(final String title, final String description, final int color, final String thumbnailUrl) {
        return embed(description, color, title, thumbnailUrl, null, null);
    }

    /**
     * Creates a payload with just content (no embed) - fallback for simple messages.
     */
    public static String content(final String content) {
        final JsonObject root = new JsonObject();
        root.addProperty("content", truncate(content, 1900));
        return root.toString();
    }

    /**
     * Parses an embed payload JSON and extracts the description for testing.
     */
    public static Optional<String> extractDescription(final String json) {
        try {
            final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            final JsonArray embeds = root.getAsJsonArray("embeds");
            if (embeds == null || embeds.isEmpty()) return Optional.empty();

            final JsonObject embed = embeds.get(0).getAsJsonObject();
            if (embed.has("description")) {
                return Optional.of(embed.get("description").getAsString());
            }
            return Optional.empty();
        } catch (final Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Extracts thumbnail URL from an embed payload for testing.
     */
    public static Optional<String> extractThumbnail(final String json) {
        try {
            final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            final JsonArray embeds = root.getAsJsonArray("embeds");
            if (embeds == null || embeds.isEmpty()) return Optional.empty();

            final JsonObject embed = embeds.get(0).getAsJsonObject();
            if (embed.has("thumbnail")) {
                final JsonObject thumb = embed.getAsJsonObject("thumbnail");
                if (thumb.has("url")) {
                    return Optional.of(thumb.get("url").getAsString());
                }
            }
            return Optional.empty();
        } catch (final Exception ignored) {
            return Optional.empty();
        }
    }

    public record Field(String name, String value, boolean inline) {}

    private static String truncate(final String input, final int maxLength) {
        if (input == null) return "";
        if (input.length() <= maxLength) return input;
        return input.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}