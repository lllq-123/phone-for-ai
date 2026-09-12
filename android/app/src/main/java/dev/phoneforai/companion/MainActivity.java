package dev.phoneforai.companion;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private TextView status;
    private EditText baseUrl;
    private EditText pairingCode;
    private CheckBox rootToggle;
    private boolean refreshing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        BridgeClient.start(this);
    }
    @Override protected void onResume() {
        super.onResume(); refresh(); BridgeClient.heartbeatNow(this);
    }

    private ScrollView buildUi() {
        int pad = dp(20);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(pad, pad, pad, pad);
        TextView title = text("AI 手机伙伴", 25f, Color.BLACK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, dp(8), 0, dp(8));
        panel.addView(title, params());
        TextView intro = text("配对后，手机会通过你填写的 HTTPS 地址主动领取命令。截图、手势、输入、界面结构、剪贴板和启动应用需要无障碍服务。安全锁屏必须由你本人解锁。", 16f, Color.DKGRAY);
        panel.addView(intro, params());
        status = text("", 16f, Color.BLACK); status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(0xFFF2F2F2); panel.addView(status, params());

        Button accessibility = button("打开无障碍设置");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        panel.addView(accessibility, params());
        Button battery = button("打开后台电池设置");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        panel.addView(battery, params());

        baseUrl = new EditText(this); baseUrl.setHint("https://phone.example.com/api/phone");
        baseUrl.setMinHeight(dp(54));
        baseUrl.setSingleLine(true); baseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        panel.addView(baseUrl, params());
        pairingCode = new EditText(this); pairingCode.setHint("一次性配对码"); pairingCode.setSingleLine(true);
        pairingCode.setMinHeight(dp(54));
        pairingCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        panel.addView(pairingCode, params());
        Button pair = button("配对"); pair.setOnClickListener(v -> pair()); panel.addView(pair, params());

        rootToggle = new CheckBox(this);
        rootToggle.setText("开启 Root 扩展（Shell、文件、应用列表、实时投屏）");
        rootToggle.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (!checked) { BridgeClient.setRootState(this, false, false); refresh(); return; }
            button.setEnabled(false);
            Toast.makeText(this, "正在请求 Root 授权…", Toast.LENGTH_LONG).show();
            DeviceCommandWorker.probeRoot(this, (ok, detail) -> runOnUiThread(() -> {
                button.setEnabled(true); Toast.makeText(this, detail, Toast.LENGTH_LONG).show(); refresh();
            }));
        });
        panel.addView(rootToggle, params());
        TextView rootHelp = text("Root 扩展可访问整台手机上的文件并执行命令，风险高于纯屏幕控制。实时投屏依赖 Root 和 scrcpy，只在浏览器查看会话存活时运行，并与队列中的手机控制互斥。", 14f, Color.DKGRAY);
        panel.addView(rootHelp, params());

        Button unpair = button("清除本机配对");
        unpair.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("清除本机配对？")
                .setMessage("会从本机清除设备令牌并停止实时投屏。如需让服务器上的旧设备令牌失效，请重新生成配对码。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                    BridgeClient.unpair(this); pairingCode.setText(""); refresh();
                }).show());
        panel.addView(unpair, params());
        ScrollView scroll = new ScrollView(this); scroll.addView(panel); return scroll;
    }

    private void pair() {
        BridgeClient.enroll(this, baseUrl.getText().toString(), pairingCode.getText().toString(),
                (ok, detail) -> runOnUiThread(() -> {
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
                    if (ok) pairingCode.setText(""); refresh();
                }));
    }
    private void refresh() {
        refreshing = true;
        String savedBase = BridgeClient.baseUrl(this);
        if (!savedBase.isEmpty()) baseUrl.setText(savedBase);
        rootToggle.setChecked(BridgeClient.rootAvailable(this));
        refreshing = false;
        status.setText("连接：" + BridgeClient.status(this) + "\n"
                + "无障碍：" + (hasAccessibility() ? "已打开" : "未打开") + "\n"
                + "Root 扩展：" + (BridgeClient.rootAvailable(this) ? "已验证可用" : "未开启或不可用") + "\n"
                + "实时投屏：" + (ScreenStreamClient.isActive() ? "进行中" : "未运行"));
    }
    private boolean hasAccessibility() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        ComponentName wanted = new ComponentName(this, PhoneAccessibilityService.class);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            if (service.getResolveInfo() == null || service.getResolveInfo().serviceInfo == null) continue;
            ComponentName actual = new ComponentName(service.getResolveInfo().serviceInfo.packageName,
                    service.getResolveInfo().serviceInfo.name);
            if (wanted.equals(actual)) return true;
        }
        return false;
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(dp(54));
        return button;
    }
    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        value.topMargin = dp(12); return value;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
