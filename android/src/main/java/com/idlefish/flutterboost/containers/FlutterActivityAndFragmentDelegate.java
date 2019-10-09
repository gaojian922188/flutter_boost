package com.idlefish.flutterboost.containers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.arch.lifecycle.Lifecycle;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.idlefish.flutterboost.BoostFlutterView;
import com.idlefish.flutterboost.FlutterBoost;
import com.idlefish.flutterboost.NewFlutterBoost;
import com.idlefish.flutterboost.interfaces.IFlutterViewContainer;
import com.idlefish.flutterboost.interfaces.IOperateSyncer;
import io.flutter.Log;
import io.flutter.app.FlutterActivity;
import io.flutter.embedding.android.*;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterShellArgs;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.renderer.OnFirstFrameRenderedListener;
import io.flutter.plugin.platform.PlatformPlugin;
import io.flutter.view.FlutterMain;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;

public class FlutterActivityAndFragmentDelegate  implements IFlutterViewContainer {


    private static final String TAG = "FlutterActivityAndFragmentDelegate";

    @NonNull
    private Host host;
    @Nullable
    private FlutterEngine flutterEngine;
    @Nullable
    private FlutterSplashView flutterSplashView;
    @Nullable
    private FlutterView flutterView;
    @Nullable
    private PlatformPlugin platformPlugin;

    private boolean isFlutterEngineFromHost;


    protected IOperateSyncer mSyncer;


    @NonNull
    private final OnFirstFrameRenderedListener onFirstFrameRenderedListener = new OnFirstFrameRenderedListener() {
        @Override
        public void onFirstFrameRendered() {
            host.onFirstFrameRendered();
        }
    };

    FlutterActivityAndFragmentDelegate(@NonNull Host host) {
        this.host = host;
    }

    void release() {
        this.host = null;
        this.flutterEngine = null;
        this.flutterView = null;
        this.platformPlugin = null;
    }


    @Nullable
    FlutterEngine getFlutterEngine() {
        return flutterEngine;
    }


    void onAttach(@NonNull Context context) {
        ensureAlive();

        initializeFlutter(context);

        // When "retain instance" is true, the FlutterEngine will survive configuration
        // changes. Therefore, we create a new one only if one does not already exist.
        if (flutterEngine == null) {
            setupFlutterEngine();
        }

        // Regardless of whether or not a FlutterEngine already existed, the PlatformPlugin
        // is bound to a specific Activity. Therefore, it needs to be created and configured
        // every time this Fragment attaches to a new Activity.
        // TODO(mattcarroll): the PlatformPlugin needs to be reimagined because it implicitly takes
        //                    control of the entire window. This is unacceptable for non-fullscreen
        //                    use-cases.
        platformPlugin = host.providePlatformPlugin(host.getActivity(), flutterEngine);

        if (host.shouldAttachEngineToActivity()) {
            // Notify any plugins that are currently attached to our FlutterEngine that they
            // are now attached to an Activity.
            //
            // Passing this Fragment's Lifecycle should be sufficient because as long as this Fragment
            // is attached to its Activity, the lifecycles should be in sync. Once this Fragment is
            // detached from its Activity, that Activity will be detached from the FlutterEngine, too,
            // which means there shouldn't be any possibility for the Fragment Lifecycle to get out of
            // sync with the Activity. We use the Fragment's Lifecycle because it is possible that the
            // attached Activity is not a LifecycleOwner.
            Log.d(TAG, "Attaching FlutterEngine to the Activity that owns this Fragment.");
            flutterEngine.getActivityControlSurface().attachToActivity(
                    host.getActivity(),
                    host.getLifecycle()
            );
        }

        host.configureFlutterEngine(flutterEngine);
    }

    private void initializeFlutter(@NonNull Context context) {
        FlutterMain.ensureInitializationComplete(
                context.getApplicationContext(),
                host.getFlutterShellArgs().toArray()
        );
    }


    private void setupFlutterEngine() {
        Log.d(TAG, "Setting up FlutterEngine.");

        // First, check if the host wants to use a cached FlutterEngine.
        String cachedEngineId = host.getCachedEngineId();
        if (cachedEngineId != null) {
            flutterEngine = FlutterEngineCache.getInstance().get(cachedEngineId);
            isFlutterEngineFromHost = true;
            if (flutterEngine == null) {
                throw new IllegalStateException("The requested cached FlutterEngine did not exist in the FlutterEngineCache: '" + cachedEngineId + "'");
            }
            return;
        }

        // Second, defer to subclasses for a custom FlutterEngine.
        flutterEngine = host.provideFlutterEngine(host.getContext());
        if (flutterEngine != null) {
            isFlutterEngineFromHost = true;
            return;
        }

        // Our host did not provide a custom FlutterEngine. Create a FlutterEngine to back our
        // FlutterView.
        Log.d(TAG, "No preferred FlutterEngine was provided. Creating a new FlutterEngine for"
                + " this FlutterFragment.");
        flutterEngine = new FlutterEngine(host.getContext());
        isFlutterEngineFromHost = false;
    }


