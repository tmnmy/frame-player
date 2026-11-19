package app.frameplayer.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

final class JogStrip extends View {
    interface Listener {
        void preview(double seconds);
        void finish(double seconds, boolean commit);
    }

    private static final double KNURL_STEP = Math.PI / 31;
    private static final double SECONDS_PER_DP = 0.075;
    private static final double COAST_DECAY_MS = 88;
    private static final double COAST_MAX_MS = 360;
    private static final double COAST_MAX_SPEED = 0.84;

    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bezel = new RectF();
    private final RectF well = new RectF();
    private final Path roller = new Path();
    private final Path rollerRim = new Path();
    private final Path ridges = new Path();
    private final Path pointer = new Path();
    private final float density = getResources().getDisplayMetrics().density;
    private final int touchSlop;
    private final Runnable animateCoast = this::advanceCoast;

    private LinearGradient bezelShade, rollerShade, sideShade, screwShade;
    private float rollerTop, rollerBottom, rollerSpan;
    private boolean tracking, dragging, coasting;
    private float downX, downY, lastX;
    private long lastMoveTime, coastStart;
    private double duration, position, startPosition, previewPosition;
    private double phase, startPhase, velocity, coastSpin, coastSpeed, coastElapsed;

    JogStrip(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("Fine seek");
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void update(double position, double duration) {
        this.duration = Double.isFinite(duration) && duration > 0 ? duration : 0;
        this.position = Double.isFinite(position) ? bound(position) : 0;
        if (this.duration == 0) cancelGesture();
        setEnabled(this.duration > 0);
    }

    boolean isDragging() {
        return dragging || coasting;
    }

    void cancelGesture() {
        finishGesture(false);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float halfWidth = width / 2f;
        float center = height / 2f;
        float unit = Math.min(32 * density, height) / 72f;
        float margin = Math.min(11 * unit, width / 12f);
        bezel.set(margin, center - 29 * unit, width - margin, center + 29 * unit);
        well.set(bezel);
        well.inset(3 * unit, 3 * unit);
        bezelShade = vertical(bezel.top, bezel.bottom,
                new int[]{0xff32323d, 0xff181820, 0xff0a0a11, 0xff383744},
                new float[]{0, .19f, .77f, 1});
        screwShade = vertical(center - 4 * unit, center + 4 * unit,
                new int[]{0xff62616e, 0xff20202b}, null);

        float left = width * .11f;
        float right = width - left;
        rollerSpan = right - left;
        rollerTop = center - 22 * unit;
        rollerBottom = center + 23 * unit;
        float crown = 4.8f * unit;
        float shoulder = Math.min(11 * unit, rollerSpan / 4);
        float topShoulder = rollerTop + crown;
        float bottomShoulder = rollerBottom - crown;
        float depth = rollerBottom - rollerTop;

        rollerRim.rewind();
        rollerRim.moveTo(left + shoulder, topShoulder);
        rollerRim.cubicTo(halfWidth - rollerSpan * .26f, rollerTop - crown,
                halfWidth + rollerSpan * .26f, rollerTop - crown, right - shoulder, topShoulder);
        roller.set(rollerRim);
        roller.quadTo(right, topShoulder, right, rollerTop + depth * .39f);
        roller.lineTo(right, rollerBottom - depth * .24f);
        roller.quadTo(right, bottomShoulder, right - shoulder, bottomShoulder);
        roller.cubicTo(halfWidth + rollerSpan * .22f, rollerBottom + crown,
                halfWidth - rollerSpan * .22f, rollerBottom + crown, left + shoulder, bottomShoulder);
        roller.quadTo(left, bottomShoulder, left, rollerBottom - depth * .24f);
        roller.lineTo(left, rollerTop + depth * .39f);
        roller.quadTo(left, topShoulder, left + shoulder, topShoulder);
        roller.close();

        rollerShade = vertical(rollerTop, rollerBottom,
                new int[]{0xff555a65, 0xff858a94, 0xff363943, 0xff515562, 0xff2a2c36, 0xff181921},
                new float[]{0, .10f, .22f, .52f, .78f, 1});
        sideShade = new LinearGradient(left, 0, right, 0,
                new int[]{0xe009090e, 0x40101117, 0x000c0d10, 0x16c9c5e7, 0x00f2efff, 0x20101218, 0x760d0d13, 0xe008080d},
                new float[]{0, .07f, .22f, .38f, .52f, .77f, .91f, 1}, Shader.TileMode.CLAMP);

        pointer.rewind();
        float notchTop = bezel.top + density;
        pointer.moveTo(halfWidth - 3 * density, notchTop);
        pointer.lineTo(halfWidth + 3 * density, notchTop);
        pointer.lineTo(halfWidth, notchTop + 4 * density);
        pointer.close();
    }

    private static LinearGradient vertical(float top, float bottom, int[] colors, float[] stops) {
        return new LinearGradient(0, top, 0, bottom, colors, stops, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bezel.isEmpty()) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(bezelShade);
        paint.setAlpha(255);
        canvas.drawRoundRect(bezel, 8 * density, 8 * density, paint);
        paint.setShader(null);
        paint.setColor(0xff0a0a10);
        canvas.drawRoundRect(well, 7 * density, 7 * density, paint);
        paint.setShader(screwShade);
        paint.setAlpha(255);
        float screwOffset = Math.min(5 * density, rollerSpan * .045f);
        canvas.drawCircle(well.left + screwOffset, bezel.centerY(), 1.6f * density, paint);
        canvas.drawCircle(well.right - screwOffset, bezel.centerY(), 1.6f * density, paint);

        paint.setShader(rollerShade);
        canvas.drawPath(roller, paint);
        paint.setShader(null);
        int clip = canvas.save();
        canvas.clipPath(roller);
        drawRidges(canvas);
        paint.setShader(sideShade);
        paint.setAlpha(255);
        canvas.drawRect(0, bezel.top, getWidth(), bezel.bottom, paint);
        paint.setShader(null);
        canvas.restoreToCount(clip);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.8f * density);
        paint.setColor(0xff0b0b12);
        canvas.drawPath(roller, paint);
        paint.setColor(isDragging() ? 0xa4b6a8ff : 0x84a5a8b8);
        canvas.drawPath(rollerRim, paint);
        if (isFocused()) {
            paint.setColor(0xffb7acff);
            canvas.drawRoundRect(bezel, 8 * density, 8 * density, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isEnabled() ? 0xffb2a7fc : 0xff77747f);
        canvas.drawPath(pointer, paint);
        float center = bezel.centerX();
        float markTop = bezel.top + 5.5f * density;
        float markBottom = Math.max(markTop, rollerBottom - 3 * density);
        canvas.drawRoundRect(center - .55f * density, markTop, center + .55f * density,
                markBottom, .55f * density, .55f * density, paint);
    }

    private void drawRidges(Canvas canvas) {
        float depth = rollerBottom - rollerTop;
        float ridgeTop = rollerTop + depth * .11f;
        float ridgeMiddle = rollerTop + depth * .505f;
        float ridgeBottom = rollerBottom - depth * .10f;
        double offset = phase * 2 * Math.PI % KNURL_STEP;
        ridges.rewind();
        for (int family = -1; family <= 1; family += 2) {
            for (int row = -17; row <= 17; row++) {
                double angle = row * KNURL_STEP + offset;
                for (int band = 0; band < 2; band++) {
                    double center = angle + (band - .5) * KNURL_STEP * .62;
                    double upper = center - family * KNURL_STEP * .55;
                    double lower = center + family * KNURL_STEP * .55;
                    if (Math.abs(upper) > 1.58 && Math.abs(lower) > 1.58) continue;
                    ridges.moveTo(project(upper), band == 0 ? ridgeTop : ridgeMiddle);
                    ridges.lineTo(project(lower), band == 0 ? ridgeMiddle : ridgeBottom);
                }
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xe8161820);
        paint.setStrokeWidth(.75f * density);
        canvas.drawPath(ridges, paint);
        int highlight = canvas.save();
        canvas.translate(.55f * density, 0);
        paint.setColor(0x72a6aab5);
        paint.setStrokeWidth(.42f * density);
        canvas.drawPath(ridges, paint);
        canvas.restoreToCount(highlight);
        paint.setStyle(Paint.Style.FILL);
    }

    private float project(double angle) {
        double faceAngle = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, angle));
        return bezel.centerX() + (float) Math.sin(faceAngle) * rollerSpan * .49f;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelGesture();
            return true;
        }
        if (!isEnabled()) return false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (coasting) finishGesture(true);
                tracking = true;
                downX = lastX = event.getX();
                downY = event.getY();
                lastMoveTime = event.getEventTime();
                velocity = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!tracking) return true;
                if (!dragging) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dy > touchSlop && dy >= dx) {
                        cancelGesture();
                        return true;
                    }
                    if (dx > Math.min(touchSlop, 4 * density) && dx > dy) {
                        dragging = true;
                        startPosition = previewPosition = position;
                        startPhase = phase;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging) {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        move(event.getHistoricalX(i), event.getHistoricalEventTime(i));
                    }
                    move(event.getX(), event.getEventTime());
                    publishPreview();
                }
                if (action == MotionEvent.ACTION_UP) release(event.getEventTime());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelGesture();
                return true;
            default:
                return true;
        }
    }

    private void move(float x, long time) {
        double delta = (x - lastX) / density;
        if (delta == 0) return;
        double elapsed = Math.max(8, time - lastMoveTime);
        double sample = Math.max(-3.5, Math.min(3.5, delta / elapsed));
        double weight = 1 - Math.exp(-elapsed / 19);
        velocity += (sample - velocity) * weight;
        double speed = sample * .7 + velocity * .3;
        previewPosition = bound(previewPosition + delta * gain(speed));
        phase = (phase + delta / 185) % 1;
        lastX = x;
        lastMoveTime = time;
    }

    private static double gain(double speed) {
        double effort = Math.max(0, Math.min(1, (Math.abs(speed) - .12) / 1.18));
        return SECONDS_PER_DP + 2.4 * effort * effort * (3 - 2 * effort);
    }

    private void release(long time) {
        double releaseSpeed = velocity * Math.exp(-Math.max(0, time - lastMoveTime) / 42d);
        boolean atBoundary = releaseSpeed < 0 ? previewPosition <= 0 : previewPosition >= duration;
        if (!dragging || Math.abs(releaseSpeed) < .36 || atBoundary || !ValueAnimator.areAnimatorsEnabled()) {
            finishGesture(true);
            return;
        }

        tracking = dragging = false;
        coasting = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        coastSpin = releaseSpeed / 310;
        coastSpeed = Math.max(-COAST_MAX_SPEED, Math.min(COAST_MAX_SPEED, releaseSpeed * gain(releaseSpeed) * .48));
        coastStart = SystemClock.uptimeMillis();
        coastElapsed = 0;
        postOnAnimation(animateCoast);
    }

    private void advanceCoast() {
        if (!coasting) return;
        double elapsed = Math.min(COAST_MAX_MS, Math.max(coastElapsed, SystemClock.uptimeMillis() - coastStart));
        double decay = Math.exp(-elapsed / COAST_DECAY_MS);
        double movement = COAST_DECAY_MS * (Math.exp(-coastElapsed / COAST_DECAY_MS) - decay);
        coastElapsed = elapsed;
        phase = (phase + coastSpin * movement) % 1;
        previewPosition = bound(previewPosition + coastSpeed * movement);
        publishPreview();
        if (elapsed >= COAST_MAX_MS || Math.abs(coastSpeed) * decay < .018
                || previewPosition <= 0 || previewPosition >= duration) {
            finishGesture(true);
        } else {
            postOnAnimation(animateCoast);
        }
    }

    private void publishPreview() {
        listener.preview(previewPosition);
        invalidate();
    }

    private void finishGesture(boolean commit) {
        tracking = false;
        if (!isDragging()) return;
        if (dragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (coasting) removeCallbacks(animateCoast);
        dragging = coasting = false;
        if (!commit) phase = startPhase;
        invalidate();
        listener.finish(previewPosition, commit && Math.abs(previewPosition - startPosition) >= .1);
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) cancelGesture();
    }

    @Override protected void onDetachedFromWindow() {
        cancelGesture();
        super.onDetachedFromWindow();
    }

    private double bound(double seconds) {
        return Math.max(0, Math.min(duration, seconds));
    }

    private boolean step(double seconds) {
        if (!isEnabled()) return false;
        if (coasting) finishGesture(true);
        else cancelGesture();
        double target = bound(position + seconds);
        if (target == position) return false;
        listener.finish(target, true);
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int increment = event.isShiftPressed() ? 30 : 1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && step(-increment)) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && step(increment)) return true;
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME && step(-duration)) return true;
        if (keyCode == KeyEvent.KEYCODE_MOVE_END && step(duration)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        if (!isEnabled()) return;
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, (float) duration, (float) position));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return step(-1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return step(1);
        return super.performAccessibilityAction(action, arguments);
    }
}
