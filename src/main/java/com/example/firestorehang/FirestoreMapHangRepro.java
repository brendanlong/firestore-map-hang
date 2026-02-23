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
 * This reproduction builds protobuf Value objects matching the document
 * structure that triggered the hang:
 *
 *   Recipe document with instructionSections containing:
 *     sections -> steps -> ingredients -> alternates -> amounts
 *   (5+ levels of nested MapValue)
 *
 * It then calls Value.equals() and measures the time, demonstrating that
 * even small documents with nested maps cause multi-second equality checks.
 */
public class FirestoreMapHangRepro {

    public static void main(String[] args) {
        System.out.println("=== Firestore protobuf-javalite equals() hang reproduction ===");
        System.out.println("protobuf-javalite version: 3.25.5 (same as Firebase BOM 34.9.0)");
        System.out.println();

        // ---- Part 1: Show scaling behavior with increasing document size ----
        System.out.println("--- Part 1: Scaling behavior of Value.equals() on nested maps ---");
        System.out.println();

        // Use small sizes since even tiny documents are extremely slow.
        // NOTE: larger configs (e.g. 3 sections, 5 steps) would take 10+ minutes
        // to complete a SINGLE equals() call, so we keep sizes small here.
        int[][] configs = {
            // {sections, stepsPerSection, ingredientsPerStep}
            {1, 1, 1},   // minimal recipe
            {1, 2, 1},   // 2 steps
            {1, 2, 2},   // 2 steps, 2 ingredients each
            {1, 3, 2},   // 3 steps, 2 ingredients each (small real recipe)
            {2, 2, 2},   // 2 sections
            {2, 3, 2},   // 2 sections, 3 steps (medium recipe)
        };

        for (int[] cfg : configs) {
            int sc = cfg[0], sps = cfg[1], ips = cfg[2];
            Value recipe = buildRecipeDocument(sc, sps, ips);
            Value recipeCopy = buildRecipeDocument(sc, sps, ips);
            int totalFields = countFields(recipe);

            // Single measurement (no warmup -- this reflects the real-world scenario
            // on the Firestore worker thread, which does one equals() per document change)
            long start = System.nanoTime();
            boolean eq = recipe.equals(recipeCopy);
            long elapsedNs = System.nanoTime() - start;
            double elapsedMs = elapsedNs / 1_000_000.0;

            if (!eq) {
                throw new AssertionError("Documents should be equal");
            }

            String severity;
            if (elapsedMs > 10_000) severity = " *** HANG (>10s) ***";
            else if (elapsedMs > 1_000) severity = " *** VERY SLOW (>1s) ***";
            else if (elapsedMs > 100) severity = " * slow (>100ms) *";
            else if (elapsedMs > 16) severity = " * noticeable (>16ms) *";
            else severity = "";

            System.out.printf(
                "sections=%d steps=%d ingredients=%d | fields=%4d | equals(): %10.1f ms%s%n",
                sc, sps, ips, totalFields, elapsedMs, severity
            );
        }

        // ---- Part 1b: Same configs but ONLY the nested map field (no flat fields) ----
        System.out.println();
        System.out.println("--- Part 1b: Map-only document (just the instructionSections field) ---");
        System.out.println();

        for (int[] cfg : configs) {
            int sc = cfg[0], sps = cfg[1], ips = cfg[2];
            Value mapOnly = buildMapOnlyDocument(sc, sps, ips);
            Value mapOnlyCopy = buildMapOnlyDocument(sc, sps, ips);
            int totalFields = countFields(mapOnly);

            long mapStart = System.nanoTime();
            boolean eq = mapOnly.equals(mapOnlyCopy);
            long elapsedNs = System.nanoTime() - mapStart;
            double elapsedMs = elapsedNs / 1_000_000.0;

            if (!eq) {
                throw new AssertionError("Documents should be equal");
            }

            String severity;
            if (elapsedMs > 10_000) severity = " *** HANG (>10s) ***";
            else if (elapsedMs > 1_000) severity = " *** VERY SLOW (>1s) ***";
            else if (elapsedMs > 100) severity = " * slow (>100ms) *";
            else if (elapsedMs > 16) severity = " * noticeable (>16ms) *";
            else severity = "";

            System.out.printf(
                "sections=%d steps=%d ingredients=%d | fields=%4d | equals(): %10.1f ms%s%n",
                sc, sps, ips, totalFields, elapsedMs, severity
            );
        }

        // ---- Part 2: Flat fields only (baseline) ----
        System.out.println();
        System.out.println("--- Part 2: Baseline -- flat fields only (no nested maps) ---");

        Value flatOnly = buildFlatFieldsOnly();
        Value flatOnlyCopy = buildFlatFieldsOnly();
        int flatOnlyFields = countFields(flatOnly);

        long start = System.nanoTime();
        flatOnly.equals(flatOnlyCopy);
        double flatOnlyMs = (System.nanoTime() - start) / 1_000_000.0;

        System.out.printf("  14 flat scalar fields: %.3f ms (%d fields)%n", flatOnlyMs, flatOnlyFields);
        System.out.println();

        // ---- Part 3: JSON string workaround comparison ----
        System.out.println("--- Part 3: JSON string workaround comparison ---");
        System.out.println("(Using 1 section, 1 step, 1 ingredient -- the smallest possible recipe)");

        Value nested = buildRecipeDocument(1, 1, 1);
        Value nestedCopy = buildRecipeDocument(1, 1, 1);
        int nestedFields = countFields(nested);

        Value flat = buildFlatRecipeDocument(1, 1, 1);
        Value flatCopy = buildFlatRecipeDocument(1, 1, 1);
        int flatFields = countFields(flat);

        start = System.nanoTime();
        nested.equals(nestedCopy);
        double nestedMs = (System.nanoTime() - start) / 1_000_000.0;

        start = System.nanoTime();
        flat.equals(flatCopy);
        double flatMs = (System.nanoTime() - start) / 1_000_000.0;

        System.out.printf("  Nested maps:         %10.1f ms (%d fields)%n", nestedMs, nestedFields);
        System.out.printf("  JSON string:         %10.3f ms (%d fields)%n", flatMs, flatFields);
        if (flatMs > 0) {
            System.out.printf("  Speedup:             %,.0fx%n", nestedMs / flatMs);
        }

        // ---- Part 4: Impact summary ----
        System.out.println();
        System.out.println("=== Impact on Firestore SDK ===");
        System.out.println();
        System.out.println("View.computeDocChanges() calls this equals() on every document change.");
        System.out.println("The Firestore SDK serializes ALL operations through a single AsyncQueue");
        System.out.println("worker thread (FirestoreWorker). During the equals() call, ALL Firestore");
        System.out.println("operations are blocked: reads, writes, and snapshot listeners.");
        System.out.println();
        System.out.println("With snapshot listeners, equals() is called on both the local write");
        System.out.println("(latency compensation) AND the server acknowledgement, so the total");
        System.out.println("block time per mutation is 2x the single equals() time.");
        System.out.println();
        System.out.println("=== Root cause in Firestore SDK code ===");
        System.out.println();
        System.out.println("View.java line ~161:");
        System.out.println("  boolean docsEqual = oldDoc.getData().equals(newDoc.getData());");
        System.out.println();
        System.out.println("ObjectValue.java line ~265:");
        System.out.println("  return buildProto().equals(((ObjectValue) o).buildProto());");
        System.out.println();
        System.out.println("This calls protobuf-javalite's generated equals(), which uses");
        System.out.println("reflection-based deep comparison (MessageSchema.equals() and");
        System.out.println("MapFieldLite.equals()). The performance is pathological for");
        System.out.println("messages containing map<string, Value> fields.");
        System.out.println();
        System.out.println("PR #1920 fixed Values.java to use a custom equals() that avoids");
        System.out.println("protobuf's native equals(), but ObjectValue.equals() still calls");
        System.out.println("buildProto().equals() directly, bypassing the Values.java fix.");
    }

