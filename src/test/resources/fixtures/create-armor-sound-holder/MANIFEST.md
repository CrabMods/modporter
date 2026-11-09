# Create armor-material sound-holder fixtures

Frozen on 2026-11-03 for the run-215 late-bound `Holder<SoundEvent>` repair.
All hashes are SHA-256 over the exact file bytes.

## Create source fixtures

| File | SHA-256 | Provenance |
| --- | --- | --- |
| `create/AllArmorMaterials.original-input.java` | `C450B1CAE54652A64C7C7B8CCD066094A32EC5C3A3DCEB22AE2BB17DF6FBCC9B` | Exact original Create armor enum from the immutable strict Create input at `src/main/java/com/simibubi/create/content/equipment/armor/AllArmorMaterials.java`. |
| `create/AllArmorMaterials.pre-render.java` | `9D7095693E4FB90747020C77F1244B907EBEA644F3D010292400E3ED6DD4C0A3` | Original input with only the already-required 1.21.1 vanilla migration `SoundEvents.ARMOR_EQUIP_LEATHER` to `SoundEvents.ARMOR_EQUIP_LEATHER.value()`. This is the input shape consumed by `LegacyArmorMaterialSoundHolderGraph` and `LegacyArmorMaterialEnums`. |
| `create/AllArmorMaterials.owned-before.java` | `F0CA341C04CEC60A6BC5272257B05CC8FA03B82AB99EA78906DBEF4DBD3105D4` | Converted run-215 armor output before the lazy-holder repair, captured from `src/main/java/com/simibubi/create/content/equipment/armor/AllArmorMaterials.java`. It preserves the failing eager `wrapAsHolder(legacy.getEquipSound())` path as evidence. |
| `create/AllSoundEvents.owned-before.java` | `A77D84E66C1C4F50F71AFE0953F81B500D5BDDCF0A43D79219139BDE2FDAF4E2` | Converted run-215 producer before holder accessors, captured from `src/main/java/com/simibubi/create/AllSoundEvents.java`. |
| `create/AllSoundEvents.expected-holder-accessors.java` | `50FD9D8B897586BBB1825B29345E50941D70B3C109111F84A0F3EA1D223C774A` | Exact expected rewrite of the owned producer: one abstract `getMainEventHolder()` plus exact backing-holder overrides for wrapped and custom entries. No existing `getMainEvent()` behavior is changed. |

## Runtime semantic fixture

| File | SHA-256 | Provenance |
| --- | --- | --- |
| `runtime/AllSoundEvents.java` | `A6960E2E1E0C3C0287088EE1E4B000CDF1604479AE5CFC812334A47112BB1CC7` | Reduced producer retaining the source shapes used by the graph resolver: abstract base, wrapped list/record `DeferredHolder`, and direct custom `DeferredHolder`. Timeline counters expose class initialization and prepare order. The focused JavaCompiler test runs the actual graph rewrite over this source before compilation. |

The tests resolve these paths relative to the repository root. Keep this
`fixtures` directory present when running
`CreateArmorMaterialSoundHolderGraphFocusedTest` and
`CreateArmorMaterialSoundHolderLateRegistryTest`.
