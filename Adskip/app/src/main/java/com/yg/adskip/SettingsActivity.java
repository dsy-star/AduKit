package com.yg.adskip;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 说明页（桌面入口）。这里不做任何设置项，只讲清楚这个模块干了什么、怎么用、边界在哪。
 */
public class SettingsActivity extends Activity {

    private static final int C_BG        = 0xFFF3F5FA;
    private static final int C_CARD      = 0xFFFFFFFF;
    private static final int C_TITLE     = 0xFF151922;
    private static final int C_BODY      = 0xFF5C6472;
    private static final int C_HINT      = 0xFF9AA3B2;
    private static final int C_BLUE      = 0xFF2F6BFF;
    private static final int C_PURPLE    = 0xFF7A5CFF;
    private static final int C_GREEN     = 0xFF12B76A;
    private static final int C_GRAY      = 0xFFB6BECC;
    private static final int C_CODE_BG   = 0xFF11151D;

    private LinearLayout root;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(C_BG);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(C_BG));
            w.setNavigationBarColor(C_BG);
            // 页面是浅色底，状态栏图标用深色（避免白底白字），失败不影响运行
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController ic = w.getDecorView().getWindowInsetsController();
                    if (ic != null) {
                        ic.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    }
                } else {
                    w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            } catch (Throwable ignore) { }
        }
        setContentView(build());
    }

    // ===================== 页面骨架 =====================

    private View build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(30));

        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, 0, 0, 0);
        root.addView(header(), hlp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        body.addView(whatCard());
        body.addView(supportCard());
        body.addView(howtoCard());
        body.addView(verifyCard());
        body.addView(boundaryCard());
        body.addView(footer());

        final android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.setBackgroundColor(C_BG);
        shell.addView(sv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // 状态栏遮罩：滚到头部之外时出现，避免文字和时钟叠在一起
        final View strip = new View(this);
        strip.setBackgroundColor(0xFF2B5BFF);
        strip.setVisibility(View.INVISIBLE);
        android.widget.FrameLayout.LayoutParams stp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, statusBarHeight());
        stp.gravity = android.view.Gravity.TOP;
        shell.addView(strip, stp);
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                strip.setVisibility(y > dp(70) ? View.VISIBLE : View.INVISIBLE);
            }
        });

        return shell;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(22), statusBarHeight() + dp(18), dp(22), dp(24));
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2B5BFF, 0xFF5455FF, 0xFF7A5CFF});
        g.setCornerRadii(new float[]{0, 0, 0, 0, dp(28), dp(28), dp(28), dp(28)});
        h.setBackground(g);

        TextView badge = text("XPOSED / LSPOSED 模块", 10.5f, 0xFFFFFFFF, Typeface.BOLD);
        badge.setLetterSpacing(0.12f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(20));
        badge.setBackground(bg);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(14);
        h.addView(badge, blp);

        TextView name = text("AdSkip", 30f, 0xFFFFFFFF, Typeface.BOLD);
        h.addView(name);

        TextView sub = text("慧生活798 · 看广告领积分免播放", 13.5f, 0xE6FFFFFF, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        h.addView(sub, slp);

        TextView ver = text("版本 1.1  ·  com.yg.adskip", 11f, 0x99FFFFFF, Typeface.NORMAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(10);
        h.addView(ver, vlp);

        return h;
    }

    // ===================== 卡片 1：它能做什么 =====================

    private View whatCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("它能做什么"));
        card.addView(bodyText("点「去完成」的时候，替你把广告跳过，"
                + "再让 App 自己把加分请求发给服务器。整个过程不改安装包、不动签名。"));

        LinearLayout box = darkBox();
        box.addView(codeText("观看视频 领取积分      +30 / 次   最多 5 次/天"));
        box.addView(codeText("观看广告 获取积分      +10 / 次   最多 5 次/天"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(box, lp);

        TextView calc = text("按满额算，一天可以自动拿 30×5 + 10×5 = 200 积分", 11.5f, C_HINT, Typeface.NORMAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.addView(calc, clp);
        return card;
    }

    // ===================== 卡片 2：支持范围 =====================

    private View supportCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("支持范围"));
        card.addView(okRow("观看视频，领取积分（+30）", "已接管：不加载广告，直接提交"));
        card.addView(okRow("观看广告，获取积分（+10）", "已接管：跳转页直接关掉，走 App 自己的提交"));
        card.addView(noRow("玩赚广告 / 逛一逛 / 看资讯", "H5 计时类任务，未做接管，需要手动完成"));
        return card;
    }

    // ===================== 卡片 3：怎么用 =====================

    private View howtoCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("怎么用"));
        card.addView(step(1, "在 LSPosed 里启用本模块",
                "LSPosed 管理器 → 模块 → AdSkip → 打开「启用模块」"));
        card.addView(step(2, "勾上作用域",
                "作用域里选中「慧生活798 / com.cloudora.android」"));
        card.addView(step(3, "强行停止并重开 App",
                "只重启 App 不会生效，必须先强停进程，再重新打开"));
        card.addView(step(4, "进「积分」页点「去完成」",
                "正常点就行，广告不会出现，两三秒后会自动加分"));

        TextView tip = text("提示：模块装好后这里是唯一入口，本身没有任何开关，装好即生效。", 11.5f, C_HINT, Typeface.NORMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.addView(tip, lp);
        return card;
    }

    // ===================== 卡片 4：怎么确认生效 =====================

    private View verifyCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("怎么确认生效"));
        card.addView(bodyText("模块的日志挂在 LSPosed 的 tag 下，所以不要按 AdSkip 过滤："));
        card.addView(codeBlock("adb logcat | grep \"AdSkip:\""));
        card.addView(bodyText("看到这一行就说明注入成功："));
        card.addView(codeBlock("AdSkip: loaded in com.cloudora.android"));
        card.addView(bodyText("点一次「去完成」，应该出现："));
        card.addView(codeBlock("loadAndShow intercepted ...\n-> onAdRewardArrived(true) sent\n[submit] parm adId=... addScore=30 ...\n[ds] m1 ENTER args=[adId=...]"));
        return card;
    }

    // ===================== 卡片 5：边界 =====================

    private View boundaryCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("边界与已知情况"));
        card.addView(bullet("加分请求由 App 自己发出，模块只负责跳过广告、替它按下提交。"));
        card.addView(bullet("服务端对加分有频率限制。短时间连续点会提示「请求太过频繁」，等一会儿即可，不是模块坏了。"));
        card.addView(bullet("同一个任务每天有次数上限，跑满了会显示「明天再来」。"));
        card.addView(bullet("模块不做任何账号、设备或网络层的伪装，换账号后额度重新计算。"));
        return card;
    }

    private View footer() {
        TextView tv = text("AdSkip 1.1  ·  仅在本机使用", 11f, C_HINT, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ===================== 组件 =====================

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CARD);
        g.setCornerRadius(dp(18));
        c.setBackground(g);
        c.setElevation(dp(1.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private View sectionTitle(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BLUE, C_PURPLE});
        bg.setCornerRadius(dp(2));
        bar.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(4), dp(16));
        blp.rightMargin = dp(10);
        row.addView(bar, blp);

        TextView t = text(s, 15.5f, C_TITLE, Typeface.BOLD);
        row.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView bodyText(String s) {
        TextView t = text(s, 12.5f, C_BODY, Typeface.NORMAL);
        t.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private View okRow(String title, String desc) {
        return taskRow(title, desc, true);
    }

    private View noRow(String title, String desc) {
        return taskRow(title, desc, false);
    }

    private View taskRow(String title, String desc, boolean supported) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView mark = text(supported ? "✓" : "✕", 13f, 0xFFFFFFFF, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable mg = new GradientDrawable();
        mg.setShape(GradientDrawable.OVAL);
        mg.setColor(supported ? C_GREEN : C_GRAY);
        mark.setBackground(mg);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(22), dp(22));
        mlp.rightMargin = dp(10);
        mlp.topMargin = dp(1);
        row.addView(mark, mlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 13f, C_TITLE, Typeface.BOLD));
        TextView d = text(desc, 11.5f, C_HINT, Typeface.NORMAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        col.addView(d, dlp);
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private View step(int n, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView num = text(String.valueOf(n), 12f, 0xFFFFFFFF, Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        GradientDrawable ng = new GradientDrawable();
        ng.setShape(GradientDrawable.OVAL);
        ng.setColor(C_BLUE);
        num.setBackground(ng);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(22), dp(22));
        nlp.rightMargin = dp(10);
        nlp.topMargin = dp(1);
        row.addView(num, nlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 13f, C_TITLE, Typeface.BOLD));
        TextView d = text(desc, 11.5f, C_HINT, Typeface.NORMAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        col.addView(d, dlp);
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private View bullet(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView dot = text("·", 14f, C_BLUE, Typeface.BOLD);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView t = text(s, 12f, C_BODY, Typeface.NORMAL);
        t.setLineSpacing(dp(3), 1f);
        row.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(9);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout darkBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_CODE_BG);
        g.setCornerRadius(dp(12));
        box.setBackground(g);
        return box;
    }

    private View codeBlock(final String code) {
        LinearLayout box = darkBox();
        TextView tv = codeText(code);
        box.addView(tv);

        TextView copy = text("复制", 10.5f, 0xFF8FB2FF, Typeface.BOLD);
        copy.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0x223F7BFF);
        cg.setCornerRadius(dp(20));
        copy.setBackground(cg);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("AdSkip", code));
                    Toast.makeText(SettingsActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) { }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.gravity = Gravity.END;
        box.addView(copy, lp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(10);
        box.setLayoutParams(blp);
        return box;
    }

    private TextView codeText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTextColor(0xFFD7E2F5);
        t.setLineSpacing(dp(4), 1f);
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
