package com.example.firestorehang;

import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;

/**
 * Reproduction of the Firestore SDK hang caused by protobuf-javalite's
 * Value.equals() being extremely slow on deeply nested Map structures.
 *
 * Background:
 *   Firebase Firestore Android SDK (firebase-firestore 26.1.0, BOM 34.9.0)
 *   uses protobuf-javalite 3.25.5. After every remote event (including local
 *   write acknowledgements), View.computeDocChanges() calls:
 *
 *       boolean docsEqual = oldDoc.getData().equals(newDoc.getData());
 *
 *   ObjectValue.equals() delegates to buildProto().equals(), which invokes
 *   protobuf-javalite's native MessageLite.equals(). For messages containing
 *   map<string, Value> fields (like Firestore's MapValue), the generated
 *   equals() implementation has pathological performance -- it appears to be
 *   worse than O(N) in the number of nested fields.
 *
 *   PR #1920 (https://github.com/firebase/firebase-android-sdk/pull/1920)
 *   fixed Values.java to use a custom equals() for recursive map comparison,
 *   but ObjectValue.equals() still calls buildProto().equals() directly,
 *   bypassing the fix entirely. View.computeDocChanges() calls
 *   ObjectValue.equals(), so the slow path is still hit.
 *
 * ROOT CAUSE (identified by this reproduction):
 *   protobuf-javalite's MessageSchema.equals() iterates ALL oneof field
 *   entries in its schema buffer, not just the active variant. The Firestore
 *   Value message has 6 oneof variants (boolean, integer, double, string,
 *   array, map). All variants share a single storage slot (offset 28).
 *
 *   For each variant, equals() checks isOneofCaseEqual() -- which compares
 *   the case discriminators. Since both messages have the same active variant,
 *   the cases are always equal, so equals() proceeds to call safeEquals() on
 *   the object stored at the shared offset. This means the nested MapValue is
 *   compared 6 TIMES instead of once at each level.
 *
 *   At each level of nesting, this 6x multiplication compounds:
 *     depth=1: 6^0 = 1 comparison
 *     depth=2: 6^1 = 6 comparisons
 *     depth=N: 6^(N-1) comparisons
 *
 *   This gives O(V^N) complexity where V = number of oneof variants (6)
 *   and N = nesting depth.
 *
 *   A typical recipe document with ~7 levels of map nesting takes ~6 seconds
 *   to compare. Since Firestore's AsyncQueue is single-threaded, this blocks
 *   ALL Firestore operations (reads, writes, listeners) for the duration.
 *
 * This reproduction uses minimal synthetic structures to isolate what
 * triggers the pathological equals() performance:
 *   - Pure depth: {a: {a: {a: ...}}} (nested maps, 1 field each)
 *   - Pure width: {a: "x", b: "x", c: "x", ...} (flat map, N fields)
 *   - Array of maps: [{a:"x"}, {a:"x"}, ...] (N single-field maps)
 *   - Depth x width: depth D of maps with W fields each
 */
public class FirestoreMapHangRepro {

