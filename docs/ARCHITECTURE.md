# Forge2Neo Architecture Document

## Overview

Forge2Neo is an automatic Minecraft mod porting tool that converts Forge 1.20.1 mods to NeoForge 1.21.1.
It operates as a multi-pass pipeline, where each pass handles a specific category of transformations
with increasing complexity.

## Design Principles

1. **Pipeline Architecture** — Each transformation is an isolated, composable pass
2. **Rule-Driven** — All transformations defined by declarative mapping rules (JSON/YAML)
3. **Test-Per-Rule** — Every transformation rule has input/output test fixtures
4. **Idempotent** — Running the tool twice produces the same result
5. **Confidence Scoring** — Each transformation reports confidence level (HIGH/MEDIUM/LOW)
6. **Dry-Run First** — Always preview changes before applying
7. **AI-Augmented** — Hard transformations delegate to LLM with structured prompts

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        CLI Interface                         │
│  forge2neo port --src ./mymod --dry-run --confidence=medium  │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                    Pipeline Orchestrator                      │
│  Manages pass ordering, dependency resolution, state          │
└─────────────────────┬───────────────────────────────────────┘
                      │
    ┌─────────────────┼─────────────────┐
    │                 │                 │
    ▼                 ▼                 ▼
┌─────────┐   ┌───────────┐   ┌──────────────┐
│ Pass 1   │   │ Pass 2     │   │ Pass 3        │
│ Text     │──▶│ AST        │──▶│ Structural    │
│ Replace  │   │ Transform  │   │ Refactor      │
└─────────┘   └───────────┘   └──────┬───────┘
                                      │
                    ┌─────────────────┼──────────────┐
                    ▼                 ▼              ▼
              ┌──────────┐   ┌────────────┐  ┌──────────┐
              │ Pass 4    │   │ Pass 5      │  │ Pass 6    │
              │ Build     │   │ Resource    │  │ AI-Assist │
              │ System    │   │ Migration   │  │ Transform │
              └──────────┘   └────────────┘  └──────────┘
                                                    │
                              ┌──────────────────────┘
                              ▼
                    ┌──────────────────┐
                    │ Report Generator  │
                    │ (changes + gates) │
                    └──────────────────┘
