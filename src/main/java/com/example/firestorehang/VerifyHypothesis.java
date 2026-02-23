package com.example.firestorehang;

import com.google.firestore.v1.Value;

/**
 * Verify the hypothesis: Value.equals() is O(6^depth) because the oneof
 * has 6 variants and equals() compares the shared storage slot for ALL
 * variants, not just the active one.
 *
 * If this is correct:
 * - The growth factor should be ~6x per depth level (number of oneof variants)
 * - A reduced proto with fewer oneof variants should have a proportionally
 *   smaller growth factor
 */
public class VerifyHypothesis {

    public static void main(String[] args) {
        System.out.println("=== Verifying hypothesis: growth factor = number of oneof variants ===");
        System.out.println();
        System.out.println("Value message has 6 oneof variants (boolean, integer, double,");
        System.out.println("string, array, map). If the hypothesis is correct, each depth");
        System.out.println("level should multiply the cost by ~6x.");
        System.out.println();

        double[] times = new double[11];

        for (int depth = 1; depth <= 10; depth++) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            // Warm up
            if (depth <= 8) v1.equals(v2);

            int iters = depth <= 5 ? 50 : (depth <= 7 ? 5 : (depth <= 8 ? 2 : 1));
            long start = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                v1.equals(v2);
            }
            double perCallMs = (System.nanoTime() - start) / 1_000_000.0 / iters;
            times[depth] = perCallMs;

            String ratio = depth > 1 ? String.format("ratio=%.1fx", perCallMs / times[depth - 1]) : "";
            System.out.printf("  depth=%2d | %10.3f ms  %s%n", depth, perCallMs, ratio);
        }

        System.out.println();
        System.out.println("Expected growth factor: ~6x (number of oneof variants in Value)");
        System.out.println();

        // Calculate average growth factor (excluding depth 1-2 due to JIT warmup)
        double sumRatio = 0;
        int count = 0;
        for (int d = 4; d <= 10; d++) {
            if (times[d] > 0 && times[d-1] > 0) {
                sumRatio += times[d] / times[d-1];
                count++;
            }
        }
        System.out.printf("Average growth factor (depth 4-10): %.1fx%n", sumRatio / count);

        System.out.println();
        System.out.println("=== Predicted time for 6^N ===");
        System.out.println();
        double baseTime = times[1];
        for (int depth = 1; depth <= 10; depth++) {
            double predicted = baseTime * Math.pow(6, depth - 1);
            System.out.printf("  depth=%2d | actual=%.3f ms, predicted(6^N)=%.3f ms%n",
                depth, times[depth], predicted);
        }
    }
}
