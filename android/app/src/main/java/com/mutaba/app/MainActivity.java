package com.mutaba.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    // التبويبات: الاسم الظاهر، وقيمة عامل التصفية في خلاصات فيسبوك
    private static final String[][] TABS = {
            {"الصفحات", "pages"},
            {"المجموعات", "groups"},
            {"الأصدقاء", "friends"},
            {"المفضلة", "favorites"}
    };
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int BLUE = Color.parseColor("#1f5fc4");
    private static final int GREY = Color.parseColor("#59647a");
    private static final int FILE_REQ = 41;

    // في عرض الحاسوب: نضبط عرض الصفحة ليتسع عمود المنشورات كاملاً داخل شاشة الهاتف
    // في عرض الحاسوب: نخفي القوائم الجانبية لفيسبوك ونضبط العرض.
    // فحص خفيف كل ثانية ونصف، لأن فيسبوك يعيد رسم القائمة أحياناً
    private static final String FIT_JS = "(function(){"
            + "function run(){var h=document.head||document.documentElement;if(!h)return;"
            + "var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m);}"
            + "var c='width=560, user-scalable=yes';if(m.content!==c)m.content=c;"
            + "if(!document.getElementById('mtb-css')){var st=document.createElement('style');st.id='mtb-css';"
            + "st.textContent='[role=complementary],[data-mtb-hide]{display:none!important}[role=main]{max-width:100%!important;min-width:0!important}';h.appendChild(st);}"
            + "var main=document.querySelector('[role=main]');"
            + "var navs=document.querySelectorAll('[role=navigation]');"
            + "for(var i=0;i<navs.length;i++){var n=navs[i];"
            + "if(n.closest('[role=banner]')||n.closest('[role=dialog]')||(main&&n.contains(main)))continue;"
            + "var t=n;while(t.parentElement&&t.parentElement!==document.body&&t.parentElement.children.length===1&&!(main&&t.parentElement.contains(main)))t=t.parentElement;"
            + "if(!t.hasAttribute('data-mtb-hide'))t.setAttribute('data-mtb-hide','');}}"
            + "run();if(!window.__mtbInt)window.__mtbInt=setInterval(function(){if(!document.hidden)run();},1500);})();";

    // التبديل بين التبويبات داخل فيسبوك دون إعادة تحميل التطبيق كله
    private static String switchJs(String filter) {
        return "(function(){var a=document.querySelector('a[href*=\"filter=" + filter + "\"]');"
                + "if(a){a.click();window.scrollTo(0,0);return 'ok';}return 'no';})()";
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable fitTask = this::fitNow;

    private WebView web;
    private ProgressBar progress;
    private TextView[] tabViews;
    private SharedPreferences prefs;
    private int current;
    private boolean desktop;
    private long lastForced;
    private ValueCallback<Uri[]> fileCallback;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private String tabUrl(int i) {
        return "https://www.facebook.com/?filter=" + TABS[i][1] + "&sk=h_chr";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mutaba", MODE_PRIVATE);
        current = prefs.getInt("tab", 0);
        desktop = prefs.getBoolean("desktop", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#d4dcea"));
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setBackgroundColor(Color.WHITE);
        tabViews = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = makeBarItem(TABS[i][0]);
            t.setOnClickListener(v -> openTab(idx));
            tabViews[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, dp(54), 1f));
        }
        TextView more = makeBarItem("⋯");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setOnClickListener(this::showMenu);
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(54)));
        root.addView(bar);

        setContentView(root);
        setupWeb();
        highlight();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(tabUrl(current));
    }

    private TextView makeBarItem(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(GREY);
        t.setClickable(true);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        return t;
    }

    private void highlight() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == current;
            tabViews[i].setTextColor(on ? BLUE : GREY);
            tabViews[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void openTab(int i) {
        current = i;
        prefs.edit().putInt("tab", i).apply();
        highlight();
        lastForced = SystemClock.elapsedRealtime();
        String now = web.getUrl();
        if (now != null && isFacebook(Uri.parse(now).getHost()) && !now.contains("/login")) {
            web.evaluateJavascript(switchJs(TABS[i][1]), res -> {
                if (res == null || !res.contains("ok")) web.loadUrl(tabUrl(i));
                else fit();
            });
        } else {
            web.loadUrl(tabUrl(i));
        }
    }

    private void fitNow() {
        if (desktop && web != null) web.evaluateJavascript(FIT_JS, null);
    }

    // يُشغَّل فوراً ثم مرة أخرى بعد لحظة؛ وبعدها يتولى الفحص الدوري داخل الصفحة الباقي
    private void fit() {
        if (!desktop) return;
        handler.removeCallbacks(fitTask);
        fitNow();
        handler.postDelayed(fitTask, 800);
    }

    private void applyMode() {
        WebSettings s = web.getSettings();
        s.setUserAgentString(desktop ? DESKTOP_UA : null);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(desktop);
        s.setDisplayZoomControls(false);
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);   // لا تشغيل تلقائي للفيديوهات
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) s.setOffscreenPreRaster(true); // تمرير أنعم
        applyMode();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(Uri.parse(url));
            }

            // يمنع العودة إلى الخلاصة العامة المليئة بالاقتراحات، ويعيد إلى التبويب المختار
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (isGeneralFeed(Uri.parse(url))) forceTab();
                else fit();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                fit();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                fit();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, R.string.offline, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static boolean isFacebook(String host) {
        return host != null && (host.equals("facebook.com") || host.endsWith(".facebook.com")
                || host.equals("fb.com") || host.endsWith(".fb.com"));
    }

    private static boolean isGeneralFeed(Uri u) {
        if (!isFacebook(u.getHost())) return false;
        String path = u.getPath();
        boolean home = path == null || path.isEmpty() || path.equals("/") || path.equals("/home.php");
        if (!home) return false;
        String q = u.getQuery();
        return q == null || !q.contains("filter=");
    }

    private void forceTab() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastForced < 5000) {
            // فيسبوك تجاهل عامل التصفية في هذا الوضع، فننتقل مرة واحدة إلى وضع الحاسوب
            if (!desktop) {
                desktop = true;
                prefs.edit().putBoolean("desktop", true).apply();
                applyMode();
                Toast.makeText(this, "تم التبديل إلى عرض الحاسوب ليعمل فرز الصفحات", Toast.LENGTH_LONG).show();
                lastForced = now;
                web.loadUrl(tabUrl(current));
            } else {
                Toast.makeText(this, "فيسبوك لم يعرض خلاصة الصفحات. جرّب تبويباً آخر أو أعد المحاولة لاحقاً.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        lastForced = now;
        web.loadUrl(tabUrl(current));
    }

    // صفحات فيسبوك تبقى داخل التطبيق، والروابط الخارجية تُفتح في المتصفح
    private boolean handle(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (host != null && host.startsWith("l.") && isFacebook(host)) {
                String target = uri.getQueryParameter("u");
                if (target != null) return openOutside(Uri.parse(target));
            }
            if (isFacebook(host)) {
                if (isGeneralFeed(uri)) {
                    lastForced = SystemClock.elapsedRealtime();
                    web.loadUrl(tabUrl(current));
                    return true;
                }
                return false;
            }
            return openOutside(uri);
        }
        return openOutside(uri);
    }

    private boolean openOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "تحديث");
        m.getMenu().add(0, 2, 1, desktop ? "عرض الهاتف" : "عرض الحاسوب");
        m.getMenu().add(0, 4, 2, "تنظيف الذاكرة المؤقتة");
        m.getMenu().add(0, 3, 3, "تسجيل الخروج");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    web.reload();
                    return true;
                case 2:
                    desktop = !desktop;
                    prefs.edit().putBoolean("desktop", desktop).apply();
                    applyMode();
                    lastForced = SystemClock.elapsedRealtime();
                    web.loadUrl(tabUrl(current));
                    return true;
                case 4:
                    web.clearCache(true);
                    Toast.makeText(this, "تم التنظيف. سيُعاد تحميل الخلاصة.", Toast.LENGTH_SHORT).show();
                    web.loadUrl(tabUrl(current));
                    return true;
                case 3:
                    CookieManager.getInstance().removeAllCookies(v -> {
                        CookieManager.getInstance().flush();
                        web.clearCache(true);
                        web.clearHistory();
                        web.loadUrl(tabUrl(current));
                    });
                    return true;
            }
            return false;
        });
        m.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        handler.removeCallbacks(fitTask);
        web.onPause();
        web.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) web.freeMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
