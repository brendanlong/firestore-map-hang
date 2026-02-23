# Firestore Map Hang Reproduction

Reproduction of a performance bug in the Firebase Firestore Android SDK where
documents containing deeply nested `Map` fields cause the Firestore worker
thread to hang for tens of seconds during document equality checks.

## The Bug

After any Firestore mutation on a document with nested maps, the UI hangs.
All subsequent Firestore operations (listeners, reads, writes) are blocked.

### Root Cause

**protobuf-javalite's `MessageSchema.equals()` has O(V^N) complexity on
messages with `oneof` fields, where V = number of `oneof` variants and
N = nesting depth.**

The Firestore `Value` message uses a `oneof` with 6 variants (boolean,
integer, double, string, array, map). In protobuf-javalite, all `oneof`
variants share a single storage slot. When `MessageSchema.equals()` compares
two `Value` messages, it iterates through **all 6 field entries** in the
schema buffer. For each entry:

1. `isOneofCaseEqual()` checks whether both messages have the same active
   `oneof` case -- this is always true when comparing identical documents.
2. Since the cases match, `safeEquals()` is called on the object at the
   shared storage offset.

This means the nested `MapValue` is compared **6 times instead of once**
at each nesting level. At each level, the 6x multiplication compounds:

| Depth | Comparisons | Time (measured) |
|-------|-------------|-----------------|
| 1     | 1           | 0.08 ms         |
| 5     | 1,296       | 3 ms            |
| 6     | 7,776       | 17 ms           |
| 7     | 46,656      | 100 ms          |
| 8     | 279,936     | 600 ms          |
| 9     | 1,679,616   | 3.6 sec         |
| 10    | 10,077,696  | 21 sec          |

**Measured growth factor: exactly 6.0x per depth level** (matching the
number of `oneof` variants).

A typical recipe document with ~7 levels of map nesting (document -> section
-> step -> ingredient -> amount) takes **~6 seconds** per `equals()` call.

### How Firestore triggers this

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

4. **Exponential time**: `MessageSchema.equals()` hits the O(6^N) path described above.

### Prior Fix (Incomplete)

[PR #1920](https://github.com/firebase/firebase-android-sdk/pull/1920) fixed
`Values.java` to use a custom `equals()` that avoids protobuf's native
comparison for recursive map fields. However, `ObjectValue.equals()` still calls
`buildProto().equals()` directly, **bypassing the `Values.java` fix entirely**.

`View.computeDocChanges()` calls `ObjectValue.equals()`, so the slow
protobuf path is still hit.

## Document Structure That Triggers This

Any document with nested maps triggers this. A recipe document:

```
name: String
instructionSections: [              // Array of maps
  { steps: [                        // Array of maps
      { instruction: String,
        ingredients: [              // Array of maps
          { name: String,
            amount: {               // Nested map
              value: Double?,
              unit: String?
            },
            alternates: [...]       // More nesting
          }
        ]
      }
    ]
  }
]
```

This creates ~6-7 levels of nested `MapValue` in the protobuf representation.
Even the simplest possible recipe (1 section, 1 step, 1 ingredient) causes
`equals()` to take **~6 seconds**.

The nesting depth is the only thing that matters:
- **1000 flat fields** (no nesting): 0.4 ms
- **500 sibling maps** in an array: 6 ms
- **8 levels of nesting** with just 1 field each: 600 ms

## Running the Reproduction

```bash
./gradlew run
```

This builds a JVM project using protobuf-javalite 3.25.5 (the exact version
used by Firebase BOM 34.9.0 / firebase-firestore 26.1.0) and runs
`Value.equals()` on structures of varying depth and width.

### Requirements

- Java 17+

### Additional tools

```bash
# Run the equals call counter (proves call count is linear, not exponential)
./gradlew run -DmainClass=com.example.firestorehang.EqualsCallCounter

# Run schema inspection (shows buffer layout and timing comparisons)
./gradlew run -DmainClass=com.example.firestorehang.SchemaInspector

# Run hypothesis verification (proves 6x growth factor)
./gradlew run -DmainClass=com.example.firestorehang.VerifyHypothesis
```

### Expected Output

```
--- Test 1: Pure depth -- {a: {a: {a: ...}}} ---
(Single field per map level)

  depth= 1 |      0.079 ms
  depth= 2 |      0.129 ms   (1.6x prev)
  depth= 3 |      0.606 ms   (4.7x prev)
  depth= 4 |      0.613 ms   (1.0x prev)
  depth= 5 |      2.813 ms   (4.6x prev)
  depth= 6 |     16.722 ms   (5.9x prev)
  depth= 7 |     98.330 ms   (5.9x prev)
  depth= 8 |    590.539 ms   (6.0x prev)
  depth= 9 |   3560.455 ms   (6.0x prev)
  depth=10 |  21224.146 ms   (6.0x prev)

Growth factor per depth level: 6.0x (= number of oneof variants)
Complexity: O(6^N) where N = nesting depth
```

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
- [protobuf#19670](https://github.com/protocolbuffers/protobuf/issues/19670) --
  protobuf-javalite `MessageSchema.equals()` exponential for `oneof` with nested messages
