package org.autojs.autojs.pluginclient;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.util.MapBuilder;

import org.autojs.autojs.autojs.AutoJs;

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.PublishSubject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.autojs.autojs.pluginclient.Utils.val2json;

/**
 * Created by Stardust on 2017/5/11.
 */
public class DevPluginService {

    static final int CLIENT_VERSION = 2;
    private static final String LOG_TAG = "DevPluginService";
    private static final String TYPE_HELLO = "hello";
    private static final String TYPE_LOG = "log";
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
    private Map<String, Object> deviceInfo;
    private static AtomicInteger baseTaskMaxId = new AtomicInteger(0); // 任务的自增id， 这里的id+任务的id才是真正的id

    public static DevPluginService getInstance() {
        return sInstance;
    }

    private DevPluginService() {
        File cache = new File(GlobalAppContext.get().getCacheDir(), "remote_project");
        mResponseHandler = new DevPluginResponseHandler(cache);
        deviceInfo = Utils.getDeviceInfo();
        AutoJs.getInstance().getScriptEngineService().registerGlobalScriptExecutionListener(new TaskChangeListener(this));
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

    public Observable<Pair<Integer, JsonObject>> getSimpleCmd() {
        return mResponseHandler.getSimpleCmd();
    }

    public Observable<State> connectionState() {
        return mConnectionState;
    }

    public String getImei() {
        return Utils.getIMEI();
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
                .addQueryParameter("device_id", Utils.getIMEI())
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
        sendData(TYPE_HELLO, (JsonObject) val2json(deviceInfo));
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
    public void log(String log) {
        if (!isConnected())
            return;
        sendData(TYPE_LOG, (JsonObject) val2json(new MapBuilder<String, String>().put("log", log).build()));
    }

    @AnyThread
    public void sendData(String type, JsonObject data) {
        if (!isConnected())
            return;

        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("data", data);
        mSocket.write(json);
    }

    @AnyThread
    public void sendCommand(String command, JsonElement data) {
        if (!data.isJsonObject()) {
            JsonObject newObj = new JsonObject();
            newObj.add("data", data);
            data = newObj;
        }
        ((JsonObject) data).addProperty("command", command);
        sendData(TYPE_CMD, (JsonObject) data);
    }
}
