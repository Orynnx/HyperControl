package org.orynnx.hypercontrol;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.TypedValue;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Small settings activity; the actual changes are made in SystemUI by Xposed. */
public final class MainActivity extends android.app.Activity {
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final String GITHUB_URL = "https://github.com/Orynnx/HyperControl";
    private SharedPreferences preferences;
    private MaterialButton statusButton;
    private MaterialSwitch swapSwitch;
    private MaterialSwitch swapBrightnessVolumeSwitch;
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
        configureEdgeToEdge();
        int paddingHorizontal = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(resolveSurface());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(paddingHorizontal, dp(18), paddingHorizontal, dp(12));
        root.setBackgroundColor(resolveSurface());
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -1));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            applySafeInsets(root, insets, paddingHorizontal);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.appearance);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        header.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(16), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setTextColor(resolveOnSurface());
        titleColumn.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.module_description);
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        subtitle.setTextColor(resolveOnSurfaceVariant());
        titleColumn.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titleColumn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        statusButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        statusButton.setAllCaps(false);
        statusButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusButton.setTextColor(resolvePrimary());
        statusButton.setStrokeColor(ColorStateList.valueOf(resolvePrimary()));
        statusButton.setOnClickListener(v -> openLsposed());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(56));
        statusParams.topMargin = dp(18);
        root.addView(statusButton, statusParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(1));
        card.setCardBackgroundColor(resolveSurfaceContainer());
        card.setUseCompatPadding(false);
        LinearLayout switchColumn = new LinearLayout(this);
        switchColumn.setOrientation(LinearLayout.VERTICAL);
        switchColumn.setPadding(dp(16), dp(4), dp(10), dp(4));
        MaterialSwitch swap = makeSwitch(R.string.swap_title,
                RemoteSettings.preferences(preferences).getBoolean(TargetRuntime.SWAP_PANELS, false));
        MaterialSwitch swapBrightnessVolume = makeSwitch(R.string.swap_brightness_volume_title,
                RemoteSettings.preferences(preferences).getBoolean(
                        TargetRuntime.SWAP_BRIGHTNESS_VOLUME, false));
        MaterialSwitch horizontal = makeSwitch(R.string.horizontal_title,
                RemoteSettings.preferences(preferences).getBoolean(TargetRuntime.HORIZONTAL_SLIDERS, false));
        swapSwitch = swap;
        swapBrightnessVolumeSwitch = swapBrightnessVolume;
        horizontalSwitch = horizontal;
        swap.setOnCheckedChangeListener((button, checked) ->
                RemoteSettings.putBoolean(preferences, TargetRuntime.SWAP_PANELS, checked));
        swapBrightnessVolume.setOnCheckedChangeListener((button, checked) ->
                RemoteSettings.putBoolean(preferences, TargetRuntime.SWAP_BRIGHTNESS_VOLUME, checked));
        horizontal.setOnCheckedChangeListener((button, checked) ->
                RemoteSettings.putBoolean(preferences, TargetRuntime.HORIZONTAL_SLIDERS, checked));
        switchColumn.addView(swap, new LinearLayout.LayoutParams(-1, dp(64)));
        switchColumn.addView(swapBrightnessVolume, new LinearLayout.LayoutParams(-1, dp(64)));
        switchColumn.addView(horizontal, new LinearLayout.LayoutParams(-1, dp(64)));
        card.addView(switchColumn, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(14);
        root.addView(card, cardParams);

        MaterialButton restartScope = new MaterialButton(this);
        restartScopeButton = restartScope;
        restartScope.setText(R.string.restart_scope);
        restartScope.setAllCaps(false);
        restartScope.setTextColor(Color.WHITE);
        restartScope.setBackgroundTintList(ColorStateList.valueOf(resolvePrimary()));
        restartScope.setOnClickListener(v -> requestRootAndRestartScope());
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(-1, dp(52));
        restartParams.topMargin = dp(12);
        root.addView(restartScope, restartParams);

        MaterialButton github = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        github.setText(R.string.github_project);
        github.setAllCaps(false);
        github.setTextColor(resolvePrimary());
        github.setStrokeColor(ColorStateList.valueOf(resolvePrimary()));
        github.setOnClickListener(v -> openGithub());
        LinearLayout.LayoutParams githubParams = new LinearLayout.LayoutParams(-1, dp(52));
        githubParams.topMargin = dp(10);
        root.addView(github, githubParams);

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
        scroll.post(() -> applySafeInsets(root, scroll.getRootWindowInsets(), paddingHorizontal));
        return scroll;
    }

    private void refreshFromRemote() {
        if (preferences == null) return;
        SharedPreferences remote = RemoteSettings.preferences(preferences);
        if (swapSwitch != null) swapSwitch.setChecked(
                remote.getBoolean(TargetRuntime.SWAP_PANELS, false));
        if (swapBrightnessVolumeSwitch != null) swapBrightnessVolumeSwitch.setChecked(
                remote.getBoolean(TargetRuntime.SWAP_BRIGHTNESS_VOLUME, false));
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

    private void openGithub() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
    }

    private void configureEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
    }

    private void applySafeInsets(View content, WindowInsets insets, int horizontalPadding) {
        if (insets == null) return;
        int left;
        int top;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = safe.left;
            top = safe.top;
            right = safe.right;
            bottom = safe.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        content.setPadding(horizontalPadding + left, dp(24) + top,
                horizontalPadding + right, dp(24) + bottom);
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
        return resolveColor(com.google.android.material.R.attr.colorSurface, Color.WHITE);
    }

    private int resolveOnSurface() {
        return resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
    }

    private int resolveOnSurfaceVariant() {
        return resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY);
    }

    private int resolvePrimary() {
        return resolveColor(com.google.android.material.R.attr.colorPrimary, Color.rgb(103, 80, 164));
    }

    private int resolveSurfaceContainer() {
        return resolveColor(com.google.android.material.R.attr.colorSurfaceContainer, resolveSurface());
    }

    private int resolveColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try { return getResources().getColor(value.resourceId, getTheme()); }
            catch (Throwable ignored) { }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT ? value.data : fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
