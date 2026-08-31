package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.IntConsumer;

/**
 * Подписка на OEM CarSignalService для машин, где ACC/MCU не прокидывается в android.car.
 *
 * CarSignalService вызывает зарегистрированный ICarSignalServiceCallBack one-way Binder-ом:
 * TX=3 — onPowerStateChanged(int), TX=4 — onACCStateChanged(int). На этой прошивке реальные
 * границы выключения/включения приходят как powerState=1/0 соответственно.
 */
final class CarSignalPowerBridge {

    private static final String TAG = "$$$ CarSignalPowerBridge $$$";
    private static final String CAR_SIGNAL_ACTION =
            "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_SIGNAL_PACKAGE = "com.qinggan.carsignal.service";
    private static final String CAR_SIGNAL_DESCRIPTOR =
            "com.qinggan.carsignal.ICarSignalService";
    private static final String CALLBACK_DESCRIPTOR =
            "com.qinggan.carsignal.ICarSignalServiceCallBack";

    private static final int TX_REGISTER_CALLBACK = 46;
    private static final int TX_UNREGISTER_CALLBACK = 47;
    private static final int CB_ON_POWER_STATE_CHANGED = 3;
    private static final int CB_ON_ACC_STATE_CHANGED = 4;
    private static final long BIND_RETRY_MS = 5_000L;

    private final Context context;
    private final Handler mainHandler;
    private final IntConsumer powerStateCallback;

    private HandlerThread ioThread;
    private Handler ioHandler;
    private volatile boolean stopped = true;
    private boolean bindingRequested;
    private boolean callbackRegistered;
    private long lastBindAttempt = -BIND_RETRY_MS;
    private long nextBindingGeneration;
    private long activeBindingGeneration;
    private long nextEpoch;
    private volatile long activeEpoch;
    private IBinder remote;
    private volatile PowerCallbackBinder callbackBinder;
    private ServiceConnection connection;

    private final Runnable rebindRunnable = this::ensureBoundOnIo;

