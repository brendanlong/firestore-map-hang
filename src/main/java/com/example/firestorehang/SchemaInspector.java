package com.example.firestorehang;

import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Inspect the MessageSchema buffer for Value and MapValue to understand
 * how many field entries are processed during equals().
 */
public class SchemaInspector {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Schema inspection ===");
        System.out.println();

        // Get the schema for Value via reflection
        Class<?> protobufClass = Class.forName("com.google.protobuf.Protobuf");
        Method getInstance = protobufClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object protobuf = getInstance.invoke(null);

        Method schemaFor = protobufClass.getDeclaredMethod("schemaFor", Class.class);
        schemaFor.setAccessible(true);
        Object valueSchema = schemaFor.invoke(protobuf, Value.class);

        System.out.println("Value schema class: " + valueSchema.getClass().getName());

        Field bufferField = valueSchema.getClass().getDeclaredField("buffer");
        bufferField.setAccessible(true);
        int[] buffer = (int[]) bufferField.get(valueSchema);
        System.out.println("Value buffer length: " + buffer.length + " (= " + (buffer.length / 3) + " fields)");

        for (int i = 0; i < buffer.length; i += 3) {
            int fieldNumber = buffer[i];
            int typeAndOffset = buffer[i + 1];
            int presenceOrOneofIndex = buffer[i + 2];

            System.out.printf("  buffer[%2d]: fieldNum=%2d, typeAndOffset=0x%08x, presence=0x%08x%n",
                i, fieldNumber, typeAndOffset, presenceOrOneofIndex);
        }

        // Extract the type from typeAndOffset using the same method as MessageSchema
        Method typeMethod = valueSchema.getClass().getDeclaredMethod("type", int.class);
        typeMethod.setAccessible(true);
        Method offsetMethod = valueSchema.getClass().getDeclaredMethod("offset", int.class);
        offsetMethod.setAccessible(true);

        System.out.println();
        System.out.println("Decoded field types:");
        for (int i = 0; i < buffer.length; i += 3) {
            int typeAndOffset = buffer[i + 1];
            int type = (Integer) typeMethod.invoke(null, typeAndOffset);
            long offset = (Long) offsetMethod.invoke(null, typeAndOffset);
            System.out.printf("  buffer[%2d]: fieldNum=%2d, type=%2d, offset=%d%n",
                i, buffer[i], type, offset);
        }

        System.out.println();

        // Same for MapValue
        Object mapSchema = schemaFor.invoke(protobuf, MapValue.class);
        System.out.println("MapValue schema class: " + mapSchema.getClass().getName());

        int[] mapBuffer = (int[]) bufferField.get(mapSchema);
        System.out.println("MapValue buffer length: " + mapBuffer.length + " (= " + (mapBuffer.length / 3) + " fields)");

        System.out.println("Decoded field types:");
        for (int i = 0; i < mapBuffer.length; i += 3) {
            int type = (Integer) typeMethod.invoke(null, mapBuffer[i + 1]);
            long offset = (Long) offsetMethod.invoke(null, mapBuffer[i + 1]);
            System.out.printf("  buffer[%2d]: fieldNum=%2d, type=%2d, offset=%d%n",
                i, mapBuffer[i], type, offset);
        }

        System.out.println();

        // Now compare equals vs hashCode timing
        System.out.println("=== equals() vs hashCode() timing ===");
        System.out.println("(If hashCode is fast but equals is slow, the difference");
        System.out.println(" shows overhead specific to equals path)");
        System.out.println();

        for (int depth : new int[]{1, 2, 3, 4, 5, 6, 7, 8}) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            // Warm up
            v1.equals(v2);
            v1.hashCode();

            int iters = depth <= 5 ? 100 : (depth <= 7 ? 10 : 3);
            long start = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                v1.equals(v2);
            }
            double perEqMs = (System.nanoTime() - start) / 1_000_000.0 / iters;

            start = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                v1.hashCode();
            }
            double perHashMs = (System.nanoTime() - start) / 1_000_000.0 / iters;

            System.out.printf("  depth=%d | equals=%.3f ms, hashCode=%.3f ms, ratio=%.1fx%n",
                depth, perEqMs, perHashMs, perEqMs / Math.max(perHashMs, 0.001));
        }

        System.out.println();

        // Test: Does equals on identical objects (same reference) short-circuit?
        System.out.println("=== Same reference vs equal objects ===");
        System.out.println();

        for (int depth : new int[]{6, 7, 8}) {
            Value v1 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);
            Value v2 = FirestoreMapHangRepro.buildNestedDepth(depth, 1);

            long start = System.nanoTime();
            v1.equals(v1);  // same reference
            double sameRefMs = (System.nanoTime() - start) / 1_000_000.0;

            start = System.nanoTime();
            v1.equals(v2);  // different reference, same content
            double diffRefMs = (System.nanoTime() - start) / 1_000_000.0;

            System.out.printf("  depth=%d | same ref=%.3f ms, diff ref=%.3f ms%n",
                depth, sameRefMs, diffRefMs);
        }
    }
}
