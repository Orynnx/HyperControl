package org.orynnx.hypercontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.SeekBar;
import android.widget.FrameLayout;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** SystemUI side of HyperControl. All hooks are optional except panel distribution. */
final class TargetRuntime {
    static final String PREFERENCES = "hypercontrol_settings";
    static final String SWAP_PANELS = "swap_panels";
    static final String SWAP_BRIGHTNESS_VOLUME = "swap_brightness_volume";
    static final String HORIZONTAL_SLIDERS = "horizontal_sliders";
    static final String HOOK_ACTIVE = "hook_active";

    private static final String TAG = "HyperControl";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean DISCOVERY_INSTALLED = new AtomicBoolean();
    private static final Object LOCK = new Object();
    private static volatile XposedModule framework;
    private static volatile SharedPreferences settings;
    private static volatile ClassLoader loader;
    private static volatile boolean loggedSwap;
    private static volatile boolean loggedHorizontal;
    private static volatile boolean loggedHorizontalTouch;
    private static final List<HookHandle> discoveryHandles = new ArrayList<>();
    // UI-thread-only cache. Weak keys release recycled views and old plugin instances.
    private static final Map<View, Integer> sliderGaps = new WeakHashMap<>();

    private TargetRuntime() {}

    static void start(XposedModule module, ClassLoader classLoader, String packageName) {
        synchronized (LOCK) {
            if (INSTALLED.get()) return;
            framework = module;
            loader = classLoader;
            settings = safePreferences(module);
            try {
                if (findDistributor(classLoader) != null) {
                    install(module, classLoader, packageName);
                    INSTALLED.set(true);
                    log(4, "[HOOKS_READY] package=" + packageName + " loader=" + classLoader);
                } else {
                    installDiscovery(module, packageName);
                }
            } catch (Throwable failure) {
                log(6, "[INSTALL_FAILED] package=" + packageName, failure);
            }
        }
    }

    private static Class<?> findDistributor(ClassLoader classLoader) {
        return findClass(classLoader,
                "miui.systemui.controlcenter.panel.main.MainPanelContentDistributor",
                "com.android.systemui.controlcenter.panel.main.MainPanelContentDistributor");
    }

    private static void installDiscovery(XposedModule module, String packageName) throws Exception {
        if (!DISCOVERY_INSTALLED.compareAndSet(false, true)) return;
        Method oneArg = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
        oneArg.setAccessible(true);
        discoveryHandles.add(module.hook(oneArg).intercept(chain -> {
            Object result = chain.proceed();
            discoverLoadedClass(module, packageName, result);
            return result;
        }));
        Method twoArg = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
        twoArg.setAccessible(true);
        discoveryHandles.add(module.hook(twoArg).intercept(chain -> {
            Object result = chain.proceed();
            discoverLoadedClass(module, packageName, result);
            return result;
        }));
        log(4, "[DISCOVERY_READY] waiting for MainPanelContentDistributor");
    }

    private static void discoverLoadedClass(XposedModule module, String packageName, Object result) {
        if (INSTALLED.get() || !(result instanceof Class<?>)) return;
        Class<?> type = (Class<?>) result;
        if (!"MainPanelContentDistributor".equals(type.getSimpleName())) return;
        synchronized (LOCK) {
            if (INSTALLED.get()) return;
            try {
                install(module, type.getClassLoader(), packageName);
                INSTALLED.set(true);
                for (HookHandle handle : discoveryHandles) {
                    try { handle.unhook(); } catch (Throwable ignored) { }
                }
                discoveryHandles.clear();
                log(4, "[HOOKS_READY] discovered=" + type.getName()
                        + " loader=" + type.getClassLoader());
            } catch (Throwable failure) {
                log(6, "[DISCOVERY_INSTALL_FAILED] " + type.getName(), failure);
            }
        }
    }

