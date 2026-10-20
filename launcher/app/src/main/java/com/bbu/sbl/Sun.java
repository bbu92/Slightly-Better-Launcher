package com.bbu.sbl;

/**
 * Whether the sun is up at a place and time, for the automatic dark/light look. NOAA's low-precision solar
 * position formulas: good to a minute or two, which is plenty for switching a look.
 */
final class Sun {
    private Sun() {
    }

    /** True from sunrise to sunset (the sun's centre above -0.833 degrees: upper limb at the horizon, with refraction). */
    static boolean isDay(double latitude, double longitude, long millis) {
        double jd = millis / 86400000.0 + 2440587.5;
        double t = (jd - 2451545.0) / 36525.0;
        double l0 = Math.toRadians((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0);
        double m = Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double c = Math.toRadians((1.914602 - t * (0.004817 + 0.000014 * t)) * Math.sin(m)
                + (0.019993 - 0.000101 * t) * Math.sin(2 * m) + 0.000289 * Math.sin(3 * m));
        double trueLong = l0 + c;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double lambda = trueLong - Math.toRadians(0.00569 + 0.00478 * Math.sin(omega));
        double eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
        double eps = Math.toRadians(eps0 + 0.00256 * Math.cos(omega));
        double decl = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double y = Math.tan(eps / 2) * Math.tan(eps / 2);
        double eqTime = 4.0 * Math.toDegrees(y * Math.sin(2 * l0) - 2 * e * Math.sin(m)
                + 4 * e * y * Math.sin(m) * Math.cos(2 * l0) - 0.5 * y * y * Math.sin(4 * l0) - 1.25 * e * e * Math.sin(2 * m));
        double utcMinutes = ((millis % 86400000L) + 86400000L) % 86400000L / 60000.0;
        double solarMinutes = utcMinutes + eqTime + 4.0 * longitude;
        double hourAngle = Math.toRadians(solarMinutes / 4.0 - 180.0);
        double lat = Math.toRadians(latitude);
        double sinElevation = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(hourAngle);
        return Math.toDegrees(Math.asin(sinElevation)) > -0.833;
    }
}
