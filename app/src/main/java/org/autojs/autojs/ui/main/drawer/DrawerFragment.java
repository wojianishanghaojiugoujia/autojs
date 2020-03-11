package org.autojs.autojs.ui.main.drawer;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputType;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.stardust.app.AppOpsKt;
import com.stardust.app.GlobalAppContext;
import com.stardust.notification.NotificationListenerService;

import org.autojs.autojs.Pref;
import org.autojs.autojs.R;
import org.autojs.autojs.autojs.AutoJs;
import org.autojs.autojs.external.foreground.ForegroundService;
import org.autojs.autojs.network.UserService;
import org.autojs.autojs.tool.Observers;
import org.autojs.autojs.ui.BaseActivity;
import org.autojs.autojs.ui.common.NotAskAgainDialog;
import org.autojs.autojs.ui.floating.CircularMenu;
import org.autojs.autojs.ui.floating.FloatyWindowManger;
import org.autojs.autojs.network.VersionService;
import org.autojs.autojs.network.entity.VersionInfo;
import org.autojs.autojs.tool.SimpleObserver;
import org.autojs.autojs.ui.main.MainActivity;
import org.autojs.autojs.ui.main.community.CommunityFragment;
import org.autojs.autojs.ui.settings.SettingsActivity;
import org.autojs.autojs.ui.update.UpdateInfoDialogBuilder;
import org.autojs.autojs.ui.widget.AvatarView;

import com.stardust.theme.ThemeColorManager;

import com.stardust.view.accessibility.AccessibilityService;

import org.autojs.autojs.pluginclient.DevPluginService;
import org.autojs.autojs.tool.AccessibilityServiceTool;
import org.autojs.autojs.tool.WifiTool;

import com.stardust.util.IntentUtil;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EFragment;
import org.androidannotations.annotations.ViewById;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;


/**
 * Created by Stardust on 2017/1/30.
 */
@EFragment(R.layout.fragment_drawer)
public class DrawerFragment extends androidx.fragment.app.Fragment {
    @ViewById(R.id.drawer_menu)
    RecyclerView mDrawerMenu;

    private DrawerMenuItem 连接电脑菜单项 = new DrawerMenuItem(R.drawable.ic_connect_to_pc, R.string.debug, 0, this::切换连接电脑);
    private DrawerMenuItem 断线重连菜单项 = new DrawerMenuItem(R.drawable.ic_sync, R.string.reconnect, 0, this::切换断线重连);
    private DrawerMenuItem 无障碍服务菜单项 = new DrawerMenuItem(R.drawable.ic_service_green, R.string.text_accessibility_service, 0, this::切换无障碍服务);

    private DrawerMenuItem 通知权限菜单项 = new DrawerMenuItem(R.drawable.ic_ali_notification, R.string.text_notification_permission, 0, this::切换通知读取权限);
    private DrawerMenuItem 统计权限菜单项 = new DrawerMenuItem(R.drawable.ic_ali_notification, R.string.text_usage_stats_permission, 0, this::切换查看使用统计权限);
    private DrawerMenuItem 前台服务菜单项 = new DrawerMenuItem(R.drawable.ic_service_green, R.string.text_foreground_service, R.string.key_foreground_servie, this::切换前台服务);

    private DrawerMenuItem 悬浮窗菜单项 = new DrawerMenuItem(R.drawable.ic_robot_64, R.string.text_floating_window, 0, this::切换悬浮窗);
    private DrawerMenuItem 检测更新菜单项 = new DrawerMenuItem(R.drawable.ic_check_for_updates, R.string.text_check_for_updates, this::点击检查更新);

    private DrawerMenuAdapter mDrawerMenuAdapter;
    private CommunityDrawerMenu mCommunityDrawerMenu = new CommunityDrawerMenu();

    private Disposable mConnectionStateDisposable;
    private Disposable mReConnectionStateDisposable;
    private Disposable tryReconnectDisposable;

    private int 断线重连_尝试次数 = 0;
    private int 断线重连_尝试间隔 = 10;
    private int 断线重连_最大尝试次数 = 5;
    private boolean 是否断线重连 = false;

