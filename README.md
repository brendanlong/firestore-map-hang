# Firestore Map Hang Reproduction

Reproduction of a performance bug in the Firebase Firestore Android SDK where
documents containing deeply nested `Map` fields cause the Firestore worker
thread to hang for tens of seconds during document equality checks.

## The Bug

After any Firestore mutation on a document with nested maps, the UI hangs.
All subsequent Firestore operations (listeners, reads, writes) are blocked.

### Root Cause

1. **Single-threaded worker**: All Firestore operations serialize through
   `AsyncQueue` on a single `FirestoreWorker` thread.

2. **Document equality check**: After every remote event, `View.computeDocChanges()`
   calls:
   ```java
   boolean docsEqual = oldDoc.getData().equals(newDoc.getData());
   ```

3. **ObjectValue.equals() delegates to protobuf**: `ObjectValue.equals()` calls
   `buildProto().equals()`, which invokes protobuf-javalite's native
   `MessageLite.equals()`.

4. **Pathological performance on map fields**: For messages containing
   `map<string, Value>` fields (which is how Firestore represents all nested
   objects), protobuf-javalite's generated `equals()` has pathological
   performance -- **a document with just 233 nested fields takes ~52 seconds**.

### Prior Fix (Incomplete)

[PR #1920](https://github.com/firebase/firebase-android-sdk/pull/1920) fixed
`Values.java` to use a custom `equals()` that avoids protobuf's native
comparison for recursive map fields. However, `ObjectValue.equals()` still calls
`buildProto().equals()` directly, **bypassing the `Values.java` fix entirely**.

`View.computeDocChanges()` calls `ObjectValue.equals()`, so the slow
protobuf path is still hit.

## Document Structure That Triggers This

A recipe document with nested `instructionSections`:

```
name: String
sourceUrl: String?
story: String?
imageUrl: String?
...
instructionSections: [              // Array of maps
  { name: String?,
    steps: [                        // Array of maps
      { stepNumber: Long,
        instruction: String,
        ingredients: [              // Array of maps
          { name: String,
            amount: {               // Nested map
              value: Double?,
              unit: String?
            },
            alternates: [...]       // Recursive nesting
          }
        ]
      }
    ]
  }
]
```

This creates 5+ levels of nested `MapValue` in the protobuf representation.
Even a small recipe (1 section, 3 steps, 2 ingredients) with ~233 fields
causes `equals()` to take **tens of seconds**.

## Running the Reproduction

```bash
./gradlew run
```

This builds a JVM project using protobuf-javalite 3.25.5 (the exact version
used by Firebase BOM 34.9.0 / firebase-firestore 26.1.0) and runs
`Value.equals()` on recipe documents of increasing size.

### Requirements

- Java 17+

### Expected Output

The full run completes in ~5 minutes. Results on a server with OpenJDK 17:

```
--- Part 1: Scaling behavior of Value.equals() on nested maps ---

sections=1 steps=1 ingredients=1 | fields=  89 | equals():    16977.1 ms *** HANG (>10s) ***
sections=1 steps=2 ingredients=1 | fields= 140 | equals():    34224.5 ms *** HANG (>10s) ***
sections=1 steps=2 ingredients=2 | fields= 168 | equals():    34848.3 ms *** HANG (>10s) ***
sections=1 steps=3 ingredients=2 | fields= 233 | equals():    52915.7 ms *** HANG (>10s) ***
sections=2 steps=2 ingredients=2 | fields= 301 | equals():    69640.4 ms *** HANG (>10s) ***
sections=2 steps=3 ingredients=2 | fields= 431 | equals():   103670.8 ms *** HANG (>10s) ***

--- Part 2: Baseline -- flat fields only (no nested maps) ---
  14 flat scalar fields: 0.013 ms (34 fields)

--- Part 3: JSON string workaround comparison ---
(Using 1 section, 1 step, 1 ingredient -- the smallest possible recipe)
  Nested maps:            17042.7 ms (89 fields)
  JSON string:              0.015 ms (36 fields)
  Speedup:             1,107,459x
```

Key observations:
- **17 seconds** for the smallest possible recipe (1 section, 1 step, 1 ingredient, 89 fields)
- **104 seconds** for a modest recipe (2 sections, 3 steps, 2 ingredients, 431 fields)
- Time scales roughly **linearly with number of steps** (~17s per step), suggesting O(N) with
  an enormous constant factor from reflection
- **Flat scalar fields** are sub-millisecond (0.013 ms for 34 fields)
- **JSON string workaround** is over **1 million times faster**

## Workaround

Store deeply nested fields as JSON strings instead of native Firestore maps:

```kotlin
// Before (causes hang):
val instructionSections: List<InstructionSectionDto> = emptyList()

// After (fast):
val instructionSectionsJson: String = "[]"
```

Protobuf's `equals()` on a `StringValue` is a simple byte comparison -- zero
recursion, zero reflection, zero `MapFieldLite`.

## Affected Versions

- **Firebase BOM**: 34.9.0
- **firebase-firestore**: 26.1.0
- **protobuf-javalite**: 3.25.5

## Related Issues

- [firebase/firebase-android-sdk#1971](https://github.com/firebase/firebase-android-sdk/issues/1971) --
  "Firestore realtime query stops working after few updates"
- [firebase/firebase-android-sdk#1920](https://github.com/firebase/firebase-android-sdk/pull/1920) --
  "Remove usages of Protobuf equals" (partial fix)
