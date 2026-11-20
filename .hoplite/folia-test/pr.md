## Stop recipe reloads from crashing Folia

Running `/o reload recipes` could disconnect players and stop a Folia region from ticking. Folia refreshed player advancements as part of each recipe registration even though the player could be ticking or saving in another region.

On Folia, Oraxen now keeps the active recipes when asked to reload and tells the sender to restart the server to apply recipe changes. This also applies to the recipe part of a full Oraxen reload. Recipes still load at startup, and Paper can still reload them without a restart.
