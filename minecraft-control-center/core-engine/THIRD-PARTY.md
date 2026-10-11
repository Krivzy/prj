# MCC Core: source and licensing

MCC Core 0.2 is a Paper source fork, with a separate required MCC panel bootstrap.
The engine is GPL-3.0-only. The full license is [LICENSE.md](LICENSE.md).
Preserve upstream notices and provide this patch package with distributed binaries.

The base is [Paper commit46d4834e8e2ec4af9dcfa028076fb16dfb8a3911](https://github.com/PaperMC/Paper/tree/46d4834e8e2ec4af9dcfa028076fb16dfb8a3911),
Minecraft26.3 / BETA / Java25. See [Paper license](licenses/Paper-LICENSE.md)
and the original upstream `licenses/GPL.md` and `licenses/MIT.md`.
Paperclip and bundled dependencies retain every original license entry.

The implementation follows a review of these published optimizations:

- [Leaf0157](https://github.com/Winds-Studio/Leaf/blob/0edc7f3b7d79b0e2e16a0ed1df8278c02c73537f/leaf-server/minecraft-patches/features/0157-C2ME-Reduce-Allocations.patch): ore BitSet reuse. Its C2ME source credits ishland and MIT. MCC uses its own one-buffer-per-worker pool with a size bound, nested-call fallback, and `finally` release.
- [Leaf0174](https://github.com/Winds-Studio/Leaf/blob/0edc7f3b7d79b0e2e16a0ed1df8278c02c73537f/leaf-server/minecraft-patches/features/0174-Bulk-writes-to-writeLongArray-during-chunk-loading.patch): bulk long encoding. MCC writes both static/instance paths, checks overflow, preserves scalar failure behavior, and excludes merged composite NIO views before and after growth.
- [Leaf0240](https://github.com/Winds-Studio/Leaf/blob/0edc7f3b7d79b0e2e16a0ed1df8278c02c73537f/leaf-server/minecraft-patches/features/0240-optimize-SimpleBitStorage-object-layout.patch): remove duplicate unsigned divider fields. Copyright(c)2025 hayanesuru, Leaf contributors; MIT default patch license.
- [Leaf0316](https://github.com/Winds-Studio/Leaf/blob/0edc7f3b7d79b0e2e16a0ed1df8278c02c73537f/leaf-server/minecraft-patches/features/0316-Optimize-chunk-packet-construction.patch): explicit heightmap loops / EnumMap and block entity values. Copyright(c)2025 hayanesuru, Leaf contributors; MIT default patch license.
- [Gale CompoundTag iteration](https://github.com/GaleMC/Gale/blob/89a0e1164bb97acbd77d8b56f8bf49bce119bcb7/gale-server/minecraft-patches/sources/net/minecraft/nbt/CompoundTag.java.patch): avoid duplicate NBT lookup. MCC additionally uses fastutil's nonallocating fast-entry iterator for Paper's default maps. See [Gale notice](licenses/Gale-LICENSE.md), GPL-3.0.

The reviewed [Leaf license](licenses/Leaf-LICENSE.md) states that patches are MIT
unless their own headers specify otherwise; binaries are GPL-3.0-only.
No Gale/Leaf/Paper branding artwork is included. No affiliation or endorsement is implied.
Decompiled Minecraft sources and Minecraft binaries are not included in this package.
The build fetches the official source and official Minecraft dependencies locally.
