package org.autojs.autojs.pluginclient;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import android.util.Log;
import android.util.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.runtime.api.Device;
import com.stardust.util.MapBuilder;

import org.autojs.autojs.BuildConfig;

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.PublishSubject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Created by Stardust on 2017/5/11.
 */
public class DevPluginService {

    private static final int CLIENT_VERSION = 2;
    private static final String LOG_TAG = "DevPluginService";
    private static final String TYPE_HELLO = "hello";
    private static final String TYPE_CMD = "command";
    private static final long HANDSHAKE_TIMEOUT = 10 * 1000;

    public static class State {

        public static final int DISCONNECTED = 0;
        public static final int CONNECTING = 1;
        public static final int CONNECTED = 2;

        private final int mState;
        private final Throwable mException;

        public State(int state, Throwable exception) {
            mState = state;
            mException = exception;
        }

        public State(int state) {
            this(state, null);
        }

        public int getState() {
            return mState;
        }

        public Throwable getException() {
            return mException;
        }
    }

    private static DevPluginService sInstance = new DevPluginService();
    private final PublishSubject<State> mConnectionState = PublishSubject.create();
    private final DevPluginResponseHandler mResponseHandler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile JsonWebSocket mSocket;
    public Device device;
    private String deviceId;
    private Map<String, Object> deviceInfo;

    public static DevPluginService getInstance() {
        return sInstance;
    }

    public DevPluginService() {
        File cache = new File(GlobalAppContext.get().getCacheDir(), "remote_project");
        mResponseHandler = new DevPluginResponseHandler(cache);
        device = new Device(GlobalAppContext.get());
        deviceId = device.getIMEI();

        ArrayList<String> imeIs = device.getIMEIs();
        JsonArray imeisJ = new JsonArray();
        for (String i : imeIs) {
            imeisJ.add(i);
        }

        String mac = "";
        try {
            mac = device.getMacAddress();
        } catch (Exception ignored) {
        }

        deviceInfo = new MapBuilder<String, Object>()
                .put("CPU_ABI", Build.CPU_ABI)
                .put("CPU_ABI2", Build.CPU_ABI2)
                .put("manufacturer", Build.MANUFACTURER)
                .put("host", Build.HOST)
                .put("build_display", Device.buildDisplay)
                .put("product", Device.product)
                .put("device", Device.device)
                .put("board", Device.board)
                .put("brand", Device.brand)
                .put("model", Device.model)
                .put("bootloader", Device.bootloader)
                .put("hardware", Device.hardware)
                .put("incremental", Device.incremental)
                .put("release", Device.release)
                .put("build_id", Device.buildId)
                .put("width", Device.width)
                .put("height", Device.height)
                .put("base_OS", Device.baseOS)
                .put("security_patch", Device.securityPatch)
                .put("fingerprint", Device.fingerprint)
                .put("sdk_int", Device.sdkInt)
                .put("codename", Device.codename)
                .put("serial", device.getSerial())
                .put("IMEIs", imeisJ)
                .put("IMEI", device.getIMEI())
                .put("mac", mac)
                .put("android_id", device.getAndroidId())
                .put("device_name", Build.BRAND + " " + Build.MODEL)
                .put("device_id", deviceId)
                .put("client_version", CLIENT_VERSION)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .build();
    }

    @AnyThread
    public boolean isConnected() {
        return mSocket != null && !mSocket.isClosed();
    }

    @AnyThread
    public boolean isDisconnected() {
        return mSocket == null || mSocket.isClosed();
    }

    @AnyThread
    public void disconnectIfNeeded() {
        if (isDisconnected())
            return;
        disconnect();
    }

    @AnyThread
    public void disconnect() {
        mSocket.close();
        mSocket = null;
    }

    public Observable<State> connectionState() {
        return mConnectionState;
    }

    @AnyThread
    public Observable<JsonWebSocket> connectToServer(String host, int port) {
        String ip = host;
        int i = host.lastIndexOf(':');
        if (i > 0 && i < host.length() - 1) {
            port = Integer.parseInt(host.substring(i + 1));
            ip = host.substring(0, i);
        }
        mConnectionState.onNext(new State(State.CONNECTING));

        return socket(ip, port)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(this::onSocketError);
    }