    public static void main(String[] args) {
        System.out.println("=== Firestore protobuf-javalite equals() hang reproduction ===");
        System.out.println("protobuf-javalite version: 3.25.5 (same as Firebase BOM 34.9.0)");
        System.out.println();

        // ---- Test 1: Pure depth (single field per level) ----
        System.out.println("--- Test 1: Pure depth -- {a: {a: {a: ...}}} ---");
        System.out.println("(Single field per map level)");
        System.out.println();

        double[] depthTimes = new double[11];
        for (int depth : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            Value v = buildNestedDepth(depth, 1);
            Value v2 = buildNestedDepth(depth, 1);

            long start = System.nanoTime();
            boolean eq = v.equals(v2);
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            depthTimes[depth] = ms;

            if (!eq) throw new AssertionError();
            String ratio = depth > 1 ? String.format(" (%.1fx prev)", ms / depthTimes[depth - 1]) : "";
            System.out.printf("  depth=%2d | %10.3f ms%s%s%n", depth, ms, ratio, severity(ms));
        }

        // ---- Test 2: Pure width (flat map, N string fields, no nesting) ----
        System.out.println();
        System.out.println("--- Test 2: Pure width -- {a:\"x\", b:\"x\", ...} ---");
        System.out.println("(Single flat map with N string fields, depth=1)");
        System.out.println();

        for (int width : new int[]{1, 5, 10, 20, 50, 100, 200, 500, 1000}) {
            Value v = buildWideMap(width);
            Value v2 = buildWideMap(width);

            long start = System.nanoTime();
            boolean eq = v.equals(v2);
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            if (!eq) throw new AssertionError();
            System.out.printf("  width=%4d | %10.3f ms%s%n", width, ms, severity(ms));
        }

        // ---- Test 3: Array of single-field maps ----
        System.out.println();
        System.out.println("--- Test 3: Array of maps -- [{a:\"x\"}, {a:\"x\"}, ...] ---");
        System.out.println("(N maps in an array, each with 1 string field, depth=2)");
        System.out.println();

        for (int count : new int[]{1, 5, 10, 20, 50, 100, 200, 500}) {
            Value v = buildArrayOfMaps(count, 1);
            Value v2 = buildArrayOfMaps(count, 1);

            long start = System.nanoTime();
            boolean eq = v.equals(v2);
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            if (!eq) throw new AssertionError();
            System.out.printf("  count=%3d | %10.3f ms%s%n", count, ms, severity(ms));
        }

        // ---- Test 4: Depth x width ----
        System.out.println();
        System.out.println("--- Test 4: Depth x width -- depth D of maps with W string fields each ---");
        System.out.println();

        int[][] depthWidthConfigs = {
            {2, 1}, {2, 2}, {2, 5}, {2, 10}, {2, 20},
            {3, 1}, {3, 2}, {3, 5}, {3, 10},
            {4, 1}, {4, 2}, {4, 5},
            {5, 1}, {5, 2}, {5, 5},
            {6, 1}, {6, 2},
            {7, 1}, {7, 2},
            {8, 1},
        };

        for (int[] cfg : depthWidthConfigs) {
            int depth = cfg[0], width = cfg[1];
            Value v = buildNestedDepth(depth, width);
            Value v2 = buildNestedDepth(depth, width);

            long start = System.nanoTime();
            boolean eq = v.equals(v2);
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            if (!eq) throw new AssertionError();
            System.out.printf("  depth=%2d width=%2d | %10.3f ms%s%n", depth, width, ms, severity(ms));
        }

        // ---- Test 5: Real-world recipe (for reference) ----
        System.out.println();
        System.out.println("--- Test 5: Real-world recipe structure (1 section, 1 step, 1 ingredient) ---");
        System.out.println();

        Value recipe = buildMinimalRecipe();
        Value recipe2 = buildMinimalRecipe();
        int fields = countFields(recipe);
        int maps = countMapValues(recipe);
        int maxDepth = maxDepth(recipe, 0);

        long start = System.nanoTime();
        recipe.equals(recipe2);
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        System.out.printf("  fields=%d, MapValue instances=%d, max depth=%d | %10.1f ms%s%n",
            fields, maps, maxDepth, ms, severity(ms));

        // ---- Root cause explanation ----
        System.out.println();
        System.out.println("=== Root cause ===");
        System.out.println();
        System.out.println("protobuf-javalite MessageSchema.equals() iterates ALL oneof variants");
        System.out.println("in the schema buffer, not just the active one. The Firestore Value");
        System.out.println("message has 6 oneof variants sharing a single storage slot.");
        System.out.println();
        System.out.println("For each variant, isOneofCaseEqual() returns true (both messages have");
        System.out.println("the same active case), so safeEquals() is called on the SAME object");
        System.out.println("6 times. At each nesting level, this 6x multiplication compounds:");
        System.out.println();
        System.out.printf("  Growth factor per depth level: %.1fx (= number of oneof variants)%n",
            depthTimes[9] > 0 && depthTimes[8] > 0 ? depthTimes[9] / depthTimes[8] : 6.0);
        System.out.printf("  Complexity: O(6^N) where N = nesting depth%n");
        System.out.println();
        System.out.println("Firestore SDK call path:");
        System.out.println("  View.computeDocChanges()");
        System.out.println("    -> ObjectValue.equals()");
        System.out.println("      -> buildProto().equals()  // bypasses PR #1920 fix");
        System.out.println("        -> GeneratedMessageLite.equals()");
        System.out.println("          -> MessageSchema.equals() loops 6 oneof variants");
        System.out.println("            -> safeEquals() on shared storage slot (6x per level)");
    }

    /** Build nested maps: depth levels, each with `width` string fields plus one nested child. */
    static Value buildNestedDepth(int depth, int width) {
        // Base case: innermost map has only string fields
        MapValue.Builder inner = MapValue.newBuilder();
        for (int i = 0; i < width; i++) {
            inner.putFields("f" + i, stringValue("v"));
        }
        Value current = Value.newBuilder().setMapValue(inner).build();

        // Wrap in depth-1 more levels
        for (int d = 1; d < depth; d++) {
            MapValue.Builder outer = MapValue.newBuilder();
            for (int i = 0; i < width; i++) {
                outer.putFields("f" + i, stringValue("v"));
            }
            outer.putFields("child", current);
            current = Value.newBuilder().setMapValue(outer).build();
        }

        // Wrap in root document map
        MapValue.Builder root = MapValue.newBuilder();
        root.putFields("data", current);
        return Value.newBuilder().setMapValue(root).build();
    }

    /** Build a flat map with N string fields. */
    static Value buildWideMap(int width) {
        MapValue.Builder map = MapValue.newBuilder();
        for (int i = 0; i < width; i++) {
            map.putFields("field_" + i, stringValue("value_" + i));
        }
        return Value.newBuilder().setMapValue(map).build();
    }

