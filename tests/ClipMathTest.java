package com.personal.sonyvegasvideo;

public final class ClipMathTest {
    private static int checks;
    public static void main(String[] args) {
        equal("0:00", ClipMath.clock(0));
        equal("0:59", ClipMath.clock(59000));
        equal("2:05", ClipMath.clock(125000));
        equal("1:00:00", ClipMath.clock(3600000));
        equal("25:01:01", ClipMath.clock(90061000));
        equal("0:00", ClipMath.clock(-1));
        check(ClipMath.validRange(0, 1));
        check(!ClipMath.validRange(-1, 1000));
        check(!ClipMath.validRange(1000, 1000));
        check(!ClipMath.validRange(1500, 1000));
        check(ClipMath.sampleTimestamp(1300, 1000) == 300);
        check(ClipMath.sampleTimestamp(700, 1000) == 0);
        check(ClipMath.clamp(8, 0, 5) == 5);
        check(ClipMath.clamp(-1, 0, 5) == 0);
        check(ClipMath.clamp(4, 0, 5) == 4);
        check(ClipMath.progress(0, 100, 200) == 0);
        check(ClipMath.progress(150, 100, 200) == 50);
        check(ClipMath.progress(201, 100, 200) == 100);
        check(ClipMath.progress(200, 200, 200) == 0);
        check(ClipMath.progress(4_500_000_000L, 0, 9_000_000_000L) == 50);
        for (int duration = 1; duration <= 1000; duration++) {
            int start = ClipMath.clamp(500, 0, duration - 1);
            int end = ClipMath.clamp(-50, start + 1, duration);
            check(ClipMath.validRange(start, end));
            check(end <= duration);
        }
        System.out.println("ClipMathTest OK: " + checks + " checks");
    }
    private static void equal(String expected, String actual) { check(expected.equals(actual)); }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
}