    @AnyThread
    private Observable<JsonWebSocket> socket(String ip, int port) {
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        boolean ssl;
        ssl = ip.startsWith("wss://");
        if (ip.startsWith("ws://") || ip.startsWith("wss://")) {
            ip = ip.substring(ip.startsWith("ws://") ? 5 : 6);
        }
        String url = new HttpUrl.Builder()
                .scheme("http")
                .port(port)
                .host(ip)
                .addQueryParameter("device_id", deviceId)
                .toString().substring(4);
        url = (ssl ? "wss" : "ws") + url;
        return Observable.just(new JsonWebSocket(client, new Request.Builder().url(url).build()))
                .doOnNext(socket -> {
                    mSocket = socket;
                    subscribeMessage(socket);
                    sayHelloToServer(socket);
                });
    }

    @SuppressLint("CheckResult")
    private void subscribeMessage(JsonWebSocket socket) {
        socket.data()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnComplete(() -> {
                    mConnectionState.onNext(new State(State.DISCONNECTED));
                })
                .subscribe(data -> onSocketData(socket, data), this::onSocketError);
    }

    @MainThread
    private void onSocketError(Throwable e) {
        e.printStackTrace();
        if (mSocket != null) {
            mConnectionState.onNext(new State(State.DISCONNECTED, e));
            mSocket.close();
            mSocket = null;
        }
    }

    @MainThread
    private void onSocketData(JsonWebSocket jsonWebSocket, JsonElement element) {
        if (!element.isJsonObject()) {
            Log.w(LOG_TAG, "onSocketData: not json object: " + element);
            return;
        }
        try {
            JsonObject obj = element.getAsJsonObject();
            JsonElement typeElement = obj.get("type");
            if (typeElement == null || !typeElement.isJsonPrimitive()) {
                return;
            }
            String type = typeElement.getAsString();
            if (type.equals(TYPE_HELLO)) {
                onServerHello(jsonWebSocket, obj);
                return;
            }
            mResponseHandler.handle(obj);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @WorkerThread
    private void sayHelloToServer(JsonWebSocket socket) {
        sendData(TYPE_HELLO, deviceInfo);
        mHandler.postDelayed(() -> {
            if (mSocket != socket && !socket.isClosed()) {
                Log.i(LOG_TAG, "onHandshakeTimeout");
                mConnectionState.onNext(new State(State.DISCONNECTED, new SocketTimeoutException("handshake timeout")));
                socket.close();
            }
        }, HANDSHAKE_TIMEOUT);
    }

    @MainThread
    private void onServerHello(JsonWebSocket jsonWebSocket, JsonObject message) {
        Log.i(LOG_TAG, "onServerHello: " + message);
        mSocket = jsonWebSocket;
        mConnectionState.onNext(new State(State.CONNECTED));
    }

    @AnyThread
    private static boolean write(JsonWebSocket socket, String type, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("data", data);
        return socket.write(json);
    }

    @AnyThread
    private static boolean writePair(JsonWebSocket socket, String type, Pair<String, String> pair) {
        JsonObject data = new JsonObject();
        data.addProperty(pair.first, pair.second);
        return write(socket, type, data);
    }

    @AnyThread
    private static boolean writeMap(JsonWebSocket socket, String type, Map<String, ?> map) {
        JsonObject data = new JsonObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                data.addProperty(entry.getKey(), (String) value);
            } else if (value instanceof Character) {
                data.addProperty(entry.getKey(), (Character) value);
            } else if (value instanceof Number) {
                data.addProperty(entry.getKey(), (Number) value);
            } else if (value instanceof Boolean) {
                data.addProperty(entry.getKey(), (Boolean) value);
            } else if (value instanceof JsonElement) {
                data.add(entry.getKey(), (JsonElement) value);
            } else {
                throw new IllegalArgumentException("cannot put value " + value + " into json");
            }
        }
        return write(socket, type, data);
    }

    @SuppressLint("CheckResult")
    @AnyThread
    public void log(String log) {
        if (!isConnected())
            return;
        writePair(mSocket, "log", new Pair<>("log", log));
    }

    @SuppressLint("CheckResult")
    @AnyThread
    public void sendData(String type, Map<String, Object> data) {
        if (!isConnected())
            return;
        writeMap(mSocket, type, data);
    }

    public void sendCommand(String command, Map<String, Object> data) {
        data.put("command", command);
        sendData(TYPE_CMD, data);
    }
}