```

## Pass Details

### Pass 1: Text Replacement (Confidence: HIGH)
**Coverage: ~40% of all changes**

Simple find-and-replace operations that don't require parsing:

- Package renames: `net.minecraftforge` → `net.neoforged.neoforge` (8 patterns, ORDER MATTERS)
- Core class renames: `MinecraftForge` → `NeoForge`, `ForgeHooks` → `CommonHooks` (~50 patterns)
- Extension interface renames: `IForgeXXX` → `IXXXExtension` (~40 patterns)
- Event bus: `MinecraftForge.EVENT_BUS` → `NeoForge.EVENT_BUS`
- Registry constants: `ForgeRegistries.ITEMS` → `BuiltInRegistries.ITEM`
- Loot table parameter renames

**Implementation**: Ordered regex rules loaded from `mappings/text-replacements.json`

### Pass 2: AST Transformation (Confidence: HIGH-MEDIUM)
**Coverage: ~20% of all changes**

Requires Java AST parsing to understand code structure:

- `new ResourceLocation(ns, path)` → `ResourceLocation.fromNamespaceAndPath(ns, path)`
- `new ResourceLocation(str)` → `ResourceLocation.parse(str)`
- `RegistryObject<T>` → `DeferredHolder<R, T>` (type parameter adjustment)
- `FMLJavaModLoadingContext.get().getModEventBus()` → constructor parameter injection
- `@Mod.EventBusSubscriber` → `@EventBusSubscriber`
- `ModLoadingContext.get().registerConfig()` → `modContainer.registerConfig()`
- `@Cancelable` annotation → `implements ICancellableEvent`
- `event.getResult()` / `@HasResult` removal
- Vertex rendering method renames with context awareness

**Implementation**: JavaParser-based visitors with pattern matching rules

### Pass 3: Structural Refactoring (Confidence: MEDIUM)
**Coverage: ~15% of all changes**

Pattern-based structural transformations:

#### 3a: Capability → Attachment + Capability Split
- Detect `ICapabilityProvider` implementations
- Identify capability patterns (getCapability/LazyOptional)
- Generate `AttachmentType` registrations for data storage
- Generate `RegisterCapabilitiesEvent` handlers
- Convert `LazyOptional<T>` → `@Nullable T`
- Convert `cap.ifPresent(...)` → null checks
- Convert `cap.orElse(...)` → null-coalescing

#### 3b: Networking → CustomPacketPayload
- Detect `SimpleChannel` / packet class patterns
- Convert packet classes to `record ... implements CustomPacketPayload`
- Generate `StreamCodec` from encode/decode methods
- Convert registration to `RegisterPayloadHandlersEvent`
- Update send calls to `PacketDistributor` static methods

#### 3c: Mod Entry Point Restructuring
- Add `IEventBus modBus, ModContainer container` to `@Mod` constructor
- Remove `FMLJavaModLoadingContext.get()` calls
- Replace `DistExecutor` with direct dist checks

**Implementation**: Multi-step AST analysis → template-based code generation

### Pass 4: Build System Migration (Confidence: HIGH)
- `build.gradle` / `build.gradle.kts` transformation
- ForgeGradle → NeoGradle plugin swap
- Maven repository URL updates
- Java 17 → Java 21 in toolchain config
- Dependency coordinate updates
- `mods.toml` → `neoforge.mods.toml` rename + field updates
- `pack.mcmeta` format version updates

**Implementation**: Groovy/Kotlin DSL regex patterns + template generation

### Pass 5: Resource Migration (Confidence: HIGH)
- Data folder depluralisation: `tags/blocks/` → `tags/block/`
- Recipe JSON format updates
- Advancement JSON updates
- Pack format version bumps
- Tag file restructuring

**Implementation**: Directory rename rules + JSON transformation rules

### Pass 6: AI-Assisted Transformation (Confidence: LOW-MEDIUM)
For changes too complex for rule-based systems:

- NBT → DataComponent migration (requires understanding what data is stored)
- Enchantment code → data-driven JSON
- Complex rendering pipeline changes
- Recipe system migration (Container → RecipeInput)
- Any unrecognized patterns

**Implementation**:
- Extract code context around flagged patterns
- Generate structured prompts with before/after examples
- Call LLM API (Claude/GPT) for transformation suggestions
- Emit structured migration candidates for automated gate evaluation
- Learn from accepted/rejected gate outcomes

## Module Structure

```
forge2neo/
├── src/main/kotlin/com/forge2neo/
│   ├── cli/                    # CLI entry point (clikt)
│   │   └── Forge2NeoCommand.kt
│   ├── core/
│   │   ├── pipeline/
│   │   │   ├── Pipeline.kt         # Pipeline orchestrator
│   │   │   ├── Pass.kt             # Pass interface
│   │   │   └── PassResult.kt       # Result with confidence + changes
│   │   ├── transforms/
│   │   │   ├── text/
│   │   │   │   └── TextReplacementPass.kt
│   │   │   ├── ast/
│   │   │   │   ├── AstTransformPass.kt
│   │   │   │   ├── ResourceLocationTransform.kt
│   │   │   │   ├── ModEntryPointTransform.kt
│   │   │   │   ├── RegistryObjectTransform.kt
│   │   │   │   └── EventAnnotationTransform.kt
│   │   │   ├── structural/
│   │   │   │   ├── StructuralRefactorPass.kt   # state, orchestration (processFiles), parse checks
│   │   │   │   ├── StructuralRules<NN>.kt      # the rules as `internal fun StructuralRefactorPass.<rule>` extensions,
│   │   │   │   │                               # in pipeline order; each rule is followed by the helpers it uses and
│   │   │   │   │                               # each file's banner lists the entry points processFiles calls
│   │   │   │   └── <Topic>.kt                  # self-contained project-level rules (NbtHookFamilies, PacketTablePayloads,
│   │   │   │                                   #  LegacyBlockEntityCapabilities, DistExecutorCompat, ...)
│   │   │   └── ai/
│   │   │       ├── AiAssistPass.kt
│   │   │       └── PromptTemplates.kt
│   │   └── rules/
│   │       ├── RuleEngine.kt        # Loads and applies rules
│   │       └── Rule.kt              # Rule data model
│   ├── mapping/
│   │   ├── MappingDatabase.kt       # Central mapping registry
│   │   ├── PackageMapping.kt
│   │   ├── ClassMapping.kt
│   │   └── MethodMapping.kt
│   ├── gradle/
│   │   ├── BuildGradleTransformer.kt
│   │   └── ModsTomlTransformer.kt
│   ├── resources/
│   │   └── ResourceMigrator.kt
│   ├── report/
│   │   ├── ReportGenerator.kt
│   │   └── ChangeEntry.kt
│   └── utils/
│       ├── FileUtils.kt
│       └── JavaParserUtils.kt
├── src/main/resources/
│   └── mappings/
│       ├── text-replacements.json    # Pass 1 rules
│       ├── class-renames.json        # Complete class mapping
│       ├── method-renames.json       # Method signature mapping
│       ├── ast-transforms.json       # Pass 2 patterns
│       └── resource-renames.json     # Pass 5 folder mappings
├── src/test/
│   ├── kotlin/com/forge2neo/
│   │   ├── transforms/
│   │   │   ├── TextReplacementTest.kt
│   │   │   ├── AstTransformTest.kt
│   │   │   └── StructuralRefactorTest.kt
│   │   ├── mapping/
│   │   │   └── MappingDatabaseTest.kt
│   │   └── integration/
│   │       └── FullPipelineTest.kt
│   └── resources/fixtures/
│       ├── input/                    # Forge 1.20.1 sample code
│       └── expected/                 # Expected NeoForge 1.21.1 output
├── build.gradle.kts
└── docs/
    └── ARCHITECTURE.md