    // 连接超时的定时器
    private Disposable connectTimeoutDisposable;
    private int timeout = 5;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        是否断线重连 = Pref.getAutoReconnect(false);
        断线重连_尝试间隔 = Pref.getReconnectInterval(10);
        断线重连_最大尝试次数 = Pref.getReconnectMaxTimes(5);

        mConnectionStateDisposable = DevPluginService.getInstance().connectionState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    if (连接电脑菜单项 != null) {
                        setChecked(连接电脑菜单项, state.getState() == DevPluginService.State.CONNECTED);
                        setProgress(连接电脑菜单项, state.getState() == DevPluginService.State.CONNECTING);
                    }
                    if (state.getException() != null) {
                        吐司消息(state.getException().getMessage());
                    }
                });
        mReConnectionStateDisposable = DevPluginService.getInstance().connectionState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    if (state.getState() != DevPluginService.State.CONNECTING) {
                        if (connectTimeoutDisposable != null && !connectTimeoutDisposable.isDisposed()) {
                            connectTimeoutDisposable.dispose();
                        }
                    }

                    if (state.getState() == DevPluginService.State.DISCONNECTED) { // 如果是断开连接，那么就判断是否需要重新连接
                        if (是否断线重连) {
                            if (断线重连_尝试次数 <= 断线重连_最大尝试次数 || 断线重连_最大尝试次数 == 0) {
                                if (tryReconnectDisposable != null && !tryReconnectDisposable.isDisposed()) {
                                    tryReconnectDisposable.dispose();
                                }
                                tryReconnectDisposable = Observable.timer(断线重连_尝试间隔, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                                        .subscribe(aLong -> {
                                            断线重连_尝试次数++;
                                            吐司消息("尝试重新连接(" + 断线重连_尝试次数 + ")");
                                            connectToServer();
                                        });
                            } else {
                                setChecked(断线重连菜单项, false);
                                int n = AutoJs.getInstance().getScriptEngineService().stopAll();
                                吐司消息L("超过尝试重连次数，关闭所有正在执行的脚本" + n);
                            }
                        }
                    } else if (state.getState() == DevPluginService.State.CONNECTED) {
                        断线重连_尝试次数 = 0;
                    }
                });
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        setChecked(无障碍服务菜单项, AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setChecked(通知权限菜单项, NotificationListenerService.Companion.getInstance() != null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setChecked(统计权限菜单项, AppOpsKt.isOpPermissionGranted(getContext(), AppOpsManager.OPSTR_GET_USAGE_STATS));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mConnectionStateDisposable.dispose();
        mReConnectionStateDisposable.dispose();
        EventBus.getDefault().unregister(this);
    }

    @AfterViews
    void 视图更新() {
        初始化菜单列表();
        if (Pref.isFloatingMenuShown()) {
            FloatyWindowManger.showCircularMenuIfNeeded();
            setChecked(悬浮窗菜单项, true);
        }
        setChecked(连接电脑菜单项, DevPluginService.getInstance().isConnected());
        setChecked(断线重连菜单项, 是否断线重连); // 默认都是不自动连接
        if (Pref.isForegroundServiceEnabled()) {
            ForegroundService.start(GlobalAppContext.get());
            setChecked(前台服务菜单项, true);
        }
    }

    void 切换无障碍服务(DrawerMenuItemViewHolder holder) {
        boolean isAccessibilityServiceEnabled = 无障碍服务是否开启();
        boolean checked = holder.getSwitchCompat().isChecked();
        if (checked && !isAccessibilityServiceEnabled) {
            enableAccessibilityService();
        } else if (!checked && isAccessibilityServiceEnabled) {
            if (!AccessibilityService.Companion.disable()) {
                AccessibilityServiceTool.goToAccessibilitySetting();
            }
        }
    }

    void 切换通知读取权限(DrawerMenuItemViewHolder holder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return;
        }
        boolean enabled = NotificationListenerService.Companion.getInstance() != null;
        boolean checked = holder.getSwitchCompat().isChecked();
        if ((checked && !enabled) || (!checked && enabled)) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void 切换前台服务(DrawerMenuItemViewHolder holder) {
        boolean checked = holder.getSwitchCompat().isChecked();
        if (checked) {
            ForegroundService.start(GlobalAppContext.get());
        } else {
            ForegroundService.stop(GlobalAppContext.get());
        }
    }

    private void 切换查看使用统计权限(DrawerMenuItemViewHolder holder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        boolean enabled = AppOpsKt.isOpPermissionGranted(getContext(), AppOpsManager.OPSTR_GET_USAGE_STATS);
        boolean checked = holder.getSwitchCompat().isChecked();
        if (checked && !enabled) {
            if (new NotAskAgainDialog.Builder(getContext(), "DrawerFragment.usage_stats")
                    .title(R.string.text_usage_stats_permission)
                    .content(R.string.description_usage_stats_permission)
                    .positiveText(R.string.ok)
                    .dismissListener(dialog -> IntentUtil.requestAppUsagePermission(getContext()))
                    .show() == null) {
                IntentUtil.requestAppUsagePermission(getContext());
            }
        }
        if (!checked && enabled) {
            IntentUtil.requestAppUsagePermission(getContext());
        }
    }

    private void 切换悬浮窗(DrawerMenuItemViewHolder holder) {
        boolean isFloatingWindowShowing = FloatyWindowManger.isCircularMenuShowing();
        boolean checked = holder.getSwitchCompat().isChecked();
        if (getActivity() != null && !getActivity().isFinishing()) {
            Pref.setFloatingMenuShown(checked);
        }
        if (checked && !isFloatingWindowShowing) {
            setChecked(悬浮窗菜单项, FloatyWindowManger.showCircularMenu());
            如果没有开启则开启无障碍服务();
        } else if (!checked && isFloatingWindowShowing) {
            FloatyWindowManger.hideCircularMenu();
        }
    }

    // 切换 连接电脑 switch
    private void 切换连接电脑(DrawerMenuItemViewHolder holder) {
        boolean checked = holder.getSwitchCompat().isChecked();
        boolean connected = DevPluginService.getInstance().isConnected();
        if (checked && !connected) {
            输入服务器地址();
        } else if (!checked && connected) {
            DevPluginService.getInstance().disconnectIfNeeded();
        }
    }

    private void 切换断线重连(DrawerMenuItemViewHolder holder) {
        if (断线重连菜单项.isChecked()) {
            new MaterialDialog.Builder(Objects.requireNonNull(getActivity()))
                    .title("最大尝试次数（输入0为不限制次数）")
                    .inputType(InputType.TYPE_CLASS_NUMBER)
                    .input("", String.valueOf(断线重连_最大尝试次数), ((d1, i1) -> {
                        if (Integer.parseInt(i1.toString()) < 0) {
                            吐司消息L("最大尝试次数不能为负数");
                            return;
                        }

                        new MaterialDialog.Builder(getActivity())
                                .title("尝试间隔(秒)")
                                .inputType(InputType.TYPE_CLASS_NUMBER)
                                .input("", String.valueOf(断线重连_尝试间隔), ((d2, i2) -> {
                                    if (Integer.parseInt(i2.toString()) <= 0) {
                                        吐司消息L("执行时间间隔必须为正整数");
                                        return;
                                    }

                                    断线重连_最大尝试次数 = Integer.parseInt(i1.toString());
                                    断线重连_尝试间隔 = Integer.parseInt(i2.toString());
                                    Pref.saveReconnectMaxTimes(断线重连_最大尝试次数);
                                    Pref.saveReconnectInterval(断线重连_尝试间隔);

                                    是否断线重连 = true;
                                    Pref.saveAutoReconnect(true);
                                }))
                                .cancelable(true)
                                .cancelListener(dialog1 -> setChecked(断线重连菜单项, false))
                                .show();
                    }))
                    .cancelable(true)
                    .cancelListener(dialog1 -> setChecked(断线重连菜单项, false))
                    .show();
        } else {
            是否断线重连 = false;
            Pref.saveAutoReconnect(false);
            if (tryReconnectDisposable != null && !tryReconnectDisposable.isDisposed()) {
                tryReconnectDisposable.dispose();
            }
        }
    }

    private void 打开主题色(DrawerMenuItemViewHolder holder) {
        SettingsActivity.selectThemeColor(getActivity());
    }

    private void 切换夜间模式(DrawerMenuItemViewHolder holder) {
        ((BaseActivity) Objects.requireNonNull(getActivity())).setNightModeEnabled(holder.getSwitchCompat().isChecked());
    }

    private void 点击检查更新(DrawerMenuItemViewHolder holder) {
        setProgress(检测更新菜单项, true);
        VersionService.getInstance().checkForUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<VersionInfo>() {

                    @Override
                    public void onNext(@io.reactivex.annotations.NonNull VersionInfo versionInfo) {
                        if (getActivity() == null)
                            return;
                        if (versionInfo.isNewer()) {
                            new UpdateInfoDialogBuilder(getActivity(), versionInfo)
                                    .show();
                        } else {
                            吐司消息(R.string.text_is_latest_version);
                        }
                        setProgress(检测更新菜单项, false);
                    }

                    @Override
                    public void onError(@io.reactivex.annotations.NonNull Throwable e) {
                        e.printStackTrace();
                        吐司消息(R.string.text_check_update_error);
                        setProgress(检测更新菜单项, false);
                    }
                });
    }

    // 默认连接服务器，使用填过的值
    private Disposable connectToServer() {
        String host = Pref.getServerAddressOrDefault(WifiTool.getRouterIp(Objects.requireNonNull(getActivity())));
        int port = Pref.getServerPortOrDefault(9317);
        return connectToServer(host, port);
    }

    // 连接服务器
    private Disposable connectToServer(String ip, int port) {
        if (tryReconnectDisposable != null && !tryReconnectDisposable.isDisposed()) {
            tryReconnectDisposable.dispose();
        }
        if (connectTimeoutDisposable != null && !connectTimeoutDisposable.isDisposed()) {
            connectTimeoutDisposable.dispose();
        }
        connectTimeoutDisposable = Observable.timer(timeout, TimeUnit.SECONDS, AndroidSchedulers.mainThread()).subscribe(aLong -> {
            if (连接电脑菜单项.isProgress()) {
                吐司消息L("超时未连接，强制关闭");
                DevPluginService.getInstance().disconnectIfNeeded();
            }
        });
        return DevPluginService
                .getInstance()
                .connectToServer(ip, port)
                .subscribe(Observers.emptyConsumer(), e -> {
                    setChecked(连接电脑菜单项, false);
                    吐司消息L(getString(R.string.error_connect_to_remote, e.getMessage()));
                });
    }

    // 连接电脑输入地址和端口
    private void 输入服务器地址() {
        String host = Pref.getServerAddressOrDefault(WifiTool.getRouterIp(Objects.requireNonNull(getActivity())));
        int port = Pref.getServerPortOrDefault(9317);

        new MaterialDialog.Builder(getActivity())
                .title(R.string.text_server_address)
                .input("", host, (dialog, input) -> {
                    String ip = input.toString();

                    new MaterialDialog.Builder(getActivity())
                            .title("服务器端口")
                            .inputType(InputType.TYPE_CLASS_NUMBER)
                            .input("", String.valueOf(port), ((dialog1, input1) -> {
                                Pref.saveServerAddress(input.toString());
                                int p = Integer.parseInt(input1.toString());
                                Pref.saveServerPort(p);
                                if (是否断线重连) {
                                    断线重连_尝试次数 = 0;
                                }
                                connectToServer(ip, p);
                            }))
                            .cancelable(true)
                            .cancelListener(dialog1 -> setChecked(连接电脑菜单项, false))
                            .show();
                })
                .cancelable(true)
                .cancelListener(dialog -> setChecked(连接电脑菜单项, false))
                .show();
    }

    @Subscribe
    public void onCircularMenuStateChange(CircularMenu.StateChangeEvent event) {
        setChecked(悬浮窗菜单项, event.getCurrentState() != CircularMenu.STATE_CLOSED);
    }

    @Subscribe
    public void onCommunityPageVisibilityChange(CommunityFragment.VisibilityChange change) {
        if (change.visible) {
            mCommunityDrawerMenu.showCommunityMenu(mDrawerMenuAdapter);
        } else {
            mCommunityDrawerMenu.hideCommunityMenu(mDrawerMenuAdapter);
        }
        mDrawerMenu.scrollToPosition(0);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLoginStateChange(UserService.LoginStateChange change) {
        if (mCommunityDrawerMenu.isShown()) {
            mCommunityDrawerMenu.setUserOnlineStatus(mDrawerMenuAdapter, change.isOnline());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDrawerOpen(MainActivity.DrawerOpenEvent event) {
        if (mCommunityDrawerMenu.isShown()) {
            mCommunityDrawerMenu.refreshNotificationCount(mDrawerMenuAdapter);
        }
    }

    private void 吐司消息(CharSequence text) {
        if (super.getContext() == null)
            return;
        Toast.makeText(super.getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void 吐司消息(int resId) {
        if (super.getContext() == null)
            return;
        Toast.makeText(super.getContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private void 吐司消息L(CharSequence text) {
        if (super.getContext() == null)
            return;
        Toast.makeText(super.getContext(), text, Toast.LENGTH_LONG).show();
    }

    private void 吐司消息L(int resId) {
        if (super.getContext() == null)
            return;
        Toast.makeText(super.getContext(), resId, Toast.LENGTH_LONG).show();
    }

    private void setProgress(DrawerMenuItem item, boolean progress) {
        item.setProgress(progress);
        mDrawerMenuAdapter.notifyItemChanged(item);
    }

    private void setChecked(DrawerMenuItem item, boolean checked) {
        item.setChecked(checked);
        mDrawerMenuAdapter.notifyItemChanged(item);
    }

    private boolean 无障碍服务是否开启() {
        return AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity());
    }

    private void 如果没有开启则开启无障碍服务() {
        Observable.fromCallable(() -> Pref.shouldEnableAccessibilityServiceByRoot() && !无障碍服务是否开启())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(needed -> {
                    if (needed) {
                        enableAccessibilityServiceByRoot();
                    }
                });
    }

    private void enableAccessibilityService() {
        if (!Pref.shouldEnableAccessibilityServiceByRoot()) {
            AccessibilityServiceTool.goToAccessibilitySetting();
            return;
        }
        enableAccessibilityServiceByRoot();
    }

    // 通过root开启无障碍五福
    private void enableAccessibilityServiceByRoot() {
        setProgress(无障碍服务菜单项, true);
        Observable.fromCallable(() -> AccessibilityServiceTool.enableAccessibilityServiceByRootAndWaitFor(4000))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(succeed -> {
                    if (!succeed) {
                        吐司消息(R.string.text_enable_accessibitliy_service_by_root_failed);
                        AccessibilityServiceTool.goToAccessibilitySetting();
                    }
                    setProgress(无障碍服务菜单项, false);
                });
    }

    private void 初始化菜单列表() {
        mDrawerMenuAdapter = new DrawerMenuAdapter(new ArrayList<>(Arrays.asList(
                new DrawerMenuGroup(R.string.text_service),
                无障碍服务菜单项,
                // 稳定模式菜单项,
                通知权限菜单项,
                前台服务菜单项,
                统计权限菜单项,
                悬浮窗菜单项,

//                new DrawerMenuGroup(R.string.text_script_record),
//                悬浮窗菜单项,
                // new DrawerMenuItem(R.drawable.ic_volume, R.string.text_volume_down_control, R.string.key_use_volume_control_record, null),

                new DrawerMenuGroup(R.string.text_others),
                连接电脑菜单项,
                断线重连菜单项,
                new DrawerMenuItem(R.drawable.ic_personalize, R.string.text_theme_color, this::打开主题色),
                new DrawerMenuItem(R.drawable.ic_night_mode, R.string.text_night_mode, R.string.key_night_mode, this::切换夜间模式),
                检测更新菜单项
        )));
        mDrawerMenu.setAdapter(mDrawerMenuAdapter);
        mDrawerMenu.setLayoutManager(new LinearLayoutManager(getContext()));
    }

    @NonNull
    @Override
    public Context getContext() {
        return Objects.requireNonNull(super.getContext());
    }
}
