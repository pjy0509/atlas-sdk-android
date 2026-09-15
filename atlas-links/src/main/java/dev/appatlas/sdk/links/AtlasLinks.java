package dev.appatlas.sdk.links;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.appatlas.sdk.Atlas;
import dev.appatlas.sdk.core.AtlasCore;
import dev.appatlas.sdk.core.Json;
import dev.appatlas.sdk.core.JsonReader;

/**
 * The links module: deferred deep links claimed from the install referrer,
 * direct opens handed in from the activity, one listener for both. Attach in
 * Application.onCreate, after Atlas.start:
 *
 * <pre>
 * Atlas.start(this, "sdk_…");
 * AtlasLinks.setListener(link -&gt; route(link));
 * // and from the launcher activity:
 * AtlasLinks.handle(getIntent());
 * </pre>
 */
public final class AtlasLinks {

    private static final String PREFS = "dev.appatlas.sdk";
    private static final String KEY_REFERRER_DONE = "links.referrerDone";
    private static final String KEY_FIRST_LINK = "links.firstLink";
    // Stamped on a consumed intent: recents re-serves old intents, and a
    // link already routed must not route twice.
    private static final String EXTRA_USED = "dev.appatlas.sdk.linkUsed";

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context app;
    private static AtlasLinkListener listener;
    // Links that arrived before the listener did — the cold-start tap lands
    // milliseconds before application code can register.
    private static final List<AtlasLink> QUEUE = new ArrayList<AtlasLink>();

    private AtlasLinks() {
    }

    /** Called by Atlas.start through reflection; not application API. */
    public static void boot(Context context) {
        synchronized (LOCK) {
            if (app != null) {
                return;
            }

            app = context.getApplicationContext();
        }

        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Once ever, ack-gated: the flag is written only after the server
        // answered, so a launch that died mid-claim simply asks again. The
        // flag rides app backup on purpose — a restored device is not an
        // install (Branch's policy, the honest one).
        if (!prefs.getBoolean(KEY_REFERRER_DONE, false)) {
            new ReferrerFetcher(app, new ReferrerFetcher.Callback() {
                @Override
                public void onReferrer(String referrer) {
                    claim(ReferrerParser.clickToken(referrer));
                }

                @Override
                public void onRetryLater() {
                    // Leave the flag unset; next launch retries.
                }
            }).fetch();
        }
    }

    public static void setListener(AtlasLinkListener next) {
        List<AtlasLink> replay;

        synchronized (LOCK) {
            listener = next;
            replay = new ArrayList<AtlasLink>(QUEUE);
            QUEUE.clear();
        }

        for (AtlasLink link : replay) {
            deliver(link);
        }
    }

    /** The link that survived the install, set once ever; null before then. */
    public static AtlasLink firstReferringLink() {
        Context context = app;

        if (context == null) {
            return null;
        }

        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FIRST_LINK, null);

        return stored == null ? null : fromStored(JsonReader.object(stored));
    }

    /** Hand in the launcher activity's intent; safe to call on every start. */
    public static void handle(Intent intent) {
        if (intent == null || app == null) {
            return;
        }

        // A relaunch from recents re-serves last week's intent; a consumed
        // link is stamped so re-delivery routes nothing.
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
                || intent.getBooleanExtra(EXTRA_USED, false)) {
            return;
        }

        Uri data = intent.getData();
        LinkUrl link = data == null ? null : LinkUrl.parse(data.toString());

        if (link == null) {
            return;
        }

        intent.putExtra(EXTRA_USED, true);

        AtlasCore core = Atlas.core();

        if (core != null) {
            // The funnel's third step: an installed app, opened by the link.
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("eventId", AtlasCore.newEventId());
            payload.put("shortId", link.shortId);

            if (link.channel != null) {
                payload.put("channel", link.channel);
            }
            if (link.campaign != null) {
                payload.put("campaign", link.campaign);
            }
            if (link.pushId != null || "push".equals(link.source)) {
                payload.put("source", "push");
            }

            core.enqueue("open", payload);
        }

        // A direct open carries what the URL carries; the saved payload rides
        // only the deferred claim.
        deliver(new AtlasLink(new LinkedHashMap<String, Object>(), null, link.shortId,
                link.channel, link.campaign, null, false, null));
    }

    private static void claim(final String token) {
        final AtlasCore core = Atlas.core();

        if (core == null) {
            return;
        }

        if (token == null) {
            // Organic or foreign referrer: answered, and the answer is "no link".
            markDone();

            return;
        }

        // Its own short thread: a claim must not sit in front of envelope
        // flushes on the core worker.
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                ClaimClient.Answer answer = new ClaimClient(core.baseUrl()).claim(token, core.installId(), "referrer");

                if (answer.outcome == ClaimClient.Outcome.RETRY) {
                    return;
                }

                markDone();

                if (answer.link != null) {
                    storeFirstLink(answer.link);
                    deliver(answer.link);
                }
            }
        }, "atlas-links-claim");
        worker.setDaemon(true);
        worker.start();
    }

    private static void deliver(final AtlasLink link) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                AtlasLinkListener current;

                synchronized (LOCK) {
                    current = listener;

                    if (current == null) {
                        QUEUE.add(link);

                        return;
                    }
                }

                current.onLink(link);
            }
        });
    }

    private static void markDone() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REFERRER_DONE, true).apply();
    }

    private static void storeFirstLink(AtlasLink link) {
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("payload", link.payload);
        stored.put("path", link.path);
        stored.put("channel", link.channel);
        stored.put("campaign", link.campaign);
        stored.put("clickedAt", link.clickedAt);
        stored.put("match", link.match);

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_FIRST_LINK, Json.write(stored)).apply();
    }

    @SuppressWarnings("unchecked")
    private static AtlasLink fromStored(Map<String, Object> stored) {
        Object payload = stored.get("payload");

        return new AtlasLink(
                payload instanceof Map ? (Map<String, Object>) payload : new LinkedHashMap<String, Object>(),
                (String) stored.get("path"),
                null,
                (String) stored.get("channel"),
                (String) stored.get("campaign"),
                (String) stored.get("clickedAt"),
                true,
                (String) stored.get("match")
        );
    }
}