    /** Build an array of N maps, each with `fieldsPerMap` string fields. */
    static Value buildArrayOfMaps(int count, int fieldsPerMap) {
        ArrayValue.Builder arr = ArrayValue.newBuilder();
        for (int i = 0; i < count; i++) {
            MapValue.Builder map = MapValue.newBuilder();
            for (int f = 0; f < fieldsPerMap; f++) {
                map.putFields("f" + f, stringValue("v"));
            }
            arr.addValues(Value.newBuilder().setMapValue(map).build());
        }
        MapValue.Builder root = MapValue.newBuilder();
        root.putFields("items", Value.newBuilder().setArrayValue(arr).build());
        return Value.newBuilder().setMapValue(root).build();
    }

    /** Minimal recipe: 1 section, 1 step, 1 ingredient (with alternates). */
    static Value buildMinimalRecipe() {
        MapValue.Builder doc = MapValue.newBuilder();
        doc.putFields("name", stringValue("Test Recipe"));

        // ingredient with amount and alternates
        MapValue.Builder altAmount = MapValue.newBuilder();
        altAmount.putFields("value", Value.newBuilder().setDoubleValue(3.0).build());
        altAmount.putFields("unit", stringValue("tbsp"));

        MapValue.Builder alt = MapValue.newBuilder();
        alt.putFields("name", stringValue("Alt"));
        alt.putFields("amount", Value.newBuilder().setMapValue(altAmount).build());

        MapValue.Builder amount = MapValue.newBuilder();
        amount.putFields("value", Value.newBuilder().setDoubleValue(2.5).build());
        amount.putFields("unit", stringValue("cups"));

        MapValue.Builder ingredient = MapValue.newBuilder();
        ingredient.putFields("name", stringValue("Flour"));
        ingredient.putFields("amount", Value.newBuilder().setMapValue(amount).build());
        ingredient.putFields("alternates", Value.newBuilder().setArrayValue(
            ArrayValue.newBuilder().addValues(Value.newBuilder().setMapValue(alt).build())
        ).build());

        MapValue.Builder step = MapValue.newBuilder();
        step.putFields("instruction", stringValue("Mix"));
        step.putFields("ingredients", Value.newBuilder().setArrayValue(
            ArrayValue.newBuilder().addValues(Value.newBuilder().setMapValue(ingredient).build())
        ).build());

        MapValue.Builder section = MapValue.newBuilder();
        section.putFields("steps", Value.newBuilder().setArrayValue(
            ArrayValue.newBuilder().addValues(Value.newBuilder().setMapValue(step).build())
        ).build());

        doc.putFields("instructionSections", Value.newBuilder().setArrayValue(
            ArrayValue.newBuilder().addValues(Value.newBuilder().setMapValue(section).build())
        ).build());

        return Value.newBuilder().setMapValue(doc).build();
    }

    static String severity(double ms) {
        if (ms > 10_000) return " *** HANG (>10s) ***";
        if (ms > 1_000) return " *** VERY SLOW (>1s) ***";
        if (ms > 100) return " * slow (>100ms) *";
        if (ms > 16) return " * noticeable (>16ms) *";
        return "";
    }

    static int countFields(Value value) {
        switch (value.getValueTypeCase()) {
            case MAP_VALUE:
                int count = 0;
                for (var entry : value.getMapValue().getFieldsMap().entrySet()) {
                    count += 1 + countFields(entry.getValue());
                }
                return count;
            case ARRAY_VALUE:
                int arrayCount = 0;
                for (Value v : value.getArrayValue().getValuesList()) {
                    arrayCount += countFields(v);
                }
                return arrayCount;
            default:
                return 1;
        }
    }

    static int countMapValues(Value value) {
        switch (value.getValueTypeCase()) {
            case MAP_VALUE:
                int count = 1;
                for (Value v : value.getMapValue().getFieldsMap().values()) {
                    count += countMapValues(v);
                }
                return count;
            case ARRAY_VALUE:
                int arrayCount = 0;
                for (Value v : value.getArrayValue().getValuesList()) {
                    arrayCount += countMapValues(v);
                }
                return arrayCount;
            default:
                return 0;
        }
    }

    static int maxDepth(Value value, int currentDepth) {
        switch (value.getValueTypeCase()) {
            case MAP_VALUE:
                int max = currentDepth + 1;
                for (Value v : value.getMapValue().getFieldsMap().values()) {
                    max = Math.max(max, maxDepth(v, currentDepth + 1));
                }
                return max;
            case ARRAY_VALUE:
                int arrayMax = currentDepth;
                for (Value v : value.getArrayValue().getValuesList()) {
                    arrayMax = Math.max(arrayMax, maxDepth(v, currentDepth));
                }
                return arrayMax;
            default:
                return currentDepth;
        }
    }

    static Value stringValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    static Value intValue(long v) {
        return Value.newBuilder().setIntegerValue(v).build();
    }

    static Value boolValue(boolean b) {
        return Value.newBuilder().setBooleanValue(b).build();
    }
}