    @SuppressLint("ResourceType")
    @NonNull
    View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.v(TAG, "Creating FlutterView.");

        mSyncer = NewFlutterBoost.instance().containerManager().generateSyncer(this);

        ensureAlive();
        flutterView = new FlutterView(host.getActivity(), host.getRenderMode(), host.getTransparencyMode());
        flutterView.addOnFirstFrameRenderedListener(onFirstFrameRenderedListener);

        flutterSplashView = new FlutterSplashView(host.getContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            flutterSplashView.setId(View.generateViewId());
        } else {
            // TODO(mattcarroll): Find a better solution to this ID. This is a random, static ID.
            // It might conflict with other Views, and it means that only a single FlutterSplashView
            // can exist in a View hierarchy at one time.
            flutterSplashView.setId(486947586);
        }
        flutterSplashView.displayFlutterViewWithSplash(flutterView, host.provideSplashScreen());
        mSyncer.onCreate();
        return flutterSplashView;
    }


    void onStart() {
        Log.v(TAG, "onStart()");
        ensureAlive();

        // We post() the code that attaches the FlutterEngine to our FlutterView because there is
        // some kind of blocking logic on the native side when the surface is connected. That lag
        // causes launching Activitys to wait a second or two before launching. By post()'ing this
        // behavior we are able to move this blocking logic to after the Activity's launch.
        // TODO(mattcarroll): figure out how to avoid blocking the MAIN thread when connecting a surface
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "Attaching FlutterEngine to FlutterView.");
                flutterView.attachToFlutterEngine(flutterEngine);

                doInitialFlutterViewRun();
            }
        });
    }


    private void doInitialFlutterViewRun() {
        // Don't attempt to start a FlutterEngine if we're using a cached FlutterEngine.
        if (host.getCachedEngineId() != null) {
            return;
        }

        if (flutterEngine.getDartExecutor().isExecutingDart()) {
            // No warning is logged because this situation will happen on every config
            // change if the developer does not choose to retain the Fragment instance.
            // So this is expected behavior in many cases.
            return;
        }

        Log.d(TAG, "Executing Dart entrypoint: " + host.getDartEntrypointFunctionName()
                + ", and sending initial route: " + host.getInitialRoute());

        // The engine needs to receive the Flutter app's initial route before executing any
        // Dart code to ensure that the initial route arrives in time to be applied.
        if (host.getInitialRoute() != null) {
            flutterEngine.getNavigationChannel().setInitialRoute(host.getInitialRoute());
        }

        // Configure the Dart entrypoint and execute it.
        DartExecutor.DartEntrypoint entrypoint = new DartExecutor.DartEntrypoint(
                host.getAppBundlePath(),
                host.getDartEntrypointFunctionName()
        );
        flutterEngine.getDartExecutor().executeDartEntrypoint(entrypoint);
    }


    void onResume() {
        mSyncer.onAppear();

        Log.v(TAG, "onResume()");
        ensureAlive();
        flutterEngine.getLifecycleChannel().appIsResumed();
    }


    void onPostResume() {
        Log.v(TAG, "onPostResume()");
        ensureAlive();
        if (flutterEngine != null) {
            if (platformPlugin != null) {
                // TODO(mattcarroll): find a better way to handle the update of UI overlays than calling through
                //                    to platformPlugin. We're implicitly entangling the Window, Activity, Fragment,
                //                    and engine all with this one call.
                platformPlugin.updateSystemUiOverlays();
            }
        } else {
            Log.w(TAG, "onPostResume() invoked before FlutterFragment was attached to an Activity.");
        }
    }


    void onPause() {
        Log.v(TAG, "onPause()");
        mSyncer.onDisappear();

        ensureAlive();
        flutterEngine.getLifecycleChannel().appIsInactive();
    }


    void onStop() {
        Log.v(TAG, "onStop()");
        ensureAlive();
        flutterEngine.getLifecycleChannel().appIsPaused();
        flutterView.detachFromFlutterEngine();
    }

    void onDestroyView() {
        Log.v(TAG, "onDestroyView()");
        mSyncer.onDestroy();

        ensureAlive();
        flutterView.removeOnFirstFrameRenderedListener(onFirstFrameRenderedListener);
    }


    void onDetach() {
        Log.v(TAG, "onDetach()");
        ensureAlive();

        if (host.shouldAttachEngineToActivity()) {
            // Notify plugins that they are no longer attached to an Activity.
            Log.d(TAG, "Detaching FlutterEngine from the Activity that owns this Fragment.");
            if (host.getActivity().isChangingConfigurations()) {
                flutterEngine.getActivityControlSurface().detachFromActivityForConfigChanges();
            } else {
                flutterEngine.getActivityControlSurface().detachFromActivity();
            }
        }

        // Null out the platformPlugin to avoid a possible retain cycle between the plugin, this Fragment,
        // and this Fragment's Activity.
        if (platformPlugin != null) {
            platformPlugin.destroy();
            platformPlugin = null;
        }

        // Destroy our FlutterEngine if we're not set to retain it.
        if (host.shouldDestroyEngineWithHost()) {
            flutterEngine.destroy();

            if (host.getCachedEngineId() != null) {
                FlutterEngineCache.getInstance().remove(host.getCachedEngineId());
            }

            flutterEngine = null;
        }
    }


    void onBackPressed() {
        mSyncer.onBackPressed();

        ensureAlive();
//        if (flutterEngine != null) {
//            Log.v(TAG, "Forwarding onBackPressed() to FlutterEngine.");
//            flutterEngine.getNavigationChannel().popRoute();
//        } else {
//            Log.w(TAG, "Invoked onBackPressed() before FlutterFragment was attached to an Activity.");
//        }
    }


    void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        mSyncer.onRequestPermissionsResult(requestCode, permissions, grantResults);

        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onRequestPermissionsResult() to FlutterEngine:\n"
                    + "requestCode: " + requestCode + "\n"
                    + "permissions: " + Arrays.toString(permissions) + "\n"
                    + "grantResults: " + Arrays.toString(grantResults));
            flutterEngine.getActivityControlSurface().onRequestPermissionsResult(requestCode, permissions, grantResults);
        } else {
            Log.w(TAG, "onRequestPermissionResult() invoked before FlutterFragment was attached to an Activity.");
        }
    }


    void onNewIntent(@NonNull Intent intent) {
        mSyncer.onNewIntent(intent);

        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onNewIntent() to FlutterEngine.");
            flutterEngine.getActivityControlSurface().onNewIntent(intent);
        } else {
            Log.w(TAG, "onNewIntent() invoked before FlutterFragment was attached to an Activity.");
        }
    }


    void onActivityResult(int requestCode, int resultCode, Intent data) {
        mSyncer.onActivityResult(requestCode,resultCode,data);
        Map<String,Object> result = null;
        if(data != null) {
            Serializable rlt = data.getSerializableExtra(RESULT_KEY);
            if(rlt instanceof Map) {
                result = (Map<String,Object>)rlt;
            }
        }

        mSyncer.onContainerResult(requestCode,resultCode,result);


        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onActivityResult() to FlutterEngine:\n"
                    + "requestCode: " + requestCode + "\n"
                    + "resultCode: " + resultCode + "\n"
                    + "data: " + data);
            flutterEngine.getActivityControlSurface().onActivityResult(requestCode, resultCode, data);
        } else {
            Log.w(TAG, "onActivityResult() invoked before FlutterFragment was attached to an Activity.");
        }
    }


    void onUserLeaveHint() {
        ensureAlive();
        if (flutterEngine != null) {
            Log.v(TAG, "Forwarding onUserLeaveHint() to FlutterEngine.");
            flutterEngine.getActivityControlSurface().onUserLeaveHint();
        } else {
            Log.w(TAG, "onUserLeaveHint() invoked before FlutterFragment was attached to an Activity.");
        }
    }


    void onTrimMemory(int level) {
        mSyncer.onTrimMemory(level);

        ensureAlive();
        if (flutterEngine != null) {
            // Use a trim level delivered while the application is running so the
            // framework has a chance to react to the notification.
            if (level == TRIM_MEMORY_RUNNING_LOW) {
                Log.v(TAG, "Forwarding onTrimMemory() to FlutterEngine. Level: " + level);
                flutterEngine.getSystemChannel().sendMemoryPressureWarning();
            }
        } else {
            Log.w(TAG, "onTrimMemory() invoked before FlutterFragment was attached to an Activity.");
        }
    }

    void onLowMemory() {
        Log.v(TAG, "Forwarding onLowMemory() to FlutterEngine.");
        mSyncer.onLowMemory();

        ensureAlive();
        flutterEngine.getSystemChannel().sendMemoryPressureWarning();
    }

    /**
     * Ensures that this delegate has not been {@link #release()}'ed.
     * <p>
     * An {@code IllegalStateException} is thrown if this delegate has been {@link #release()}'ed.
     */
    private void ensureAlive() {
        if (host == null) {
            throw new IllegalStateException("Cannot execute method on a destroyed FlutterActivityAndFragmentDelegate.");
        }
    }

    @Override
    public Activity getContextActivity() {
        return (Activity)this.host;
    }

    @Override
    public FlutterSplashView getBoostFlutterView() {
        return this.flutterSplashView;
    }

    @Override
    public void finishContainer(Map<String, Object> result) {
        Activity activity= (Activity)this.host;

        activity.finish();
    }

    @Override
    public String getContainerUrl() {
        return "flutterPage";
    }

    @Override
    public Map getContainerUrlParams() {
        return null;
    }

    @Override
    public void onContainerShown() {

    }

    @Override
    public void onContainerHidden() {

    }

    /**
     * The {@link FlutterActivity} or {@link FlutterFragment} that owns this
     * {@code FlutterActivityAndFragmentDelegate}.
     */
    /* package */ interface Host extends SplashScreenProvider, FlutterEngineProvider, FlutterEngineConfigurator {
        /**
         * Returns the {@link Context} that backs the host {@link Activity} or {@code Fragment}.
         */
        @NonNull
        Context getContext();

        /**
         * Returns the host {@link Activity} or the {@code Activity} that is currently attached
         * to the host {@code Fragment}.
         */
        @Nullable
        Activity getActivity();

        /**
         * Returns the {@link Lifecycle} that backs the host {@link Activity} or {@code Fragment}.
         */
        @NonNull
        Lifecycle getLifecycle();

        /**
         * Returns the {@link FlutterShellArgs} that should be used when initializing Flutter.
         */
        @NonNull
        FlutterShellArgs getFlutterShellArgs();

        /**
         * Returns the ID of a statically cached {@link FlutterEngine} to use within this
         * delegate's host, or {@code null} if this delegate's host does not want to
         * use a cached {@link FlutterEngine}.
         */
        @Nullable
        String getCachedEngineId();

        /**
         * Returns true if the {@link FlutterEngine} used in this delegate should be destroyed
         * when the host/delegate are destroyed.
         * <p>
         * The default value is {@code true} in cases where {@code FlutterFragment} created its own
         * {@link FlutterEngine}, and {@code false} in cases where a cached {@link FlutterEngine} was
         * provided.
         */
        boolean shouldDestroyEngineWithHost();

        /**
         * Returns the Dart entrypoint that should run when a new {@link FlutterEngine} is
         * created.
         */
        @NonNull
        String getDartEntrypointFunctionName();

        /**
         * Returns the path to the app bundle where the Dart code exists.
         */
        @NonNull
        String getAppBundlePath();

        /**
         * Returns the initial route that Flutter renders.
         */
        @Nullable
        String getInitialRoute();

        /**
         * Returns the {@link FlutterView.RenderMode} used by the {@link FlutterView} that
         * displays the {@link FlutterEngine}'s content.
         */
        @NonNull
        FlutterView.RenderMode getRenderMode();

        /**
         * Returns the {@link FlutterView.TransparencyMode} used by the {@link FlutterView} that
         * displays the {@link FlutterEngine}'s content.
         */
        @NonNull
        FlutterView.TransparencyMode getTransparencyMode();

        @Nullable
        SplashScreen provideSplashScreen();

        /**
         * Returns the {@link FlutterEngine} that should be rendered to a {@link FlutterView}.
         * <p>
         * If {@code null} is returned, a new {@link FlutterEngine} will be created automatically.
         */
        @Nullable
        FlutterEngine provideFlutterEngine(@NonNull Context context);

        /**
         * Hook for the host to create/provide a {@link PlatformPlugin} if the associated
         * Flutter experience should control system chrome.
         */
        @Nullable
        PlatformPlugin providePlatformPlugin(@Nullable Activity activity, @NonNull FlutterEngine flutterEngine);

        /**
         * Hook for the host to configure the {@link FlutterEngine} as desired.
         */
        void configureFlutterEngine(@NonNull FlutterEngine flutterEngine);

        /**
         * Returns true if the {@link FlutterEngine}'s plugin system should be connected to the
         * host {@link Activity}, allowing plugins to interact with it.
         */
        boolean shouldAttachEngineToActivity();

        /**
         * Invoked by this delegate when its {@link FlutterView} has rendered its first Flutter
         * frame.
         */
        void onFirstFrameRendered();
    }


}