    /**
     * Build a protobuf Value representing a recipe document with deeply nested
     * instructionSections, matching the structure that caused the Firestore hang.
     */
    static Value buildRecipeDocument(int numSections, int stepsPerSection, int ingredientsPerStep) {
        MapValue.Builder doc = MapValue.newBuilder();

        // Flat scalar fields
        doc.putFields("name", stringValue("Grandma's Famous Chocolate Chip Cookies"));
        doc.putFields("sourceUrl", stringValue("https://example.com/recipe/123"));
        doc.putFields("story", stringValue("This recipe has been in our family for generations..."));
        doc.putFields("imageUrl", stringValue("https://example.com/images/cookies.jpg"));
        doc.putFields("sourceImageUrl", stringValue("https://example.com/images/cookies-original.jpg"));
        doc.putFields("servings", intValue(24));
        doc.putFields("prepTime", stringValue("PT20M"));
        doc.putFields("cookTime", stringValue("PT12M"));
        doc.putFields("totalTime", stringValue("PT32M"));
        doc.putFields("isFavorite", boolValue(true));
        doc.putFields("createdAt", intValue(1708000000));
        doc.putFields("updatedAt", intValue(1708100000));
        doc.putFields("tags", arrayOfStrings("dessert", "cookies", "baking", "chocolate"));
        doc.putFields("equipment", arrayOfStrings("mixing bowl", "baking sheet", "wire rack", "stand mixer"));

        // instructionSections -- the deeply nested field that causes the hang
        ArrayValue.Builder sections = ArrayValue.newBuilder();
        for (int s = 0; s < numSections; s++) {
            sections.addValues(Value.newBuilder()
                .setMapValue(buildInstructionSection("Section " + (s + 1), stepsPerSection, ingredientsPerStep))
                .build());
        }
        doc.putFields("instructionSections", Value.newBuilder().setArrayValue(sections).build());

        return Value.newBuilder().setMapValue(doc).build();
    }

