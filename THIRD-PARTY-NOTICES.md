# Third-party notices

JReiProxyServer is MIT licensed (see `LICENSE`). It also contains work derived from, and
redistributes, the projects below. Each is listed with the notice its licence requires.

Nothing here is affiliated with or endorsed by the authors of JEI, REI, Fabric, NeoForge or
Architectury.

---

## Just Enough Items (JEI)

<https://github.com/mezz/JustEnoughItems> — MIT

The server side of JEI's recipe transfer is a port of `BasicRecipeTransferHandlerServer`, in
`network/RecipeTransfer.kt`. The packet layouts the plugin reads and writes were taken from JEI's
own `StreamCodec` definitions.

```
The MIT License (MIT)

Copyright (c) 2014-2015 mezz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Roughly Enough Items (REI)

<https://github.com/shedaniel/RoughlyEnoughItems> — MIT

The server side of REI's recipe transfer is a port of `InputSlotCrafter`, `NewInputSlotCrafter` and
`RecipeFinder`, in `network/rei/ReiTransfer.kt` and `network/rei/RecipeFinder.kt`. REI's ingredient
matcher is in turn derived from Minecraft's own recipe-book placement algorithm. The transfer
payload format and slot-accessor NBT shape were taken from REI's sources.

```
MIT License

Copyright (c) 2018, 2019, 2020, 2021, 2022, 2023 shedaniel

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Bundled in the released jar

The shaded jar redistributes these. No code from either was copied into this project; they are
dependencies packaged alongside it.

- **Kotlin standard library** — <https://github.com/JetBrains/kotlin> — Apache License 2.0
- **JetBrains Java Annotations** — <https://github.com/JetBrains/java-annotations> — Apache License 2.0

The Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0>.

---

## Read but not copied

The wire formats these define are implemented from their public sources; no code was taken.

- **Fabric API** — <https://github.com/FabricMC/fabric-api> — Apache License 2.0 — the
  `fabric:recipe_sync` payload.
- **NeoForge** — <https://github.com/neoforged/NeoForge> — LGPL 2.1 — the `neoforge:recipe_content`
  payload.
- **Architectury API** — <https://github.com/architectury/architectury-api> — LGPL 3.0 — the
  split-packet framing REI's packets travel in.