    private static void install(XposedModule module, ClassLoader classLoader, String packageName)
            throws Exception {
        Class<?> distributor = findDistributor(classLoader);
        if (distributor == null) {
            throw new ClassNotFoundException("MainPanelContentDistributor not found in " + packageName);
        }
        Method distribute = findBooleanMethod(distributor, "distributePanels");
        if (distribute == null) {
            throw new NoSuchMethodException(distributor.getName() + ".distributePanels(boolean)");
        }
        List<HookHandle> installed = new ArrayList<>();
        try {
            installed.add(module.hook(distribute).intercept(chain -> {
                Object result = chain.proceed();
                if (read(SWAP_PANELS, false) || read(SWAP_BRIGHTNESS_VOLUME, false)) {
                    applyPanelSwap(chain.getThisObject());
                }
                return result;
            }));

            hookSliderSize(module, classLoader, installed);
            hookSliderLayout(module, classLoader, installed);
            hookHorizontalTouch(module, classLoader, installed);
        } catch (Throwable failure) {
            for (int i = installed.size() - 1; i >= 0; i--) {
                try { installed.get(i).unhook(); } catch (Throwable ignored) { }
            }
            throw failure;
        }
    }

    private static void hookSliderSize(XposedModule module, ClassLoader classLoader,
                                       List<HookHandle> installed) throws Exception {
        Class<?> holder = findClass(classLoader,
                "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder",
                "com.android.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder");
        if (holder == null) return;
        Method update = findNoArgMethod(holder, "updateSize");
        if (update == null) return;
        installed.add(module.hook(update).intercept(chain -> {
            Object result = chain.proceed();
            if (read(HORIZONTAL_SLIDERS, false)) configureHorizontal(chain.getThisObject());
            return result;
        }));
        for (Constructor<?> constructor : holder.getDeclaredConstructors()) {
            installed.add(module.hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                if (read(HORIZONTAL_SLIDERS, false)) configureHorizontal(chain.getThisObject());
                return result;
            }));
        }
        Method bind = findNoArgMethod(holder, "onBindViewHolder");
        if (bind != null) installed.add(module.hook(bind).intercept(chain -> {
            Object result = chain.proceed();
            if (read(HORIZONTAL_SLIDERS, false)) {
                Object self = chain.getThisObject();
                configureHorizontal(self);
                View item = (View) findField(self.getClass(), "itemView").get(self);
                item.post(() -> configureHorizontal(self));
            }
            return result;
        }));
    }

    private static void hookSliderLayout(XposedModule module, ClassLoader classLoader,
                                          List<HookHandle> installed) throws Exception {
        Class<?> recycler = findClass(classLoader,
                "miui.systemui.controlcenter.widget.MainPanelRecyclerView",
                "com.android.systemui.controlcenter.widget.MainPanelRecyclerView");
        if (recycler == null) throw new ClassNotFoundException("MainPanelRecyclerView");
        Method layout = findMethodBySignature(recycler, "onLayout",
                boolean.class, int.class, int.class, int.class, int.class);
        if (layout == null) throw new NoSuchMethodException("MainPanelRecyclerView.onLayout");
        installed.add(module.hook(layout).intercept(chain -> {
            boolean target = recycler.isInstance(chain.getThisObject());
            Object result = chain.proceed();
            if (target && read(HORIZONTAL_SLIDERS, false)) {
                layoutSliderPair((ViewGroup) chain.getThisObject());
            }
            return result;
        }));
        // Scrolling fills/recycles grid cells without invoking RecyclerView.onLayout.
        Method scroll = findMethodBySignature(recycler, "scrollStep",
                int.class, int.class, int[].class);
        if (scroll == null) throw new NoSuchMethodException("RecyclerView.scrollStep");
        installed.add(module.hook(scroll).intercept(chain -> {
            boolean target = recycler.isInstance(chain.getThisObject());
            Object result = chain.proceed();
            if (target && read(HORIZONTAL_SLIDERS, false)) {
                layoutSliderPair((ViewGroup) chain.getThisObject());
            }
            return result;
        }));
        Method scrolled = findMethodBySignature(recycler, "onScrolled", int.class, int.class);
        if (scrolled != null) installed.add(module.hook(scrolled).intercept(chain -> {
            Object result = chain.proceed();
            if (read(HORIZONTAL_SLIDERS, false)) {
                ViewGroup view = (ViewGroup) chain.getThisObject();
                view.post(() -> layoutSliderPair(view));
            }
            return result;
        }));
    }

    // Keep the native grid's two slider cells so the media tile stays beside them.
    // Only their final view rectangles are combined into a half-width, two-row stack.
    private static void layoutSliderPair(ViewGroup recycler) {
        List<View> sliders = new ArrayList<>();
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (child.getClass().getSimpleName().equals("ToggleSliderView")) sliders.add(child);
        }
        if (sliders.size() != 2) return;
        View brightness = null;
        View volume = null;
        for (View slider : sliders) {
            int kind = sliderKind(slider);
            if (kind == 1) brightness = slider;
            else if (kind == 2) volume = slider;
        }
        if (brightness == null || volume == null) {
            sliders.sort(Comparator.comparingInt(View::getLeft));
            brightness = sliders.get(0);
            volume = sliders.get(1);
        }
        if (read(SWAP_BRIGHTNESS_VOLUME, false)) {
            View temporary = brightness;
            brightness = volume;
            volume = temporary;
        }
        int left = Math.min(brightness.getLeft(), volume.getLeft());
        int right = Math.max(brightness.getRight(), volume.getRight());
        int top = Math.min(brightness.getTop(), volume.getTop());
        int bottom = Math.max(brightness.getBottom(), volume.getBottom());
        Integer gap = sliderGaps.get(recycler);
        if (gap == null) {
            gap = 2 * dimension(recycler.getContext(), "control_center_universal_margin", 4);
            sliderGaps.put(recycler, gap);
        }
        int height = (bottom - top - gap) / 2;
        if (height <= 0 || right <= left) return;
        placeSlider(brightness, left, top, right, top + height);
        placeSlider(volume, left, top + height + gap, right, bottom);
    }

    private static void placeSlider(View view, int left, int top, int right, int bottom) {
        view.measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        view.layout(left, top, right, bottom);
        view.invalidateOutline();
    }

    /** Returns 1 for brightness, 2 for volume, and 0 when a recycled item is unknown. */
    private static int sliderKind(View item) {
        try {
            View slider = findChild(item, "VerticalSeekBar");
            CharSequence description = slider == null ? null : slider.getContentDescription();
            if (description != null) {
                String text = description.toString().toLowerCase(java.util.Locale.ROOT);
                if (text.contains("brightness") || text.contains("亮度")) return 1;
                if (text.contains("volume") || text.contains("media") || text.contains("媒体")
                        || text.contains("音量")) return 2;
            }
            ViewGroup.LayoutParams params = item.getLayoutParams();
            if (params != null) {
                Object holder = readFieldValue(params, params.getClass(), "mViewHolder");
                if (holder != null) {
                    Object owner = readFieldValue(holder, holder.getClass(), "owner");
                    String name = owner == null ? "" : owner.getClass().getSimpleName();
                    if (name.contains("BrightnessSliderController")) return 1;
                    if (name.contains("VolumeSliderController")) return 2;
                }
            }
        } catch (Throwable ignored) {
            // Recycled views can be detached while RecyclerView is laying them out.
        }
        return 0;
    }

    private static void hookHorizontalTouch(XposedModule module, ClassLoader classLoader,
                                            List<HookHandle> installed) throws Exception {
        Class<?> injector = findClass(classLoader,
                "miui.systemui.widget.RelativeSeekBarInjector",
                "com.android.systemui.widget.RelativeSeekBarInjector");
        if (injector == null) throw new ClassNotFoundException("RelativeSeekBarInjector");
        Method transform = findMethodBySignature(injector, "transformTouchEvent", MotionEvent.class);
        if (transform == null) throw new NoSuchMethodException("RelativeSeekBarInjector.transformTouchEvent");
        installed.add(module.hook(transform).intercept(chain -> {
            if (read(HORIZONTAL_SLIDERS, false)) {
                Field vertical = findField(injector, "mVertical");
                if (vertical != null) {
                    vertical.setAccessible(true);
                    vertical.setBoolean(chain.getThisObject(), false);
                    if (!loggedHorizontalTouch) {
                        loggedHorizontalTouch = true;
                        log(4, "[HORIZONTAL_TOUCH_APPLIED] injector=" + injector.getName());
                    }
                }
            }
            return chain.proceed();
        }));
        Class<?> seekBar = findClass(classLoader,
                "miui.systemui.controlcenter.widget.VerticalSeekBar",
                "com.android.systemui.controlcenter.widget.VerticalSeekBar");
        if (seekBar != null) {
            Method create = findMethodByName(seekBar, "createGestureHelper", 1);
            if (create != null) installed.add(module.hook(create).intercept(chain -> {
                Object helper = chain.proceed();
                if (read(HORIZONTAL_SLIDERS, false) && helper != null) {
                    Field vertical = findField(helper.getClass(), "vertical");
                    if (vertical != null) {
                        vertical.setAccessible(true);
                        vertical.set(helper, Boolean.FALSE);
                    }
                }
                return helper;
            }));
        }

        // The slider's original onTouchEvent forwards through RelativeSeekBarInjector
        // and then SeekBar.dispatchTouchEvent, which invokes the controller's listener.
        // Do not replace it with setProgress(): that only changes the visual state and
        // skips BrightnessSliderController/VolumeSliderController's side effects.
        Class<?> helper = findClass(classLoader,
                "miui.systemui.controlcenter.windowview.GestureDispatcher$GestureHelper",
                "com.android.systemui.controlcenter.windowview.GestureDispatcher$GestureHelper");
        if (helper != null) {
            Method check = findMethodBySignature(helper, "check", boolean.class, boolean.class);
            if (check != null) installed.add(module.hook(check).intercept(chain -> {
                if (!read(HORIZONTAL_SLIDERS, false)) return chain.proceed();
                Object view = readFieldValue(chain.getThisObject(), helper, "view");
                if (view != null && seekBar != null && seekBar.isInstance(view)) {
                    // GestureDispatcher's first argument is true for a vertical drag.
                    // The horizontal slider must win only for horizontal movement.
                    return !((Boolean) chain.getArg(0));
                }
                return chain.proceed();
            }));
        }
    }

    private static Object readFieldValue(Object object, Class<?> type, String name) {
        try {
            Field field = findField(type, name);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static void configureHorizontal(Object holder) {
        try {
            View item = (View) findField(holder.getClass(), "itemView").get(holder);
            if (!(item instanceof ViewGroup)) return;
            Context context = item.getContext();
            int one = dimension(context, "control_center_universal_1_row_size", 72);
            int sliderId = identifier(context, "slider", "id");
            int iconId = identifier(context, "icon", "id");
            View slider = sliderId == 0 ? findChild(item, "VerticalSeekBar") : item.findViewById(sliderId);
            View icon = iconId == 0 ? findChild(item, "ImageView") : item.findViewById(iconId);
            // The parent grid keeps the original dimensions; onLayout places the stack.
            if (slider != null) {
                setSize(slider, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                slider.setRotation(0f);
                slider.setTranslationX(0f);
                slider.setTranslationY(0f);
            }
            if (icon != null) {
                ViewGroup.LayoutParams raw = icon.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                    lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                    lp.leftMargin = 0;
                    lp.topMargin = 0;
                    lp.bottomMargin = 0;
                    lp.rightMargin = 0;
                    lp.leftMargin = dimension(context, "control_center_universal_margin", 8);
                    icon.setLayoutParams(lp);
                }
                // XML gravity is recomputed by the recycled holder. Reassert the
                // final position after every bind/layout and keep it above fills.
                icon.setRotation(0f);
                icon.setScaleX(1f);
                icon.setScaleY(1f);
                icon.setTranslationX(0f);
                icon.setTranslationY(0f);
                icon.bringToFront();
            }
            if (slider instanceof SeekBar) {
                SeekBar seek = (SeekBar) slider;
                View progress = item.findViewById(identifier(context, "progress", "id"));
                if (progress != null) {
                    progress.setOutlineProvider(new ViewOutlineProvider() {
                        @Override public void getOutline(View view, Outline outline) {
                            float fraction = Math.max(0f, Math.min(1f,
                                    (seek.getProgress() - seek.getMin()) /
                                    (float) Math.max(1, seek.getMax() - seek.getMin())));
                            int filled = Math.round(view.getWidth() * fraction);
                            if (filled <= 0) outline.setEmpty();
                            else outline.setRoundRect(0, 0, filled, view.getHeight(),
                                    dimension(context, "toggle_slider_clip_round_corner_radius", 2));
                        }
                    });
                    progress.setClipToOutline(true);
                    progress.invalidateOutline();
                }
            }
            item.requestLayout();
            if (!loggedHorizontal) {
                loggedHorizontal = true;
                log(4, "[HORIZONTAL_APPLIED] item=" + item.getClass().getName());
            }
        } catch (Throwable failure) {
            log(5, "[HORIZONTAL_FAILED] " + failure, failure);
        }
    }

    private static void applyPanelSwap(Object distributor) {
        try {
            boolean swapPanels = read(SWAP_PANELS, false);
            boolean swapBrightnessVolume = read(SWAP_BRIGHTNESS_VOLUME, false);
            List<List<Object>> panelLists = panelLists(distributor);
            if (panelLists.isEmpty()) return;
            List<Object> mediaList = null;
            List<Object> sliderList = null;
            for (List<Object> list : panelLists) {
                if (contains(list, "MediaPlayerController")) mediaList = list;
                if (contains(list, "BrightnessSliderController") || contains(list, "VolumeSliderController")) {
                    sliderList = list;
                }
            }
            if (swapPanels && mediaList != null && sliderList != null && mediaList != sliderList) {
                swapGroups(mediaList, sliderList);
            } else {
                for (List<Object> list : panelLists) {
                    if (swapPanels) reorderInPlace(list);
                    else if (swapBrightnessVolume) reorderSlidersInPlace(list);
                }
            }
            if (!loggedSwap && swapPanels) {
                loggedSwap = true;
                log(4, "[PANEL_SWAP_APPLIED] media and slider groups reordered");
            }
        } catch (Throwable failure) {
            log(5, "[PANEL_SWAP_FAILED] " + failure, failure);
        }
    }

    private static void reorderInPlace(List<Object> list) {
        List<Object> targets = new ArrayList<>();
        for (Object item : list) if (isTarget(item)) targets.add(item);
        if (targets.size() < 2) return;
        int insertAt = Integer.MAX_VALUE;
        for (Object item : targets) insertAt = Math.min(insertAt, list.indexOf(item));
        list.removeAll(targets);
        List<Object> ordered = new ArrayList<>();
        if (read(SWAP_BRIGHTNESS_VOLUME, false)) {
            addMatching(ordered, targets, "VolumeSliderController");
            addMatching(ordered, targets, "BrightnessSliderController");
        } else {
            addMatching(ordered, targets, "BrightnessSliderController");
            addMatching(ordered, targets, "VolumeSliderController");
        }
        addMatching(ordered, targets, "MediaPlayerController");
        list.addAll(Math.min(insertAt, list.size()), ordered);
    }

    private static void reorderSlidersInPlace(List<Object> list) {
        List<Object> sliders = new ArrayList<>();
        sliders.addAll(matching(list, "BrightnessSliderController"));
        sliders.addAll(matching(list, "VolumeSliderController"));
        if (sliders.size() < 2) return;
        int insertAt = indexOfFirst(list, sliders);
        list.removeAll(sliders);
        if (read(SWAP_BRIGHTNESS_VOLUME, false)) {
            list.addAll(Math.min(insertAt, list.size()), matching(sliders, "VolumeSliderController"));
            list.addAll(Math.min(insertAt + 1, list.size()), matching(sliders, "BrightnessSliderController"));
        } else {
            list.addAll(Math.min(insertAt, list.size()), matching(sliders, "BrightnessSliderController"));
            list.addAll(Math.min(insertAt + 1, list.size()), matching(sliders, "VolumeSliderController"));
        }
    }

    private static void swapGroups(List<Object> mediaList, List<Object> sliderList) {
        List<Object> media = matching(mediaList, "MediaPlayerController");
        List<Object> sliders = new ArrayList<>();
        if (read(SWAP_BRIGHTNESS_VOLUME, false)) {
            sliders.addAll(matching(sliderList, "VolumeSliderController"));
            sliders.addAll(matching(sliderList, "BrightnessSliderController"));
        } else {
            sliders.addAll(matching(sliderList, "BrightnessSliderController"));
            sliders.addAll(matching(sliderList, "VolumeSliderController"));
        }
        if (media.isEmpty() || sliders.isEmpty()) return;
        int mediaAt = indexOfFirst(mediaList, media);
        int sliderAt = indexOfFirst(sliderList, sliders);
        mediaList.removeAll(media);
        sliderList.removeAll(sliders);
        mediaList.addAll(Math.min(sliderAt, mediaList.size()), sliders);
        sliderList.addAll(Math.min(mediaAt, sliderList.size()), media);
    }

    private static List<List<Object>> panelLists(Object distributor) throws IllegalAccessException {
        List<List<Object>> result = new ArrayList<>();
        for (Field field : allFields(distributor.getClass())) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            if (!field.getName().toLowerCase().contains("panelcontent")) continue;
            field.setAccessible(true);
            Object value = field.get(distributor);
            if (value instanceof List) result.add((List<Object>) value);
        }
        return result;
    }

    private static boolean contains(List<Object> list, String simpleName) {
        for (Object item : list) if (simple(item).contains(simpleName)) return true;
        return false;
    }

    private static boolean isTarget(Object item) {
        String name = simple(item);
        return name.contains("MediaPlayerController") || name.contains("BrightnessSliderController")
                || name.contains("VolumeSliderController");
    }

    private static List<Object> matching(List<Object> list, String simpleName) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) if (simple(item).contains(simpleName)) result.add(item);
        return result;
    }

    private static void addMatching(List<Object> out, List<Object> source, String name) {
        out.addAll(matching(source, name));
    }

    private static int indexOfFirst(List<Object> source, List<Object> values) {
        int result = Integer.MAX_VALUE;
        for (Object value : values) result = Math.min(result, source.indexOf(value));
        return result == Integer.MAX_VALUE ? source.size() : result;
    }

    private static String simple(Object object) {
        return object == null ? "" : object.getClass().getSimpleName();
    }

    private static SharedPreferences safePreferences(XposedModule module) {
        try { return module.getRemotePreferences(PREFERENCES); }
        catch (Throwable failure) { log(5, "[PREFERENCES_UNAVAILABLE] " + failure); return null; }
    }

    private static boolean read(String key, boolean fallback) {
        try { return settings != null && settings.getBoolean(key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }

    private static Class<?> findClass(ClassLoader classLoader, String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, classLoader); }
            catch (Throwable ignored) { }
        }
        return null;
    }

    private static Method findBooleanMethod(Class<?> type, String name) {
        for (Method method : allMethods(type)) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == boolean.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        return findMethodByName(type, name, 0);
    }

    private static Method findMethodByName(Class<?> type, String name, int count) {
        for (Method method : allMethods(type)) {
            if (method.getName().equals(name) && method.getParameterTypes().length == count) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findMethodBySignature(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (!Arrays.equals(method.getParameterTypes(), parameters)) continue;
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> result = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            result.addAll(Arrays.asList(c.getDeclaredMethods()));
        }
        return result;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            result.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return result;
    }

    private static Field findField(Class<?> type, String name) {
        for (Field field : allFields(type)) if (field.getName().equals(name)) return field;
        return null;
    }

    private static int identifier(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static int dimension(Context context, String name, int fallbackDp) {
        int id = identifier(context, name, "dimen");
        return id == 0 ? Math.round(fallbackDp * context.getResources().getDisplayMetrics().density)
                : context.getResources().getDimensionPixelSize(id);
    }

    private static void setSize(View view, int width, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) params = new ViewGroup.LayoutParams(width, height);
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }

    private static View findChild(View root, String simpleName) {
        if (root.getClass().getSimpleName().contains(simpleName)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = findChild(group.getChildAt(i), simpleName);
            if (child != null) return child;
        }
        return null;
    }

    private static void log(int level, String message) {
        if (framework != null) framework.log(level, TAG, message);
        Log.println(toAndroidLevel(level), TAG, message);
    }

    private static void log(int level, String message, Throwable failure) {
        if (framework != null) framework.log(level, TAG, message, failure);
        Log.println(toAndroidLevel(level), TAG, message + "\n" + Log.getStackTraceString(failure));
    }

    private static int toAndroidLevel(int level) {
        if (level >= 6) return Log.ERROR;
        if (level == 5) return Log.WARN;
        if (level <= 2) return Log.DEBUG;
        return Log.INFO;
    }
}
