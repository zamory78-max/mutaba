package com.mutaba.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView web;
    private String siteHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteHost = Uri.parse(BuildConfig.SITE_URL).getHost();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        // منشورات فيسبوك المضمّنة تحتاج ملفات تعريف الارتباط
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return handle(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(Uri.parse(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, R.string.offline, Toast.LENGTH_LONG).show();
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BuildConfig.SITE_URL);
        }
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    // استقبال رابط مُشارَك من تطبيق فيسبوك، وتحويل رابط المشاركة إلى رابط الصفحة الحقيقي
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        intent.setAction(Intent.ACTION_MAIN);
        if (text == null) text = "";
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) {
            Toast.makeText(this, R.string.no_link, Toast.LENGTH_LONG).show();
            return;
        }
        final String shared = m.group();
        Toast.makeText(this, R.string.reading_link, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String[] res = resolve(shared);
            String target;
            try {
                target = BuildConfig.SITE_URL + "?add=" + URLEncoder.encode(res[0], "UTF-8")
                        + "&name=" + URLEncoder.encode(res[1], "UTF-8") + "#added";
            } catch (Exception e) {
                target = BuildConfig.SITE_URL;
            }
            final String go = target;
            runOnUiThread(() -> web.loadUrl(go));
        }).start();
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final String CRAWLER = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

    private static boolean isGood(String u) {
        if (u == null) return false;
        Uri x = Uri.parse(u);
        String h = x.getHost(), p = x.getPath();
        if (h == null || !h.endsWith("facebook.com") || p == null || p.length() < 2) return false;
        return !p.startsWith("/share") && !p.startsWith("/login") && !p.startsWith("/checkpoint")
                && !p.startsWith("/l.php") && !p.startsWith("/sharer");
    }

    // يحاول عدة طرق لتحويل رابط المشاركة إلى رابط الصفحة، ويقرأ اسمها إن أمكن
    private String[] resolve(String start) {
        String[] r = follow(start, UA, true);
        if (isGood(r[0])) return r;
        String[] c = follow(start, CRAWLER, false);
        if (isGood(c[0])) return new String[]{c[0], c[1].isEmpty() ? r[1] : c[1]};
        String alt = start.replace("://www.facebook.com", "://m.facebook.com");
        if (!alt.equals(start)) {
            String[] m = follow(alt, CRAWLER, false);
            if (isGood(m[0])) return m;
        }
        return new String[]{start, c[1].isEmpty() ? r[1] : c[1]};
    }

    // يتتبع التحويلات ثم يقرأ الصفحة؛ يعيد [الرابط النهائي، الاسم]
    private String[] follow(String start, String ua, boolean withCookies) {
        String url = start, name = "";
        String cookies = null;
        if (withCookies) {
            try { cookies = CookieManager.getInstance().getCookie("https://www.facebook.com"); } catch (Exception ignored) {}
        }
        try {
            for (int hop = 0; hop < 8; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Accept", "text/html");
                c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
                if (cookies != null && c.getURL().getHost().endsWith("facebook.com")) c.setRequestProperty("Cookie", cookies);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) break;
                    String next = new URL(new URL(url), loc).toString();
                    if (next.contains("/login")) {
                        String n = Uri.parse(next).getQueryParameter("next");
                        if (isGood(n)) url = n;
                        break;
                    }
                    url = next;
                    continue;
                }
                if (code == 200) {
                    String html = readHtml(c);
                    name = titleOf(html);
                    if (!isGood(url)) {
                        String canon = meta(html, "<meta[^>]+property=\"og:url\"[^>]+content=\"([^\"]+)\"");
                        if (canon.isEmpty()) canon = meta(html, "<meta[^>]+content=\"([^\"]+)\"[^>]+property=\"og:url\"");
                        if (canon.isEmpty()) canon = meta(html, "<link[^>]+rel=\"canonical\"[^>]+href=\"([^\"]+)\"");
                        canon = unescape(canon);
                        if (isGood(canon)) url = canon;
                        else {
                            String id = meta(html, "fb://(?:page|profile)/(?:\\?id=)?(\\d{5,})");
                            if (!id.isEmpty()) url = "https://www.facebook.com/profile.php?id=" + id;
                        }
                    }
                }
                c.disconnect();
                break;
            }
        } catch (Exception ignored) {}
        return new String[]{url, name};
    }

    private static String meta(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private String readHtml(HttpURLConnection c) {
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[400000];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return new String(buf, 0, total, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String titleOf(String html) {
        String t = meta(html, "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"");
        if (t.isEmpty()) t = meta(html, "<title[^>]*>([^<]+)</title>");
        t = unescape(t).replaceAll("\\s*\\|\\s*Facebook$", "").trim();
        if (t.equalsIgnoreCase("Facebook") || t.toLowerCase().contains("log in") || t.contains("تسجيل")) return "";
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    @SuppressWarnings("deprecation")
    private static String unescape(String s) {
        return s.isEmpty() ? s : android.text.Html.fromHtml(s).toString();
    }

    // روابط المنصة تبقى داخل التطبيق، وروابط فيسبوك وغيرها تُفتح في تطبيق فيسبوك أو المتصفح
    private boolean handle(Uri uri) {
        String host = uri.getHost();
        if (host != null && host.equalsIgnoreCase(siteHost)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            return false;
        }
        return true;
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
