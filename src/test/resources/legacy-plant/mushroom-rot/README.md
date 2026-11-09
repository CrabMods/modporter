# MushroomRot bytecode differential fixture

`MushroomRotGenFeature.class` is an unchanged Java 17 class entry from the
named Dynamic Trees `1.3.0-BETA13` artifact selected by
`logs/public-create-source-graph-current.json`. The JUnit differential test
loads it only inside an isolated class loader with minimal Java 17 stubs and
compares its protected `postRot` behavior with the current compatibility
helper. The complete 3.7 MB artifact is deliberately not preserved here.

The entry SHA-256 is
`7e59b6e0692b1469d99a3d11762046e72458d75d626988d7c46e4f878f97871b`.
See `fixture-manifest.json` for the transformed-artifact identity and source
graph coordinates.

The upstream method is by Harley O'Connor and is licensed under MIT:
`Copyright (c) 2016,2017 Ferreus Veritas`. The primary provenance record and
full license text are in `third_party/dynamictrees/README.md` and
`third_party/dynamictrees/LICENSE`.
