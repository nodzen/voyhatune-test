package ru.big.town.restoremode;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * Lifecycle-owned connection to Native's privileged command service.
 *
 * <p>The old UI stored one process-global Messenger. Opening a settings
 * screen directly therefore worked only if the removed dashboard happened to be alive first. Each
 * visible owner now has an explicit client, and a dead Binder is rebound without retaining an
 * Activity globally.</p>
 */
final class NativeServiceClient implements AutoCloseable {
    private static final String TAG = "$$$ NativeServiceClient $$$";
    private static final long REBIND_DELAY_MS = 750L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable rebind = this::connect;
    private Messenger service;
    private boolean bindRequested;
    private boolean closed;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            service = new Messenger(binder);
            Log.i(TAG, "connected");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            scheduleRebind();
        }

        @Override public void onBindingDied(ComponentName name) {
            replaceBinding("binding died");
        }

        @Override public void onNullBinding(ComponentName name) {
            replaceBinding("null binding");
        }
    };

    NativeServiceClient(Context owner) {
        context = owner;
    }

    void connect() {
        if (closed || bindRequested) return;
        Intent intent = new Intent().setClassName(
                "ru.big.town.anative", "ru.big.town.anative.SetModesService");
        try {
            bindRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bindRequested) scheduleRebind();
        } catch (RuntimeException e) {
            Log.w(TAG, "bind failed: " + e.getMessage());
            bindRequested = false;
            scheduleRebind();
        }
    }

    boolean isConnected() {
        return service != null;
    }

    boolean send(int what) {
        return send(what, 0, null, null);
    }

    boolean send(int what, int arg1) {
        return send(what, arg1, null, null);
    }

    boolean send(int what, int arg1, Bundle data, Messenger replyTo) {
        Message message = Message.obtain(null, what, arg1, 0);
        if (data != null) message.setData(data);
        message.replyTo = replyTo;
        return send(message);
    }

    boolean send(Message message) {
        Messenger target = service;
        if (target == null || message == null) {
            connect();
            return false;
        }
        try {
            target.send(message);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
            replaceBinding("send failed");
            return false;
        }
    }

    private void replaceBinding(String reason) {
        main.removeCallbacks(rebind);
        service = null;
        if (bindRequested) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbind failed: " + e.getMessage());
            }
        }
        bindRequested = false;
        scheduleRebind();
    }

    private void scheduleRebind() {
        if (closed) return;
        main.removeCallbacks(rebind);
        main.postDelayed(rebind, REBIND_DELAY_MS);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacks(rebind);
        service = null;
        if (bindRequested) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, "close unbind failed: " + e.getMessage());
            }
        }
        bindRequested = false;
    }
}
