package com.example.firestorehang;

import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;

import java.lang.reflect.Field;

/**
 * Counts how many times MessageSchema.equals loops through field entries
 * by manually walking the same path that protobuf's equals() takes,
 * counting each step.
 *
 * This helps us understand if the call count is exponential or if
 * the per-call cost is what's expensive.
 */
public class EqualsCallCounter {

    static int valueEqualsCount = 0;
    static int mapValueEqualsCount = 0;
    static int mapEntryCompareCount = 0;

    public static void main(String[] args) {
        System.out.println("=== Counting equals() calls per nesting depth ===");
        System.out.println();

        // For each depth, manually count how many Value.equals and MapValue.equals
        // calls would be made by protobuf's reflection-based equals
        for (int depth = 1; depth <= 12; depth++) {
            valueEqualsCount = 0;
            mapValueEqualsCount = 0;
            mapEntryCompareCount = 0;

            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            simulateEquals(v1, v2);

            System.out.printf("  depth=%2d | Value.equals=%d, MapValue.equals=%d, map entry compares=%d%n",
                depth, valueEqualsCount, mapValueEqualsCount, mapEntryCompareCount);
        }

        System.out.println();
        System.out.println("=== Actual timing for comparison ===");
        System.out.println();

        for (int depth = 1; depth <= 10; depth++) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            // Time the actual protobuf equals
            long start = System.nanoTime();
            v1.equals(v2);
            double ms = (System.nanoTime() - start) / 1_000_000.0;

            System.out.printf("  depth=%2d | %10.3f ms%n", depth, ms);
        }

        System.out.println();
        System.out.println("=== Serialization size check ===");
        System.out.println("(If serialization is involved in equals, size would correlate with time)");
        System.out.println();

        for (int depth = 1; depth <= 12; depth++) {
            Value v = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            int size = v.getSerializedSize();
            System.out.printf("  depth=%2d | serialized size=%d bytes%n", depth, size);
        }

        System.out.println();
        System.out.println("=== Testing: does equals use serialization? ===");
        System.out.println("Comparing: serialized bytes equals vs protobuf equals");
        System.out.println();

        for (int depth : new int[]{6, 7, 8}) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            // Force serialization first (warm cache)
            byte[] b1 = v1.toByteArray();
            byte[] b2 = v2.toByteArray();

            // Time serialized comparison
            long start = System.nanoTime();
            boolean eqBytes = java.util.Arrays.equals(b1, b2);
            double msSerialized = (System.nanoTime() - start) / 1_000_000.0;

            // Time protobuf equals (after serialization has been done)
            start = System.nanoTime();
            boolean eqProto = v1.equals(v2);
            double msProto = (System.nanoTime() - start) / 1_000_000.0;

            System.out.printf("  depth=%2d | serialized compare=%.3f ms, protobuf equals=%.3f ms%n",
                depth, msSerialized, msProto);
        }

        // Check if equals is doing repeated serialization
        System.out.println();
        System.out.println("=== Testing: second equals call (cached?) ===");
        System.out.println();

        for (int depth : new int[]{6, 7, 8}) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            // First call
            long start = System.nanoTime();
            v1.equals(v2);
            double ms1 = (System.nanoTime() - start) / 1_000_000.0;

            // Second call (same objects)
            start = System.nanoTime();
            v1.equals(v2);
            double ms2 = (System.nanoTime() - start) / 1_000_000.0;

            // Third call
            start = System.nanoTime();
            v1.equals(v2);
            double ms3 = (System.nanoTime() - start) / 1_000_000.0;

            System.out.printf("  depth=%2d | 1st=%.3f ms, 2nd=%.3f ms, 3rd=%.3f ms%n",
                depth, ms1, ms2, ms3);
        }
    }

    /** Simulate the recursive equals path that protobuf takes */
    static boolean simulateEquals(Value v1, Value v2) {
        valueEqualsCount++;

        // Value has a oneof field. MessageSchema.equals loops through the buffer,
        // which for Value contains entries for each oneof variant.
        // For the active variant, it calls SchemaUtil.safeEquals on the field value.

        if (v1.getValueTypeCase() != v2.getValueTypeCase()) return false;

        switch (v1.getValueTypeCase()) {
            case MAP_VALUE:
                return simulateMapValueEquals(v1.getMapValue(), v2.getMapValue());
            case STRING_VALUE:
                return v1.getStringValue().equals(v2.getStringValue());
            case DOUBLE_VALUE:
                return v1.getDoubleValue() == v2.getDoubleValue();
            case BOOLEAN_VALUE:
                return v1.getBooleanValue() == v2.getBooleanValue();
            case INTEGER_VALUE:
                return v1.getIntegerValue() == v2.getIntegerValue();
            case ARRAY_VALUE:
                var list1 = v1.getArrayValue().getValuesList();
                var list2 = v2.getArrayValue().getValuesList();
                if (list1.size() != list2.size()) return false;
                for (int i = 0; i < list1.size(); i++) {
                    if (!simulateEquals(list1.get(i), list2.get(i))) return false;
                }
                return true;
            default:
                return true;
        }
    }

    static boolean simulateMapValueEquals(MapValue m1, MapValue m2) {
        mapValueEqualsCount++;

        // MapValue has one field: map<string, Value> fields = 1;
        // The generated MapValue stores this as a MapFieldLite internally.
        // MapFieldLite.equals() iterates all entries and calls Object.equals on each value.
        var map1 = m1.getFieldsMap();
        var map2 = m2.getFieldsMap();

        if (map1.size() != map2.size()) return false;

        for (var entry : map1.entrySet()) {
            mapEntryCompareCount++;
            Value val2 = map2.get(entry.getKey());
            if (val2 == null) return false;
            // This calls Value.equals which recurses
            simulateEquals(entry.getValue(), val2);
        }
        return true;
    }
}
