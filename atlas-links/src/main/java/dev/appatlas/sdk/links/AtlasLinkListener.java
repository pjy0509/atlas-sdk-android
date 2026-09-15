package dev.appatlas.sdk.links;

/** The single callback: direct opens and deferred (post-install) links land
 *  in the same place. Registered late, it still receives what arrived first —
 *  the cold-start tap is queued, never lost. */
public interface AtlasLinkListener {

    void onLink(AtlasLink link);
}
