package jp.mk.batteryhub;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 残量リングゲージのビットマップ生成ユーティリティ。
 * 常駐通知（RemoteViews）とアプリ内一覧の両方で使う。
 *
 * v1.2: 背景の明暗に合わせた2パレット制にした。
 * v1.1 までは明背景前提の濃いグレー（#616161）で描いており、ダークな
 * 通知シェード上では円弧も中央数値もほぼ見えなかった（実機スクショで確認）。
 * - 明背景: トラック #B0B0B0 / 通常弧 #37474F / 数値 #212121
 * - 暗背景: トラック #5F6368 / 通常弧 #E8EAED / 数値 #FFFFFF
 * 低残量色も背景ごとに視認性の高い明度へ調整する。
 * 色分けの段階自体は従来どおり最小限: 15%未満=赤 / 20%以下=橙 / それ以外=単色。
 */
public final class RingGauge {

    /** 生成ビットマップの一辺（px）。通知の 56dp 表示でも耐える大きさ */
    private static final int SIZE_PX = 224;

    // 明背景パレット
    private static final int TRACK_LIGHT = 0xFFB0B0B0;
    private static final int NORMAL_LIGHT = 0xFF37474F;
    private static final int TEXT_LIGHT = 0xFF212121;
    private static final int LOW_LIGHT = 0xFFE65100;      // 濃い橙
    private static final int CRITICAL_LIGHT = 0xFFC62828; // 赤

    // 暗背景パレット
    private static final int TRACK_DARK = 0xFF5F6368;
    private static final int NORMAL_DARK = 0xFFE8EAED;
    private static final int TEXT_DARK = 0xFFFFFFFF;
    private static final int LOW_DARK = 0xFFFFB74D;      // 明るい橙
    private static final int CRITICAL_DARK = 0xFFFF5252; // 明るい赤

    private RingGauge() {
    }

    /** 端末のナイトモード（ダークテーマ）かどうか。通知シェードの明暗と概ね一致する。 */
    public static boolean isDarkBackground(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 残量に対するリング色（明背景）。警告閾値（BatteryService側の定数）と揃えている。 */
    public static int colorForLevel(int level) {
        return colorForLevel(level, false);
    }

    public static int colorForLevel(int level, boolean darkBg) {
        if (level < BatteryService.ALERT_THRESHOLD) {
            return darkBg ? CRITICAL_DARK : CRITICAL_LIGHT; // 15%未満
        }
        if (level <= BatteryService.ALERT_REARM) {
            return darkBg ? LOW_DARK : LOW_LIGHT;           // 20%以下
        }
        return darkBg ? NORMAL_DARK : NORMAL_LIGHT;
    }

    /** 中央数値の色。低残量時は弧と同系色、それ以外は背景に対する最大コントラスト色。 */
    private static int textColorForLevel(int level, boolean darkBg) {
        if (level < BatteryService.ALERT_THRESHOLD) {
            return darkBg ? 0xFFFF8A80 : CRITICAL_LIGHT;
        }
        if (level <= BatteryService.ALERT_REARM) {
            return darkBg ? LOW_DARK : LOW_LIGHT;
        }
        return darkBg ? TEXT_DARK : TEXT_LIGHT;
    }

    /** 従来シグネチャ（明背景）。 */
    public static Bitmap render(int level, boolean stale) {
        return render(level, stale, false);
    }

    /**
     * リングゲージのビットマップを生成する。
     *
     * @param level  残量 0〜100
     * @param stale  応答なし表示にする場合 true
     * @param darkBg 暗い背景の上に置く場合 true
     */
    public static Bitmap render(int level, boolean stale, boolean darkBg) {
        int size = SIZE_PX;
        int lv = Math.max(0, Math.min(100, level));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        float stroke = size * 0.11f;
        float inset = stroke / 2f + 2f;
        RectF oval = new RectF(inset, inset, size - inset, size - inset);
        int alpha = stale ? 120 : 255;
        int arcColor = stale
                ? (darkBg ? 0xFF9E9E9E : NORMAL_LIGHT)
                : colorForLevel(lv, darkBg);
        int trackColor = darkBg ? TRACK_DARK : TRACK_LIGHT;

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(trackColor);
        track.setAlpha(alpha);
        canvas.drawArc(oval, 0, 360, false, track);

        float sweep = lv * 3.6f;
        if (sweep > 0) {
            Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(arcColor);
            arc.setAlpha(alpha);
            canvas.drawArc(oval, -90, sweep, false, arc);
        }

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(stale
                ? (darkBg ? 0xFFBDBDBD : 0xFF616161)
                : textColorForLevel(lv, darkBg));
        text.setAlpha(alpha);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(size * (lv >= 100 ? 0.32f : 0.38f));
        float y = size / 2f - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(String.valueOf(lv), size / 2f, y, text);

        return bmp;
    }
}
