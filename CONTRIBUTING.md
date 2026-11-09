# Contributing

ModPorter helps developers prepare and review Minecraft mod migrations.
Generated research ports are examples of its reusable rules, and their
migration issues are tracked in this repository.

Start with a pinned source revision and a small reproduction. Review the
generated change, identify the API or source structure involved, and add a
general migration rule with focused regression coverage. Keep original
authors, source licenses, and resource provenance intact.

Run the public build with `./mod-build.ps1 build shadowJar`. Changes to
runtime behavior also need the relevant native project checks. Record what
was tested: compilation, server/client loading, item-interface rendering,
and any specific gameplay or integration scenarios exercised.

When a fix is ready, regenerate affected research ports from the tool rather
than accumulating independent handwritten behavior patches in each example.
Human source review and mod-specific playtesting remain part of preparing a
candidate for wider distribution.
