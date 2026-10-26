package com.example.sotavpntoggle;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Служба специальных возможностей.
 *
 * Что делает по команде:
 *   1. открывает шторку быстрых настроек;
 *   2. ищет плитку, в подписи которой есть заданный текст (по умолчанию «Sota»);
 *   3. нажимает её (или не нажимает, если VPN уже в нужном состоянии);
 *   4. закрывает шторку — на экране остаётся то, что было открыто.
 *
 * В остальное время служба ничего не делает: события экрана игнорируются.
 */
public class TapAccessibilityService extends AccessibilityService {

    public interface ResultListener {
        void onResult(boolean ok, String log);
    }

    public static final String MODE_TOGGLE = "toggle";
    public static final String MODE_ON = "on";
    public static final String MODE_OFF = "off";

    private static final String SYSTEMUI = "com.android.systemui";
    private static final int MAX_ATTEMPTS = 6;
    private static final long FIRST_WAIT_MS = 650;  // ждём, пока шторка раскроется
    private static final long RETRY_WAIT_MS = 300;

    // Порядок важен: сначала проверяем «выкл», потом «вкл».
    // «Включить»/«Отключить» — это команды, а не состояние, поэтому (?!юч).
    private static final Pattern OFF = Pattern.compile(
            "(выкл|откл(?!юч)|отключен|не подключ|неактивн|не активн"
            + "|\\boff\\b|disconnected|inactive|not connected)");
    private static final Pattern ON = Pattern.compile(
            "(вкл(?!юч)|включен|подключен|активн|\\bon\\b|\\bconnected\\b|\\bactive\\b)");

    private static volatile TapAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    // ---------- жизненный цикл ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* не нужно */ }

    @Override
    public void onInterrupt() { }

    // ---------- публичный вход ----------

    public static boolean isRunning() {
        return instance != null;
    }

    /** Запустить переключение. Возвращает false, если служба не включена. */
    public static boolean run(String label, String mode, ResultListener listener) {
        TapAccessibilityService s = instance;
        if (s == null) {
            if (listener != null) listener.onResult(false, "Служба спец. возможностей выключена.");
            return false;
        }
        s.start(label, mode, listener);
        return true;
    }

    // ---------- логика ----------