```

## Technology Stack

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | **Kotlin** | JVM-native, excellent Java interop, concise DSL capabilities |
| Java Parser | **JavaParser** | Best source-to-source preservation, active maintenance, rich visitor API |
| CLI Framework | **Clikt** | Kotlin-native, clean API, auto-generated help |
| Build Tool | **Gradle + Kotlin DSL** | Standard for JVM, matches target ecosystem |
| Testing | **JUnit 5 + Kotest** | Standard + property-based testing for rules |
| AI Integration | **Claude API** | Best for code transformation tasks |
| Serialization | **kotlinx.serialization** | JSON mapping rule loading |

### Why Kotlin + JavaParser?

1. **JavaParser** is the gold standard for Java source-to-source transformation:
   - Preserves comments, formatting, and whitespace
   - Full type resolution with symbol solver
   - Rich visitor/transformer pattern
   - Active community (1500+ GitHub stars)

2. **Kotlin** provides:
   - Null safety (critical for AST node traversal)
   - Extension functions (clean DSL for rules)
   - Coroutines (parallel file processing)
   - Sealed classes (exhaustive pattern matching)
   - 100% Java interop (direct JavaParser usage)

### Why NOT OpenRewrite?

OpenRewrite is excellent for enterprise Java refactoring, but:
- Overkill for this use case (we don't need LST/Lossless Semantic Trees)
- Recipe authoring has steep learning curve
- Less control over transformation ordering
- No existing Minecraft ecosystem recipes
- JavaParser gives us more direct, surgical control

## Mapping Database Design

The core of the tool is a comprehensive mapping database. Each mapping entry:

```kotlin
data class MappingEntry(
    val id: String,                    // Unique rule ID
    val category: Category,            // TEXT, AST, STRUCTURAL, AI
    val confidence: Confidence,        // HIGH, MEDIUM, LOW
    val forgePattern: String,          // What to match (regex or AST pattern)
    val neoForgeReplacement: String,   // What to replace with
    val context: MatchContext?,        // Optional: file type, scope constraints
    val description: String,           // Human-readable explanation
    val source: String                 // Where this mapping was documented
)
```

### Mapping Categories

| Category | Count (est.) | Format |
|----------|-------------|--------|
| Package renames | 8 | ordered text replacement |
| Class renames | ~90 | 1:1 text mapping |
| Method renames | ~50 | qualified name mapping |
| Constructor changes | ~10 | AST pattern → template |
| Annotation changes | ~5 | AST pattern → template |
| Structural patterns | ~10 | multi-node AST pattern |
| Build system | ~20 | regex/template |
| Resource folders | ~12 | directory rename |
| **Total** | **~205** | |

## Transformation Strategy

### Phase 1: Deterministic Rules (Ship immediately)
All Category A + B transformations. These are 100% correct, no ambiguity.
~60-70% coverage of typical mods.

### Phase 2: Pattern-Based Heuristics (Ship with review mode)
Category C transformations using AST pattern matching.
Tool detects patterns, generates code, and requires automated validation gates.
~80-85% total coverage.

### Phase 3: AI-Augmented (Experimental)
Category D via LLM integration.
Tool extracts context, generates prompts, and feeds candidates back through deterministic gates.
~90-95% total coverage with strict automated validation.

## Benchmark Strategy

1. **Unit benchmarks**: Time per rule application on single files
2. **Mod benchmarks**: Select 5-10 real open-source Forge 1.20.1 mods of varying size:
   - Small (<10 files): e.g., a simple item mod
   - Medium (10-50 files): e.g., a tech mod with machines
   - Large (50+ files): e.g., Create, Mekanism-scale
3. **Metrics**:
   - Files processed / second
   - Rules applied count
   - Confidence distribution (HIGH/MEDIUM/LOW)
   - Compilation success rate after porting
   - Strict gate failures remaining count

## CLI Interface Design

```
forge2neo - Automatic Forge 1.20.1 → NeoForge 1.21.1 Mod Porter

USAGE:
  forge2neo port [OPTIONS] --src <path>

OPTIONS:
  --src <path>            Source mod project directory (required)
  --out <path>            Output directory (default: <src>-neoforge)
  --dry-run               Preview changes without modifying files
  --passes <list>         Run specific passes (text,ast,structural,build,resource,ai)
  --min-confidence <lvl>  Dry-run/report filter for proposed changes (high,medium,low)
  --ai-api-key <key>      API key for AI-assisted transforms
  --report <path>         Write detailed report to file
  --verbose               Show detailed transformation log
  --backup                Create backup before modifying (default: true)

COMMANDS:
  port                    Port a Forge mod to NeoForge
  analyze                 Analyze a mod and report needed changes (no modifications)
  validate                Check if a ported mod has remaining Forge references
  mappings                List all known mappings and rules
  version                 Show version info
```
