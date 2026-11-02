# Graph Report - KachaponConverter  (2026-11-02)

## Corpus Check
- Corpus is ~47,143 words - fits in a single context window. You may not need a graph.

## Summary
- 627 nodes · 1664 edges · 27 communities (10 shown, 17 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 143 edges (avg confidence: 0.85)
- Token cost: 99,335 input · 0 output

## Community Hubs (Navigation)
- Puente y pruebas del núcleo
- Ventana de escritorio
- Escritor de Clip
- Arquitectura documentada
- Modos de fusión
- Planificador de capas
- Conversor de ibisPaint
- App de Android
- Composición de píxeles
- Lectura de teselas Krita
- Pruebas en Android
- Orquestador
- PNG
- Formato Binc
- Lector SQLite
- Escritor SQLite
- Secuencia PNG

## God Nodes (most connected - your core abstractions)
1. `Bridge` - 67 edges
2. `Manifest` - 53 edges
3. `ClipWriter` - 37 edges
4. `KritaConverter` - 32 edges
5. `Planner` - 29 edges
6. `MainApp` - 23 edges
7. `IbisConverter` - 22 edges
8. `MainActivity` - 19 edges
9. `CoreTest` - 18 edges
10. `Composite` - 17 edges

## Surprising Connections (you probably didn't know these)
- `MainApp` --calls--> `Orchestrator`  [EXTRACTED]
  docs/LIMPIEZA.md → ARQUITECTURA_PIPELINE.md
- `Colisión de salida.tmp` --references--> `Orchestrator`  [EXTRACTED]
  docs/LIMPIEZA.md → ARQUITECTURA_PIPELINE.md
- `MainActivity (Android)` --calls--> `Orchestrator`  [EXTRACTED]
  docs/RENDIMIENTO.md → ARQUITECTURA_PIPELINE.md
- `Decodificación en paralelo del Planner` --rationale_for--> `Planner`  [EXTRACTED]
  docs/RENDIMIENTO.md → ARQUITECTURA_PIPELINE.md
- `SequenceZipConverter` --conceptually_related_to--> `Converter (extract/build)`  [INFERRED]
  docs/LIMPIEZA.md → ARQUITECTURA_PIPELINE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Conversores de formato sobre el puente** — arquitectura_pipeline_kritaconverter, arquitectura_pipeline_clipconverter, arquitectura_pipeline_ibisconverter, arquitectura_pipeline_pngsequenceconverter, arquitectura_pipeline_bridge [EXTRACTED 1.00]
- **Combinación de capas** — arquitectura_pipeline_planner, arquitectura_pipeline_composite, arquitectura_pipeline_blend [EXTRACTED 1.00]
- **Control de versión del .ipv** — docs_ibis_versiones_needversion, docs_ibis_versiones_meta_tail, docs_ibis_versiones_metainfochunk, docs_ibis_versiones_legacy_format [EXTRACTED 1.00]

## Communities (27 total, 17 thin omitted)

### Community 0 - "Puente y pruebas del núcleo"
Cohesion: 0.06
Nodes (10): Bridge, Progress, Converter, Drawing, KritaConverter, KritaTimeout, SequenceZipConverter, Layer (+2 more)

### Community 2 - "Escritor de Clip"
Cohesion: 0.15
Nodes (3): ClipWriter, Node, Table

### Community 3 - "Arquitectura documentada"
Cohesion: 0.07
Nodes (33): Binc, Blend, Bridge (puente), clip_template.clip, ClipConverter, ClipWriter, Composite, Converter (extract/build) (+25 more)

### Community 4 - "Modos de fusión"
Cohesion: 0.10
Nodes (4): Blend, CelCurve, ClipConverter, Native

### Community 5 - "Planificador de capas"
Cohesion: 0.15
Nodes (5): FrameRef, Plane, Planner, State, IbisWriterTest

### Community 6 - "Conversor de ibisPaint"
Cohesion: 0.12
Nodes (4): IbisConverter, Layer, Native, RpngEqualityTest

### Community 10 - "Lectura de teselas Krita"
Cohesion: 0.20
Nodes (4): KraTiles, KraTilesTest, ShrinkEqualityTest, PlannerTest

### Community 12 - "Orquestador"
Cohesion: 0.14
Nodes (3): ConversionResult, Orchestrator, OrchestratorTest

### Community 14 - "Formato Binc"
Cohesion: 0.20
Nodes (3): Binc, Doc, Node

### Community 17 - "Escritor SQLite"
Cohesion: 0.29
Nodes (3): LinkedHashMap<String, int[]>, Entry, SqliteWriter

## Knowledge Gaps
- **7 isolated node(s):** `Manifest (manifest.json)`, `Sqlite`, `SqliteWriter`, `Binc`, `Colisión de salida.tmp` (+2 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 93 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Bridge` connect `Puente y pruebas del núcleo` to `Modos de fusión`, `Planificador de capas`, `Conversor de ibisPaint`, `Lectura de teselas Krita`, `Pruebas en Android`, `Orquestador`, `PNG`, `Secuencia PNG`, `Gson del puente`?**
  _High betweenness centrality (0.210) - this node is a cross-community bridge._
- **What connects `Manifest (manifest.json)`, `Sqlite`, `SqliteWriter` to the rest of the system?**
  _7 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Puente y pruebas del núcleo` be split into smaller, more focused modules?**
  _Cohesion score 0.057764186204553175 - nodes in this community are weakly interconnected._
- **Why does `Orchestrator` connect `Orquestador` to `Puente y pruebas del núcleo`, `Ventana de escritorio`, `Pruebas en Android`, `App de Android`?**
  _High betweenness centrality (0.113) - this node is a cross-community bridge._
- **Should `Ventana de escritorio` be split into smaller, more focused modules?**
  _Cohesion score 0.07317073170731707 - nodes in this community are weakly interconnected._
- **Why does `Manifest` connect `Puente y pruebas del núcleo` to `Modos de fusión`, `Planificador de capas`, `Conversor de ibisPaint`, `Lectura de teselas Krita`, `Pruebas en Android`, `PNG`, `Imports de Planner y Manifest`?**
  _High betweenness centrality (0.081) - this node is a cross-community bridge._
- **Should `Arquitectura documentada` be split into smaller, more focused modules?**
  _Cohesion score 0.07254623044096728 - nodes in this community are weakly interconnected._