    private void start(final String label, final String mode, final ResultListener listener) {
        if (busy) return;               // защита от двойного срабатывания NFC
        busy = true;
        final StringBuilder log = new StringBuilder();
        final String needle = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            finishRun(listener, false, log.append("Не задан текст плитки."));
            return;
        }
        // Небольшая пауза, чтобы невидимый TriggerActivity успел закрыться.
        handler.postDelayed(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                attempt(needle, mode, listener, 0, log);
            } catch (Throwable t) {
                closeShade();
                finishRun(listener, false, log.append("Ошибка: ").append(t));
            }
        }, 150);
    }

    private void attempt(final String needle, final String mode, final ResultListener listener,
                         final int n, final StringBuilder log) {
        handler.postDelayed(() -> {
            try {
                List<String> seen = new ArrayList<>();
                AccessibilityNodeInfo tile = findTile(needle, seen);
                if (tile != null) {
                    handleTile(tile, mode, listener, log);
                    return;
                }
                if (n + 1 < MAX_ATTEMPTS) {
                    // Со второй неудачи пробуем перелистнуть страницу плиток.
                    if (n >= 1) scrollTilesForward();
                    attempt(needle, mode, listener, n + 1, log);
                } else {
                    log.append("Плитка с текстом «").append(needle).append("» не найдена.\n\n")
                       .append("Что видно в шторке (подписи элементов):\n");
                    if (seen.isEmpty()) log.append("— ничего (шторка не открылась?)\n");
                    for (String s : seen) log.append("• ").append(s).append('\n');
                    closeShade();
                    finishRun(listener, false, log);
                }
            } catch (Throwable t) {
                closeShade();
                finishRun(listener, false, log.append("Ошибка: ").append(t));
            }
        }, n == 0 ? FIRST_WAIT_MS : RETRY_WAIT_MS);
    }

    private void handleTile(AccessibilityNodeInfo tile, String mode,
                            ResultListener listener, StringBuilder log) {
        AccessibilityNodeInfo target = clickableAncestor(tile);
        if (target == null) target = tile;

        Boolean on = readState(tile, target);
        log.append("Найдена плитка: ").append(describe(target)).append('\n')
           .append("Состояние до: ")
           .append(on == null ? "не удалось определить" : (on ? "включено" : "выключено"))
           .append('\n');

        boolean needClick;
        if (MODE_ON.equals(mode)) {
            needClick = on == null || !on;
        } else if (MODE_OFF.equals(mode)) {
            needClick = on == null || on;
        } else {
            needClick = true;
        }
        if (on == null && !MODE_TOGGLE.equals(mode)) {
            log.append("Состояние неизвестно — нажимаю как обычный переключатель.\n");
        }

        if (needClick) {
            boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) {
                tapCenter(target);
                log.append("Нажато жестом по координатам плитки.\n");
            } else {
                log.append("Нажато.\n");
            }
        } else {
            log.append("VPN уже в нужном состоянии — ничего не нажимаю.\n");
        }

        handler.postDelayed(() -> {
            closeShade();
            finishRun(listener, true, log);
        }, needClick ? 400 : 100);
    }

    private void finishRun(ResultListener listener, boolean ok, StringBuilder log) {
        busy = false;
        if (listener != null) listener.onResult(ok, log.toString());
    }

    // ---------- поиск плитки ----------

    private List<AccessibilityNodeInfo> systemUiRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && isSystemUi(r)) roots.add(r);
            }
        } catch (Throwable ignored) { }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && isSystemUi(active)) roots.add(active);
        return roots;
    }

    private static boolean isSystemUi(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p != null && SYSTEMUI.contentEquals(p);
    }

    /**
     * Ищет узел, в тексте/описании которого есть needle.
     * Предпочитает то, что похоже на плитку быстрых настроек,
     * и никогда не берёт уведомления (чтобы не открыть приложение соты).
     */
    private AccessibilityNodeInfo findTile(String needle, List<String> seen) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo root : systemUiRoots()) {
            Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
            stack.push(root);
            int visited = 0;
            while (!stack.isEmpty() && visited < 3000) {
                AccessibilityNodeInfo node = stack.pop();
                visited++;
                String t = label(node);
                if (!t.isEmpty()) {
                    AccessibilityNodeInfo target = clickableAncestor(node);
                    if (target != null && seen.size() < 60 && !seen.contains(t)) seen.add(t);
                    if (target != null && t.toLowerCase(Locale.ROOT).contains(needle)
                            && !insideNotificationList(node)) {
                        if (looksLikeTile(target)) return node;
                        if (fallback == null) fallback = node;
                    }
                }
                for (int i = node.getChildCount() - 1; i >= 0; i--) {
                    AccessibilityNodeInfo c = node.getChild(i);
                    if (c != null) stack.push(c);
                }
            }
        }
        return fallback;
    }

    private static String label(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.getText() != null) sb.append(n.getText());
        if (n.getContentDescription() != null) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(n.getContentDescription());
        }
        return sb.toString().replace('\n', ' ').trim();
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 7 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean looksLikeTile(AccessibilityNodeInfo n) {
        if (n.isCheckable()) return true;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        String id = n.getViewIdResourceName() == null ? ""
                : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
        return cls.contains("Switch") || cls.contains("Button") || cls.contains("Tile")
                || id.contains("tile") || id.contains("qs");
    }

    /** true, если узел лежит внутри списка уведомлений. */
    private static boolean insideNotificationList(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 14 && cur != null; i++) {
            String id = cur.getViewIdResourceName() == null ? ""
                    : cur.getViewIdResourceName().toLowerCase(Locale.ROOT);
            String cls = cur.getClassName() == null ? "" : cur.getClassName().toString();
            if (id.contains("stack_scroller") || id.contains("notification_row")
                    || id.contains("expandable") || id.contains("notification_container")
                    || id.contains("notification_list") || cls.contains("NotificationRow")
                    || cls.contains("StackScroll")) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    /** Определить, включён ли VPN по плитке. null — не удалось. */
    private static Boolean readState(AccessibilityNodeInfo tile, AccessibilityNodeInfo target) {
        AccessibilityNodeInfo[] nodes = {target, tile};
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null && n.isCheckable()) return n.isChecked();
        }
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            Boolean b = null;
            if (Build.VERSION.SDK_INT >= 30) b = parseState(n.getStateDescription());
            if (b == null) b = parseState(n.getContentDescription());
            if (b == null) b = parseState(n.getText());
            if (b != null) return b;
        }
        return null;
    }

    private static Boolean parseState(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().toLowerCase(Locale.ROOT);
        if (OFF.matcher(s).find()) return Boolean.FALSE;
        if (ON.matcher(s).find()) return Boolean.TRUE;
        return null;
    }

    private static String describe(AccessibilityNodeInfo n) {
        String t = label(n);
        if (t.isEmpty()) {
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null && !label(c).isEmpty()) { t = label(c); break; }
            }
        }
        return t.isEmpty() ? "(без подписи)" : t;
    }

    // ---------- действия ----------

    private void scrollTilesForward() {
        for (AccessibilityNodeInfo root : systemUiRoots()) {
            Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
            stack.push(root);
            int visited = 0;
            while (!stack.isEmpty() && visited < 3000) {
                AccessibilityNodeInfo node = stack.pop();
                visited++;
                if (node.isScrollable()) {
                    String cls = node.getClassName() == null ? "" : node.getClassName().toString();
                    String id = node.getViewIdResourceName() == null ? ""
                            : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
                    if (cls.contains("ViewPager") || id.contains("pager") || id.contains("paged")) {
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                        return;
                    }
                }
                for (int i = node.getChildCount() - 1; i >= 0; i--) {
                    AccessibilityNodeInfo c = node.getChild(i);
                    if (c != null) stack.push(c);
                }
            }
        }
    }

    private void tapCenter(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        dispatchGesture(g, null, null);
    }

    private void closeShade() {
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }
}