    CarSignalPowerBridge(Context context, Handler mainHandler, IntConsumer powerStateCallback) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.powerStateCallback = powerStateCallback;
    }

    void start() {
        if (ioThread != null) return;
        stopped = false;
        ioThread = new HandlerThread("CarSignalPower");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        ioHandler.post(this::ensureBoundOnIo);
    }

    void stop() {
        stopped = true;
        Handler io = ioHandler;
        HandlerThread thread = ioThread;
        if (io != null && thread != null) {
            if (!io.post(() -> {
                releaseBindingOnIo("stop");
                thread.quitSafely();
            })) {
                thread.quitSafely();
            }
        } else if (thread != null) {
            thread.quitSafely();
        }
        ioHandler = null;
        ioThread = null;
    }

    private void ensureBoundOnIo() {
        if (stopped || bindingRequested) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBindAttempt < BIND_RETRY_MS) {
            scheduleRebindOnIo();
            return;
        }
        lastBindAttempt = now;
        long generation = ++nextBindingGeneration;
        ServiceConnection candidate = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (!isCurrent(generation) || stopped) return;
                ioHandler.removeCallbacks(rebindRunnable);
                remote = service;
                callbackRegistered = false;
                long epoch = ++nextEpoch;
                activeEpoch = epoch;
                callbackBinder = new PowerCallbackBinder(epoch);
                Log.i(TAG, "CarSignalService connected, alive=" + service.isBinderAlive());
                registerCallbackOnIo(service, callbackBinder, epoch);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (!isCurrent(generation)) return;
                Log.w(TAG, "CarSignalService disconnected");
                releaseBindingOnIo("disconnected");
                scheduleRebindOnIo();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (!isCurrent(generation)) return;
                Log.w(TAG, "CarSignalService binding died");
                releaseBindingOnIo("binding died");
                scheduleRebindOnIo();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                if (!isCurrent(generation)) return;
                Log.w(TAG, "CarSignalService returned null binding");
                releaseBindingOnIo("null binding");
                scheduleRebindOnIo();
            }
        };
        connection = candidate;
        activeBindingGeneration = generation;
        try {
            Intent intent = new Intent(CAR_SIGNAL_ACTION);
            intent.setPackage(CAR_SIGNAL_PACKAGE);
            boolean bound = context.bindService(intent, Context.BIND_AUTO_CREATE,
                    command -> {
                        Handler handler = ioHandler;
                        if (handler == null || !handler.post(command)) {
                            if (!stopped) Log.w(TAG, "CarSignal callback delivery dropped");
                        }
                    }, candidate);
            bindingRequested = bound;
            Log.i(TAG, "bindService returned " + bound);
            if (!bound) {
                connection = null;
                activeBindingGeneration = 0L;
                scheduleRebindOnIo();
            }
        } catch (RuntimeException e) {
            bindingRequested = false;
            connection = null;
            activeBindingGeneration = 0L;
            Log.e(TAG, "bindService failed: " + e.getMessage(), e);
            scheduleRebindOnIo();
        }
    }

    private boolean isCurrent(long generation) {
        return connection != null && activeBindingGeneration == generation;
    }

    private void scheduleRebindOnIo() {
        Handler io = ioHandler;
        if (stopped || io == null) return;
        io.removeCallbacks(rebindRunnable);
        io.postDelayed(rebindRunnable, BIND_RETRY_MS);
    }

    private void registerCallbackOnIo(IBinder service, PowerCallbackBinder callback,
                                      long epoch) {
        if (stopped || service != remote || callback != callbackBinder || epoch != activeEpoch) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!service.transact(TX_REGISTER_CALLBACK, data, reply, 0)) {
                throw new RemoteException("registerCallback returned false");
            }
            reply.readException();
            callbackRegistered = true;
            Log.i(TAG, "registerCallback: OK (TX=" + TX_REGISTER_CALLBACK + ")");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "registerCallback failed: " + e.getMessage());
            releaseBindingOnIo("register failed");
            scheduleRebindOnIo();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void releaseBindingOnIo(String reason) {
        Handler io = ioHandler;
        if (io != null) io.removeCallbacks(rebindRunnable);

        ServiceConnection oldConnection = connection;
        IBinder oldRemote = remote;
        PowerCallbackBinder oldCallback = callbackBinder;
        boolean wasRegistered = callbackRegistered;

        connection = null;
        activeBindingGeneration = 0L;
        bindingRequested = false;
        callbackRegistered = false;
        remote = null;
        callbackBinder = null;
        activeEpoch = 0L;

        if (wasRegistered && oldRemote != null && oldCallback != null) {
            unregisterCallback(oldRemote, oldCallback);
        }
        if (oldConnection != null) {
            try {
                context.unbindService(oldConnection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbindService failed: " + e.getMessage());
            }
        }
    }

    private void unregisterCallback(IBinder service, IBinder callback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_SIGNAL_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (service.transact(TX_UNREGISTER_CALLBACK, data, reply, 0)) {
                reply.readException();
                Log.i(TAG, "unregisterCallback: OK");
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "unregisterCallback failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private final class PowerCallbackBinder extends Binder {
        private final long epoch;

        PowerCallbackBinder(long epoch) {
            this.epoch = epoch;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CB_ON_POWER_STATE_CHANGED) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                int state = data.readInt();
                dispatchPowerState(epoch, state);
                return true;
            }
            if (code == CB_ON_ACC_STATE_CHANGED) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                int state = data.readInt();
                Log.i(TAG, "onACCStateChanged=" + state);
                return true;
            }
            // Остальные OEM callbacks нам не нужны, но их надо поглощать: иначе vendor Binder
            // получает UNKNOWN_TRANSACTION на каждый чужой callback.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private void dispatchPowerState(long epoch, int state) {
        if (stopped || activeEpoch != epoch || callbackBinder == null
                || callbackBinder.epoch != epoch) {
            return;
        }
        Handler main = mainHandler;
        if (!main.post(() -> {
            if (!stopped && activeEpoch == epoch) powerStateCallback.accept(state);
        })) {
            Log.w(TAG, "power callback delivery to main dropped");
        }
    }
}
