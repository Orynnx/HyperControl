package org.orynnx.hypercontrol;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Small settings activity; the actual changes are made in SystemUI by Xposed. */
public final class MainActivity extends android.app.Activity {
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private SharedPreferences preferences;
    private MaterialButton statusButton;
    private MaterialSwitch swapSwitch;
    private MaterialSwitch horizontalSwitch;
    private MaterialButton restartScopeButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(TargetRuntime.PREFERENCES, MODE_PRIVATE);
        RemoteSettings.connect(() -> runOnUiThread(this::refreshFromRemote));
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusButton != null) updateStatus();
    }

    private View buildContent() {
        int paddingHorizontal = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(paddingHorizontal, dp(18), paddingHorizontal, dp(12));
        root.setBackgroundColor(resolveSurface());

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setTextColor(resolveOnSurface());
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        statusButton.setAllCaps(false);
        statusButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusButton.setOnClickListener(v -> openLsposed());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(56));
        statusParams.topMargin = dp(18);
        root.addView(statusButton, statusParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setUseCompatPadding(false);
        LinearLayout switchColumn = new LinearLayout(this);
        switchColumn.setOrientation(LinearLayout.VERTICAL);
        switchColumn.setPadding(dp(16), dp(4), dp(10), dp(4));
        MaterialSwitch swap = makeSwitch(R.string.swap_title,
                RemoteSettings.preferences(preferences).getBoolean(TargetRuntime.SWAP_PANELS, false));
        MaterialSwitch horizontal = makeSwitch(R.string.horizontal_title,
                RemoteSettings.preferences(preferences).getBoolean(TargetRuntime.HORIZONTAL_SLIDERS, false));
        swapSwitch = swap;
        horizontalSwitch = horizontal;
        swap.setOnCheckedChangeListener((button, checked) ->
                RemoteSettings.putBoolean(preferences, TargetRuntime.SWAP_PANELS, checked));
        horizontal.setOnCheckedChangeListener((button, checked) ->
                RemoteSettings.putBoolean(preferences, TargetRuntime.HORIZONTAL_SLIDERS, checked));
        switchColumn.addView(swap, new LinearLayout.LayoutParams(-1, dp(64)));
        switchColumn.addView(horizontal, new LinearLayout.LayoutParams(-1, dp(64)));
        card.addView(switchColumn, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(14);
        root.addView(card, cardParams);

        MaterialButton restartScope = new MaterialButton(this);
        restartScopeButton = restartScope;
        restartScope.setText(R.string.restart_scope);
        restartScope.setAllCaps(false);
        restartScope.setOnClickListener(v -> requestRootAndRestartScope());
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(-1, dp(52));
        restartParams.topMargin = dp(12);
        root.addView(restartScope, restartParams);

        TextView footer = new TextView(this);
        footer.setText(R.string.developer);
        footer.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        footer.setTextColor(resolveOnSurfaceVariant());
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(20);
        footerParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(footer, footerParams);
        updateStatus();
        return root;
    }

    private void refreshFromRemote() {
        if (preferences == null) return;
        SharedPreferences remote = RemoteSettings.preferences(preferences);
        if (swapSwitch != null) swapSwitch.setChecked(
                remote.getBoolean(TargetRuntime.SWAP_PANELS, false));
        if (horizontalSwitch != null) horizontalSwitch.setChecked(
                remote.getBoolean(TargetRuntime.HORIZONTAL_SLIDERS, false));
        if (statusButton != null) updateStatus();
    }

    private MaterialSwitch makeSwitch(int text, boolean checked) {
        MaterialSwitch result = new MaterialSwitch(this);
        result.setText(text);
        result.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        result.setChecked(checked);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private void updateStatus() {
        statusButton.setText(R.string.status_not_working);
        new Thread(() -> {
            boolean active = RemoteSettings.isHooked();
            runOnUiThread(() -> {
                if (statusButton != null) statusButton.setText(
                        active ? R.string.status_working : R.string.status_not_working);
            });
        }, "hypercontrol-status").start();
    }

    private void openLsposed() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(LSPOSED_PACKAGE);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN).setClassName(
                    LSPOSED_PACKAGE, "org.lsposed.manager.ui.activity.MainActivity");
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void requestRootAndRestartScope() {
        restartScopeButton.setEnabled(false);
        new Thread(() -> {
            String result = restartScopeProcesses();
            runOnUiThread(() -> {
                restartScopeButton.setEnabled(true);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                updateStatus();
            });
        }, "hypercontrol-su").start();
    }

    private String restartScopeProcesses() {
        try {
            String command;
            try (java.io.InputStream script = getResources().openRawResource(R.raw.restart_scope)) {
                command = readText(script);
            }
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "重启超时，请检查 SU 授权与日志";
            }
            String output = readText(process.getInputStream());
            Log.i("HyperControl", "Scope restart exit=" + process.exitValue() + "\n" + output);
            if (process.exitValue() == 0 && output.contains("RESTART_OK")) {
                return getString(R.string.root_ready);
            }
            return "重启失败：" + output.trim();
        } catch (Throwable failure) {
            Log.e("HyperControl", "Scope restart failed", failure);
            return "重启失败：" + failure.getMessage();
        }
    }

    private static String readText(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private int resolveSurface() {
        return getResources().getColor(android.R.color.background_light, getTheme());
    }

    private int resolveOnSurface() {
        return getResources().getColor(android.R.color.primary_text_light, getTheme());
    }

    private int resolveOnSurfaceVariant() {
        return getResources().getColor(android.R.color.secondary_text_light, getTheme());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
