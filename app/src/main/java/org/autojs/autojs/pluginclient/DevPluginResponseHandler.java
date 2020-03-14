package org.autojs.autojs.pluginclient;

import android.text.TextUtils;
import android.util.Pair;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.pio.PFiles;

import org.autojs.autojs.Pref;
import org.autojs.autojs.R;
import org.autojs.autojs.autojs.AutoJs;
import org.autojs.autojs.model.script.Scripts;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

import static org.autojs.autojs.pluginclient.Utils.json2val;

/**
 * Created by Stardust on 2017/5/11.
 */
public class DevPluginResponseHandler implements Handler {
    private final PublishSubject<Pair<Integer, JsonObject>> simpleCmd = PublishSubject.create();

    public static final int SIMPLE_CMD_FORCE_CLOSE = 1;

    Observable<Pair<Integer, JsonObject>> getSimpleCmd() {
        return simpleCmd;
    }

    private Router mRouter = new Router.RootRouter("type")
            .handler("command", new Router("command")
                    .handler("force-close", data -> {
                        simpleCmd.onNext(new Pair<>(SIMPLE_CMD_FORCE_CLOSE, data));
                        return true;
                    })
                    .handler("run", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        String id = getId(data);
                        ExecutionConfig config = getExecutionConfig(data);
                        runScript(id, name, script, config);
                        return true;
                    })
                    .handler("onlyRun", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        String id = getId(data);
                        ExecutionConfig config = getExecutionConfig(data);

                        AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
                        runScript(id, name, script, config);
                        return true;
                    })
                    .handler("rerun", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        String id = getId(data);
                        ExecutionConfig config = getExecutionConfig(data);

                        stopScript(id);
                        runScript(id, name, script, config);
                        return true;
                    })
                    .handler("stop", data -> {
                        String id = getId(data);
                        stopScript(id);
                        return true;
                    })
                    .handler("stopAll", data -> {
                        AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
                        return true;
                    })
                    .handler("save", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        saveScript(name, script);
                        return true;
                    })
            );

    private HashMap<String, ArrayList<ScriptExecution>> mScriptExecutions = new HashMap<>();

    DevPluginResponseHandler(File cacheDir) {
        if (cacheDir.exists()) {
            if (cacheDir.isDirectory()) {
                PFiles.deleteFilesOfDir(cacheDir);
            } else {
                cacheDir.delete();
                cacheDir.mkdirs();
            }
        }
    }

    @Override
    public boolean handle(JsonObject data) {
        return mRouter.handle(data);
    }

    private void runScript(String viewId, String name, String script, ExecutionConfig executionConfig) {
        if (TextUtils.isEmpty(name)) {
            name = "[" + viewId + "]";
        } else {
            name = PFiles.getNameWithoutExtension(name);
        }

        ArrayList<ScriptExecution> executions = mScriptExecutions.get(viewId);
        if (executions == null) {
            executions = new ArrayList<>();
            mScriptExecutions.put(viewId, executions);
        }

        executions.add(Scripts.INSTANCE.run(new StringScriptSource("[remote]" + name, script), executionConfig));
    }

    private void stopScript(String viewId) {
        ArrayList<ScriptExecution> executions = mScriptExecutions.get(viewId);
        if (executions != null) {
            for (ScriptExecution execution : executions) {
                execution.getEngine().forceStop();
            }
            mScriptExecutions.remove(viewId);
        }
    }

    private ExecutionConfig getExecutionConfig(JsonObject data) {
        ExecutionConfig config;
        JsonElement element = data.get("executionConfig");
        if (element.isJsonObject()) {
            config = new Gson().fromJson(element.getAsJsonObject(), ExecutionConfig.class);
        } else {
            config = new ExecutionConfig();
        }

        JsonElement argv = data.get("argv");
        if (argv.isJsonObject()) {
            for (String key : ((JsonObject) argv).keySet()) {
                config.setArgument(key, json2val(((JsonObject) argv).get(key)));
            }
        }

        return config;
    }

    private String getId(JsonObject data) {
        JsonElement element = data.get("id");
        if (element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private String getName(JsonObject data) {
        JsonElement element = data.get("name");
        if (element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private void saveScript(String name, String script) {
        if (TextUtils.isEmpty(name)) {
            name = "untitled";
        }
        name = PFiles.getNameWithoutExtension(name);
        if (!name.endsWith(".js")) {
            name = name + ".js";
        }
        File file = new File(Pref.getScriptDirPath(), name);
        PFiles.ensureDir(file.getPath());
        PFiles.write(file, script);
        GlobalAppContext.toast(R.string.text_script_save_successfully);
    }

//
//    @SuppressLint("CheckResult")
//    private void saveProject(String name, String dir) {
//        if (TextUtils.isEmpty(name)) {
//            name = "untitled";
//        }
//        name = PFiles.getNameWithoutExtension(name);
//        File toDir = new File(Pref.getScriptDirPath(), name);
//        Observable.fromCallable(() -> {
//            copyDir(new File(dir), toDir);
//            return toDir.getPath();
//        }).subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(dest ->
//                                GlobalAppContext.toast(R.string.text_project_save_success, dest),
//                        err ->
//                                GlobalAppContext.toast(R.string.text_project_save_error, err.getMessage())
//                );
//
//    }

//    private void copyDir(File fromDir, File toDir) throws FileNotFoundException {
//        toDir.mkdirs();
//        File[] files = fromDir.listFiles();
//        if (files == null || files.length == 0) {
//            return;
//        }
//        for (File file : files) {
//            if (file.isDirectory()) {
//                copyDir(file, new File(toDir, file.getName()));
//            } else {
//                FileOutputStream fos = new FileOutputStream(new File(toDir, file.getName()));
//                PFiles.write(new FileInputStream(file), fos, true);
//            }
//        }
//    }

}
