package dev.appatlas.sdk.links;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One read of the Play install referrer, with every branch the incumbents
 * learned the hard way: endConnection in every outcome including the read's
 * own RemoteException, a completion guard because onInstallReferrerService-
 * Disconnected also fires after success, and retries only for the transient
 * codes — FEATURE_NOT_SUPPORTED is forever.
 */
final class ReferrerFetcher {

    interface Callback {
        /** The raw referrer string, or null when this device has none to give. */
        void onReferrer(String referrer);
        /** Transient failure: worth asking again next launch. */
        void onRetryLater();
    }

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 3_000L;

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private int attempts;

    ReferrerFetcher(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    void fetch() {
        try {
            connect();
        } catch (NoClassDefFoundError absent) {
            // The app excluded the installreferrer dependency: it simply
            // has no deferred deep links.
            settle(null);
        }
    }

    private void connect() {
        final InstallReferrerClient client = InstallReferrerClient.newBuilder(context).build();

        client.startConnection(new InstallReferrerStateListener() {
            @Override
            public void onInstallReferrerSetupFinished(int code) {
                switch (code) {
                    case InstallReferrerClient.InstallReferrerResponse.OK:
                        String referrer = null;

                        try {
                            referrer = client.getInstallReferrer().getInstallReferrer();
                        } catch (RemoteException flaky) {
                            end(client);
                            retry();

                            return;
                        }

                        end(client);
                        settle(referrer);

                        return;
                    case InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE:
                    case InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED:
                    case InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR:
                        end(client);
                        retry();

                        return;
                    default:
                        // FEATURE_NOT_SUPPORTED and anything unknown: this
                        // device will never answer.
                        end(client);
                        settle(null);
                }
            }

            @Override
            public void onInstallReferrerServiceDisconnected() {
                // Fires after a successful read too; only an unsettled fetch
                // treats it as a failure.
                if (!settled.get()) {
                    retry();
                }
            }
        });
    }

    private void end(InstallReferrerClient client) {
        try {
            client.endConnection();
        } catch (RuntimeException ignored) {
            // Ending a dead connection must not become its own failure.
        }
    }

    private void retry() {
        if (settled.get()) {
            return;
        }

        if (++attempts >= MAX_RETRIES) {
            if (settled.compareAndSet(false, true)) {
                callback.onRetryLater();
            }

            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!settled.get()) {
                    connect();
                }
            }
        }, RETRY_DELAY_MS);
    }

    private void settle(String referrer) {
        if (settled.compareAndSet(false, true)) {
            callback.onReferrer(referrer);
        }
    }
}
