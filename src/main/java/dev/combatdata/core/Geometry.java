package dev.combatdata.core;

/** Game coordinate system: Y up, yaw = atan2(-x, z). */
public final class Geometry {
    private Geometry() {
    }

    public record V3(double x, double y, double z) {
        public V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
        public V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
        public V3 scale(double k) { return new V3(x * k, y * k, z * k); }
        public double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
        public double len() { return Math.sqrt(dot(this)); }
        public V3 norm() { double l = len(); return l < 1e-9 ? new V3(0, 0, 0) : scale(1.0 / l); }
    }

    public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public V3 center() {
            return new V3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        }

        public double nearestDistance(V3 p) {
            double dx = Math.max(Math.max(minX - p.x(), 0), p.x() - maxX);
            double dy = Math.max(Math.max(minY - p.y(), 0), p.y() - maxY);
            double dz = Math.max(Math.max(minZ - p.z(), 0), p.z() - maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Distance along the ray to the box entry point (dir must be unit length); NaN if the ray misses. */
        public double rayDistance(V3 o, V3 d) {
            double tMin = 0, tMax = Double.MAX_VALUE;
            double[] os = {o.x(), o.y(), o.z()}, ds = {d.x(), d.y(), d.z()};
            double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
            for (int i = 0; i < 3; i++) {
                if (Math.abs(ds[i]) < 1e-12) {
                    if (os[i] < lo[i] || os[i] > hi[i]) return Double.NaN;
                } else {
                    double t1 = (lo[i] - os[i]) / ds[i], t2 = (hi[i] - os[i]) / ds[i];
                    if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                    tMin = Math.max(tMin, t1);
                    tMax = Math.min(tMax, t2);
                    if (tMin > tMax) return Double.NaN;
                }
            }
            return tMin;
        }
    }

    public record Aim(double total, double yaw, double pitch) {
    }

    public static double wrap180(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    public static double yawDeg(V3 d) { return Math.toDegrees(Math.atan2(-d.x(), d.z())); }

    public static double pitchDeg(V3 d) {
        double l = d.len();
        if (l < 1e-9) return 0;
        return Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d.y() / l))));
    }

    public static double angleDeg(V3 a, V3 b) {
        double la = a.len(), lb = b.len();
        if (la < 1e-9 || lb < 1e-9) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b) / (la * lb)))));
    }

    /** Aim offset from hitbox center. yaw > 0: target is right of the crosshair; pitch > 0: target is below. */
    public static Aim aim(V3 eye, V3 look, V3 center) {
        V3 to = center.sub(eye);
        return new Aim(angleDeg(look, to), wrap180(yawDeg(to) - yawDeg(look)), pitchDeg(to) - pitchDeg(look));
    }

    /** Point on the hitbox from the attacker's view: U from -1 (left) to 1 (right), V from 0 (feet) to 1 (top). */
    public static double[] hitLocal(Aabb box, V3 point, V3 look) {
        double h = Math.hypot(look.x(), look.z());
        double u = Double.NaN;
        if (h > 1e-6) {
            double rx = -look.z() / h, rz = look.x() / h;
            V3 c = box.center();
            double half = 0.5 * (Math.abs(rx) * (box.maxX() - box.minX()) + Math.abs(rz) * (box.maxZ() - box.minZ()));
            u = half < 1e-9 ? 0 : Math.max(-1, Math.min(1, ((point.x() - c.x()) * rx + (point.z() - c.z()) * rz) / half));
        }
        double height = box.maxY() - box.minY();
        double v = height < 1e-9 ? Double.NaN : Math.max(0, Math.min(1, (point.y() - box.minY()) / height));
        return new double[]{u, v};
    }
}
