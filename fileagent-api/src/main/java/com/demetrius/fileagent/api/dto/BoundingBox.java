package com.demetrius.fileagent.api.dto;

/**
 * 来源平面上的归一化矩形，原点位于显示方向的左上角，所有值均在 0 到 1 之间。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public record BoundingBox(double x, double y, double width, double height) {

    public BoundingBox {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(width, "width");
        requireFinite(height, "height");
        if (x < 0 || x >= 1) {
            throw new IllegalArgumentException("x 必须位于 [0, 1) 范围内");
        }
        if (y < 0 || y >= 1) {
            throw new IllegalArgumentException("y 必须位于 [0, 1) 范围内");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width 和 height 必须为正数");
        }
        if (x + width > 1) {
            throw new IllegalArgumentException("矩形右边界不能超过来源平面");
        }
        if (y + height > 1) {
            throw new IllegalArgumentException("矩形下边界不能超过来源平面");
        }
    }

    /** 覆盖完整来源平面的矩形。 */
    public static BoundingBox fullPlane() {
        return new BoundingBox(0, 0, 1, 1);
    }

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限数值");
        }
    }
}
