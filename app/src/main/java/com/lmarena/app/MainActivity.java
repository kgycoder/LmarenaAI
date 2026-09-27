package com.lmarena.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상태바/네비게이션 바 다크 색상
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

        // 쿠키 영속 저장
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        loadCurrentMode();
    }

    // ── WebView ───────────────────────────────────────────────────────
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
        // Mixed content: arena.ai는 HTTPS이므로 NEVER_ALLOW 유지
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new JsBridge(), "ArenaAndroid");

        // ── WebViewClient: URL 필터링 ──
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // WebView 안에서 열어야 할 도메인 목록
                if (isAllowedHost(url)) {
                    view.loadUrl(url);   // 직접 로드 (인터셉트 안 함)
                    return true;         // true = WebViewClient가 처리했다고 알림
                }
                // 그 외 외부 링크는 무시 (시스템 브라우저 열지 않음)
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // 네트워크 오류 시 새로고침 스피너 중단
                swipeRefresh.setRefreshing(false);
            }
        });

        // ── WebChromeClient: 로딩 진행 ──
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress == 100) {
                    swipeRefresh.setRefreshing(false);
                    injectHelpers();
                } else {
                    swipeRefresh.setRefreshing(true);
                }
            }
        });

        // SwipeRefresh
        swipeRefresh.setColorSchemeColors(Color.parseColor("#7c6fcd"));
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1a1a1a"));
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
    }

    /** arena.ai 관련 도메인이면 WebView 안에서 처리 */
    private boolean isAllowedHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            return host.endsWith("arena.ai")
                || host.endsWith("lmarena.ai")
                || host.endsWith("google.com")
                || host.endsWith("googleapis.com")
                || host.endsWith("gstatic.com")
                || host.endsWith("recaptcha.net");
        } catch (Exception e) {
            return false;
        }
    }

    // ── 하단 탭 ────────────────────────────────────────────────────────
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
        boolean isDirect = "direct".equals(chatMode);
        binding.btnDirect.setAlpha(isDirect ? 1.0f : 0.4f);
        binding.btnAgent.setAlpha(isDirect ? 0.4f : 1.0f);
    }

    private void loadCurrentMode() {
        String url = "direct".equals(chatMode) ? DIRECT_URL : AGENT_URL;
        webView.loadUrl(url);
    }

    // ── JS → Native 브리지 ────────────────────────────────────────────
    private void injectHelpers() {
        webView.evaluateJavascript(
            "(function(){" +
            // 모바일에서 사이드바 버튼 클릭 → 로그인 버튼 노출
            "  var sb=document.querySelector('button[aria-label=\"Open sidebar\"]');" +
            "  if(sb) sb.click();" +
            // 생성 중 감지 → 상단 바 색상 표시
            "  if(!window.__lmaWatcher){" +
            "    window.__lmaWatcher=true;" +
            "    setInterval(function(){" +
            "      var s=!!document.querySelector(\"button[aria-label='Stop generation']\");" +
            "      if(window.ArenaAndroid) ArenaAndroid.onGenerating(s);" +
            "    },800);" +
            "  }" +
            "})();",
            null
        );
    }

    public class JsBridge {
        @JavascriptInterface
        public void onGenerating(boolean active) {
            runOnUiThread(() ->
                getWindow().setStatusBarColor(
                    active ? Color.parseColor("#3d2f99") : Color.parseColor("#0a0a0a")
                )
            );
        }
    }

    // ── 생명주기 ──────────────────────────────────────────────────────
    @Override
    protected void onResume()  { super.onResume();  webView.onResume(); }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
