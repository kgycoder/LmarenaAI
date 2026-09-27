package com.lmarena.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lmarena.app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private static final String DIRECT_URL = "https://arena.ai/text/direct";
    private static final String AGENT_URL  = "https://arena.ai/agent/";
    private static final String PREFS_NAME = "lmarena_prefs";

    private ActivityMainBinding binding;
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private SharedPreferences prefs;
    private String chatMode = "direct";

    // 로그인 감지 폴링용
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean loginCheckRunning = false;

    // ── dom_selectors.py 기반 JS 상수 ──────────────────────────────
    // LOGGED_OUT_MARKERS: "Experience the frontier" 문구 존재 여부
    // SIGN_IN_MARKERS: 로그인 버튼 존재 여부
    // LOGGED_IN_MARKERS: ProseMirror contenteditable 존재 여부
    private static final String JS_LOGIN_STATE_CHECK =
        "(function() {" +
        "  var result = {state: 'unknown', url: location.href};" +

        // 1) SIGN_IN_MARKERS - 로그인 버튼이 보이면 무조건 로그아웃
        "  var signInSelectors = [" +
        "    \"button\", \"a\"" +
        "  ];" +
        "  var allBtns = document.querySelectorAll('button, a');" +
        "  for (var i = 0; i < allBtns.length; i++) {" +
        "    var t = (allBtns[i].innerText || '').trim();" +
        "    if (t === 'Log in' || t === 'Sign in' || t === 'Sign up') {" +
        "      result.state = 'logged_out';" +
        "      return JSON.stringify(result);" +
        "    }" +
        "  }" +

        // 2) LOGGED_OUT_MARKERS - "Experience the frontier" 텍스트 존재
        "  var bodyText = document.body ? document.body.innerText : '';" +
        "  if (/experience\\s+the\\s+frontier/i.test(bodyText)) {" +
        "    result.state = 'logged_out';" +
        "    return JSON.stringify(result);" +
        "  }" +

        // 3) LOGGED_IN_MARKERS - ProseMirror 입력창 존재
        "  var loginSelectors = [" +
        "    \"div.ProseMirror[contenteditable='true']\"," +
        "    \"div[contenteditable='true'][translate='no']\"," +
        "    \"div[contenteditable='true']\"," +
        "    \"textarea[name='message']\"," +
        "    \"[data-testid='chat-input']\"" +
        "  ];" +
        "  for (var j = 0; j < loginSelectors.length; j++) {" +
        "    if (document.querySelector(loginSelectors[j])) {" +
        "      result.state = 'logged_in';" +
        "      return JSON.stringify(result);" +
        "    }" +
        "  }" +

        "  result.state = 'unknown';" +
        "  return JSON.stringify(result);" +
        "})();";

    // 생성 중 감지 (GENERATING_INDICATOR 셀렉터 이식)
    private static final String JS_GENERATING_CHECK =
        "(function() {" +
        "  var s = [" +
        "    \"button[aria-label='Stop generation']\"," +
        "    \"button[aria-label='Stop generating']\"," +
        "    \"button:has(svg rect[width='12'][height='12'])\"" +
        "  ];" +
        "  for (var i=0; i<s.length; i++) {" +
        "    try { if (document.querySelector(s[i])) return 'true'; } catch(e) {}" +
        "  }" +
        "  return 'false';" +
        "})();";

    // 모바일 사이드바 열기 (Open sidebar 버튼 클릭 → Log in 버튼 노출)
    private static final String JS_OPEN_SIDEBAR =
        "(function() {" +
        "  var btn = document.querySelector('button[aria-label=\"Open sidebar\"]');" +
        "  if (btn) { btn.click(); return 'clicked'; }" +
        "  return 'not_found';" +
        "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
        getWindow().setNavigationBarColor(Color.parseColor("#111111"));

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs    = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        chatMode = prefs.getString("chat_mode", "direct");
        webView      = binding.webView;
        swipeRefresh = binding.swipeRefresh;

        setupWebView();
        setupBottomBar();
        updateModeButtons();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        loadCurrentMode();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new JsBridge(), "ArenaAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (isAllowedHost(url)) {
                    view.loadUrl(url);
                    return true;
                }
                return true; // 외부 링크 차단
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                // 페이지 로드 완료 → 사이드바 열기 + 로그인 상태 폴링 시작
                view.evaluateJavascript(JS_OPEN_SIDEBAR, null);
                startLoginPolling();
            }

            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                swipeRefresh.setRefreshing(false);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                swipeRefresh.setRefreshing(progress < 100);
            }
        });

        swipeRefresh.setColorSchemeColors(Color.parseColor("#7c6fcd"));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1a1a1a"));
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    // ── 로그인 상태 폴링 ──────────────────────────────────────────────
    private static final int POLL_INTERVAL_MS = 1500;
    private static final int POLL_MAX_COUNT   = 60; // 최대 90초
    private int pollCount = 0;

    private void startLoginPolling() {
        stopLoginPolling();
        loginCheckRunning = true;
        pollCount = 0;
        scheduleLoginCheck();
    }

    private void stopLoginPolling() {
        loginCheckRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleLoginCheck() {
        if (!loginCheckRunning) return;
        handler.postDelayed(this::doLoginCheck, POLL_INTERVAL_MS);
    }

    private void doLoginCheck() {
        if (!loginCheckRunning || webView == null) return;
        pollCount++;

        webView.evaluateJavascript(JS_LOGIN_STATE_CHECK, result -> {
            if (result == null || result.equals("null")) {
                if (pollCount < POLL_MAX_COUNT) scheduleLoginCheck();
                return;
            }
            // result는 JSON 문자열 (따옴표로 감싸인 경우 제거)
            String json = result.replaceAll("^\"|\"$", "")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
            String state   = extractJsonField(json, "state");
            String pageUrl = extractJsonField(json, "url");

            if ("logged_in".equals(state)) {
                // 로그인 완료 - 현재 URL이 채팅 페이지가 아니면 이동
                stopLoginPolling();
                if (pageUrl != null && !pageUrl.contains("/text/") && !pageUrl.contains("/agent/")) {
                    String target = "agent".equals(chatMode) ? AGENT_URL : DIRECT_URL;
                    webView.loadUrl(target);
                }
                // 생성 중 감지 루프 시작
                startGeneratingWatch();

            } else if ("logged_out".equals(state)) {
                // 아직 로그아웃 상태 - 사이드바 열기 재시도
                webView.evaluateJavascript(JS_OPEN_SIDEBAR, null);
                if (pollCount < POLL_MAX_COUNT) scheduleLoginCheck();

            } else {
                // unknown - 계속 폴링
                if (pollCount < POLL_MAX_COUNT) scheduleLoginCheck();
            }
        });
    }

    // ── 생성 중 감지 루프 ────────────────────────────────────────────
    private final Runnable generatingWatcher = new Runnable() {
        @Override
        public void run() {
            if (webView == null) return;
            webView.evaluateJavascript(JS_GENERATING_CHECK, value -> {
                boolean generating = "true".equals(value) ||
                                     (value != null && value.contains("true"));
                getWindow().setStatusBarColor(
                    generating ? Color.parseColor("#3d2f99")
                               : Color.parseColor("#0a0a0a")
                );
            });
            handler.postDelayed(this, 1000);
        }
    };

    private void startGeneratingWatch() {
        handler.removeCallbacks(generatingWatcher);
        handler.post(generatingWatcher);
    }

    // ── 간단한 JSON 필드 추출 (외부 라이브러리 없이) ─────────────────
    private String extractJsonField(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private boolean isAllowedHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            return host.endsWith("arena.ai")
                || host.endsWith("lmarena.ai")
                || host.endsWith("google.com")
                || host.endsWith("googleapis.com")
                || host.endsWith("gstatic.com")
                || host.endsWith("accounts.google.com")
                || host.endsWith("recaptcha.net");
        } catch (Exception e) { return false; }
    }

    // ── JS → Native 브리지 ───────────────────────────────────────────
    public class JsBridge {
        @JavascriptInterface
        public void onLoginStateChanged(String state) {
            runOnUiThread(() -> {
                if ("logged_in".equals(state)) {
                    stopLoginPolling();
                    String target = "agent".equals(chatMode) ? AGENT_URL : DIRECT_URL;
                    webView.loadUrl(target);
                    startGeneratingWatch();
                }
            });
        }
    }

    // ── 하단 탭 ──────────────────────────────────────────────────────
    private void setupBottomBar() {
        binding.btnDirect.setOnClickListener(v -> switchMode("direct"));
        binding.btnAgent.setOnClickListener(v -> switchMode("agent"));
        binding.btnReload.setOnClickListener(v -> {
            webView.reload();
            Toast.makeText(this, "새로고침 중...", Toast.LENGTH_SHORT).show();
        });
    }

    private void switchMode(String mode) {
        if (mode.equals(chatMode)) return;
        chatMode = mode;
        prefs.edit().putString("chat_mode", mode).apply();
        loadCurrentMode();
        updateModeButtons();
    }

    private void updateModeButtons() {
        boolean d = "direct".equals(chatMode);
        binding.btnDirect.setAlpha(d ? 1.0f : 0.4f);
        binding.btnAgent.setAlpha(d ? 0.4f : 1.0f);
    }

    private void loadCurrentMode() {
        webView.loadUrl("direct".equals(chatMode) ? DIRECT_URL : AGENT_URL);
    }

    // ── 생명주기 ─────────────────────────────────────────────────────
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        stopLoginPolling();
        handler.removeCallbacks(generatingWatcher);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