    /** Build a document with ONLY the instructionSections field (no flat scalar fields). */
    static Value buildMapOnlyDocument(int numSections, int stepsPerSection, int ingredientsPerStep) {
        MapValue.Builder doc = MapValue.newBuilder();
        ArrayValue.Builder sections = ArrayValue.newBuilder();
        for (int s = 0; s < numSections; s++) {
            sections.addValues(Value.newBuilder()
                .setMapValue(buildInstructionSection("Section " + (s + 1), stepsPerSection, ingredientsPerStep))
                .build());
        }
        doc.putFields("instructionSections", Value.newBuilder().setArrayValue(sections).build());
        return Value.newBuilder().setMapValue(doc).build();
    }

    /** Build a document with only flat scalar fields (no nested maps). */
    static Value buildFlatFieldsOnly() {
        MapValue.Builder doc = MapValue.newBuilder();
        doc.putFields("name", stringValue("Grandma's Famous Chocolate Chip Cookies"));
        doc.putFields("sourceUrl", stringValue("https://example.com/recipe/123"));
        doc.putFields("story", stringValue("This recipe has been in our family for generations..."));
        doc.putFields("imageUrl", stringValue("https://example.com/images/cookies.jpg"));
        doc.putFields("sourceImageUrl", stringValue("https://example.com/images/cookies-original.jpg"));
        doc.putFields("servings", intValue(24));
        doc.putFields("prepTime", stringValue("PT20M"));
        doc.putFields("cookTime", stringValue("PT12M"));
        doc.putFields("totalTime", stringValue("PT32M"));
        doc.putFields("isFavorite", boolValue(true));
        doc.putFields("createdAt", intValue(1708000000));
        doc.putFields("updatedAt", intValue(1708100000));
        doc.putFields("tags", arrayOfStrings("dessert", "cookies", "baking", "chocolate"));
        doc.putFields("equipment", arrayOfStrings("mixing bowl", "baking sheet", "wire rack", "stand mixer"));
        return Value.newBuilder().setMapValue(doc).build();
    }

