package dev.appatlas.sdk.links;

import java.util.Collections;
import java.util.Map;

/**
 * What the one listener receives, direct and deferred alike: the link's own
 * payload plus how it arrived. The unified shape is the lesson of AppsFlyer's
 * UDL and Kochava's processDeeplink — two code paths for one concept is the
 * integration bug factory.
 */
public final class AtlasLink {

    /** The link's custom key-values, exactly as saved on the link. */
    public final Map<String, Object> payload;
    /** The deep-link path for this platform, when the link names one. */
    public final String path;
    public final String shortId;
    public final String channel;
    public final String campaign;
    /// When the click that produced this link happened (ISO 8601); null on a
    /// direct open, which has no click behind it.
    public final String clickedAt;
    /** True when this link survived an install (a claimed store handoff). */
    public final boolean deferred;
    /** referrer, campaign_id, clipboard, relink — or null for a direct open. */
    public final String match;

    AtlasLink(Map<String, Object> payload, String path, String shortId,
              String channel, String campaign, String clickedAt, boolean deferred, String match) {
        this.payload = Collections.unmodifiableMap(payload);
        this.path = path;
        this.shortId = shortId;
        this.channel = channel;
        this.campaign = campaign;
        this.clickedAt = clickedAt;
        this.deferred = deferred;
        this.match = match;
    }
}
