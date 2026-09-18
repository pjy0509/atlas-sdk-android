package dev.appatlas.sample;

import android.app.Application;
import android.util.Log;

import dev.appatlas.sdk.Atlas;
import dev.appatlas.sdk.crash.AtlasCrash;

// SampleApplication.java: registered as android:name on <application>.
public class SampleApplication extends Application {

    static final String TAG = "AtlasSample";

    @Override
    public void onCreate() {
        super.onCreate();
        // A port nothing listens on: every envelope stays in the disk queue
        // for the device run to pull and verify against the server's parser.
        Atlas.start(this, "sdk_sample", "http://10.0.2.2:9");

        AtlasCrash.setUserId("u-sample");
        AtlasCrash.setKey("build", "sample");
        AtlasCrash.leaveBreadcrumb("app", "onCreate");
        AtlasCrash.log("application created");

        Log.i(TAG, "crashedLastRun=" + AtlasCrash.crashedLastRun());
    }
}