    /**
     * Build a "flat" recipe where instructionSections is stored as a JSON string
     * instead of nested maps. This is the workaround.
     */
    static Value buildFlatRecipeDocument(int numSections, int stepsPerSection, int ingredientsPerStep) {
        MapValue.Builder doc = MapValue.newBuilder();
        doc.putFields("name", stringValue("Grandma's Famous Chocolate Chip Cookies"));
        doc.putFields("sourceUrl", stringValue("https://example.com/recipe/123"));
        doc.putFields("story", stringValue("This recipe has been in our family for generations..."));
        doc.putFields("imageUrl", stringValue("https://example.com/images/cookies.jpg"));
        doc.putFields("sourceImageUrl", stringValue("https://example.com/images/cookies-original.jpg"));
        doc.putFields("servings", intValue(24));
        doc.putFields("prepTime", stringValue("PT20M"));
        doc.putFields("cookTime", stringValue("PT12M"));
        doc.putFields("totalTime", stringValue("PT32M"));
        doc.putFields("isFavorite", boolValue(true));
        doc.putFields("createdAt", intValue(1708000000));
        doc.putFields("updatedAt", intValue(1708100000));
        doc.putFields("tags", arrayOfStrings("dessert", "cookies", "baking", "chocolate"));
        doc.putFields("equipment", arrayOfStrings("mixing bowl", "baking sheet", "wire rack", "stand mixer"));

        // instructionSections as a JSON string -- the workaround
        StringBuilder json = new StringBuilder("[");
        for (int s = 0; s < numSections; s++) {
            if (s > 0) json.append(",");
            json.append(buildInstructionSectionJson("Section " + (s + 1), stepsPerSection, ingredientsPerStep));
        }
        json.append("]");
        doc.putFields("instructionSectionsJson", stringValue(json.toString()));

        return Value.newBuilder().setMapValue(doc).build();
    }

    static MapValue buildInstructionSection(String name, int numSteps, int ingredientsPerStep) {
        MapValue.Builder section = MapValue.newBuilder();
        section.putFields("name", stringValue(name));

        ArrayValue.Builder steps = ArrayValue.newBuilder();
        for (int i = 0; i < numSteps; i++) {
            steps.addValues(Value.newBuilder()
                .setMapValue(buildStep(i + 1, ingredientsPerStep))
                .build());
        }
        section.putFields("steps", Value.newBuilder().setArrayValue(steps).build());
        return section.build();
    }

    static MapValue buildStep(int stepNumber, int numIngredients) {
        MapValue.Builder step = MapValue.newBuilder();
        step.putFields("stepNumber", intValue(stepNumber));
        step.putFields("instruction", stringValue(
            "Mix the dry ingredients together in a large bowl. " +
            "Combine flour, baking soda, and salt. Set aside."));
        step.putFields("yields", intValue(1));
        step.putFields("optional", boolValue(false));

        ArrayValue.Builder ingredients = ArrayValue.newBuilder();
        for (int i = 0; i < numIngredients; i++) {
            ingredients.addValues(Value.newBuilder()
                .setMapValue(buildIngredient("Ingredient " + (i + 1), i % 3 == 0))
                .build());
        }
        step.putFields("ingredients", Value.newBuilder().setArrayValue(ingredients).build());
        return step.build();
    }

    static MapValue buildIngredient(String name, boolean withAlternates) {
        MapValue.Builder ingredient = MapValue.newBuilder();
        ingredient.putFields("name", stringValue(name));
        ingredient.putFields("notes", stringValue("finely chopped"));
        ingredient.putFields("optional", boolValue(false));
        ingredient.putFields("density", Value.newBuilder().setDoubleValue(1.05).build());

        // Amount (nested map)
        MapValue.Builder amount = MapValue.newBuilder();
        amount.putFields("value", Value.newBuilder().setDoubleValue(2.5).build());
        amount.putFields("unit", stringValue("cups"));
        ingredient.putFields("amount", Value.newBuilder().setMapValue(amount).build());

        // Alternates -- recursive nesting (the most problematic part)
        if (withAlternates) {
            ArrayValue.Builder alternates = ArrayValue.newBuilder();
            for (int i = 0; i < 2; i++) {
                MapValue.Builder alt = MapValue.newBuilder();
                alt.putFields("name", stringValue("Alternate " + (i + 1)));
                alt.putFields("notes", stringValue("can substitute"));
                alt.putFields("optional", boolValue(true));
                alt.putFields("density", Value.newBuilder().setDoubleValue(0.95).build());

                MapValue.Builder altAmount = MapValue.newBuilder();
                altAmount.putFields("value", Value.newBuilder().setDoubleValue(3.0).build());
                altAmount.putFields("unit", stringValue("tablespoons"));
                alt.putFields("amount", Value.newBuilder().setMapValue(altAmount).build());

                alt.putFields("alternates", Value.newBuilder()
                    .setArrayValue(ArrayValue.newBuilder()).build());

                alternates.addValues(Value.newBuilder().setMapValue(alt).build());
            }
            ingredient.putFields("alternates", Value.newBuilder().setArrayValue(alternates).build());
        } else {
            ingredient.putFields("alternates", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder()).build());
        }

        return ingredient.build();
    }

    static String buildInstructionSectionJson(String name, int numSteps, int ingredientsPerStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(name).append("\",\"steps\":[");
        for (int i = 0; i < numSteps; i++) {
            if (i > 0) sb.append(",");
            sb.append(buildStepJson(i + 1, ingredientsPerStep));
        }
        sb.append("]}");
        return sb.toString();
    }

    static String buildStepJson(int stepNumber, int numIngredients) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"stepNumber\":").append(stepNumber)
          .append(",\"instruction\":\"Mix the dry ingredients together in a large bowl.\",")
          .append("\"yields\":1,\"optional\":false,\"ingredients\":[");
        for (int i = 0; i < numIngredients; i++) {
            if (i > 0) sb.append(",");
            sb.append(buildIngredientJson("Ingredient " + (i + 1), i % 3 == 0));
        }
        sb.append("]}");
        return sb.toString();
    }

    static String buildIngredientJson(String name, boolean withAlternates) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(name).append("\",")
          .append("\"notes\":\"finely chopped\",\"optional\":false,\"density\":1.05,")
          .append("\"amount\":{\"value\":2.5,\"unit\":\"cups\"},");
        if (withAlternates) {
            sb.append("\"alternates\":[")
              .append("{\"name\":\"Alt 1\",\"notes\":\"sub\",\"optional\":true,\"density\":0.95,")
              .append("\"amount\":{\"value\":3.0,\"unit\":\"tbsp\"},\"alternates\":[]},")
              .append("{\"name\":\"Alt 2\",\"notes\":\"sub\",\"optional\":true,\"density\":0.95,")
              .append("\"amount\":{\"value\":3.0,\"unit\":\"tbsp\"},\"alternates\":[]}")
              .append("]");
        } else {
            sb.append("\"alternates\":[]");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Count total protobuf fields recursively to quantify document complexity. */
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

    static Value stringValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    static Value intValue(long v) {
        return Value.newBuilder().setIntegerValue(v).build();
    }

    static Value boolValue(boolean b) {
        return Value.newBuilder().setBooleanValue(b).build();
    }

    static Value arrayOfStrings(String... values) {
        ArrayValue.Builder arr = ArrayValue.newBuilder();
        for (String s : values) {
            arr.addValues(stringValue(s));
        }
        return Value.newBuilder().setArrayValue(arr).build();
    }
}
