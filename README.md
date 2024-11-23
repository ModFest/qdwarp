"A really simple warps mod for servers, originally created for (but not during) ModFest: Singularity. Warps are stored in an easily readable and editable `warps.ini` file inside the world save.

## Commands

### /warp `name`
Can be used by anyone.
    
Teleport to the warp named `name`, even if it's in another dimension.

Supports full lenient suggestions. e.g. `/warp salad` will turn up a warp named `fruit-salad`.

### /warpother `player` `name`
Can be used by command blocks and operators.

Warp the given `player` to the warp named `name`, as if they themselves had run `/warp name`.

Useful for replacing command blocks with hardcoded `/execute as @p in minecraft:the_nether run tp @s -27 40 123` or similar. `/warpother @p nether-test` is a lot nicer and easier to work with.

### /mkwarp `pos-mode` `rot-mode` `name`
Requires operator.

Create a new warp where you're standing.

`pos-mode` can be any of:
* `pos-exact`: Keep your *exact* position in the warp. Players will teleport exactly where you are standing, no ifs, ands, or buts.
* `pos-block-corner`: Your position will be rounded to a block corner in the warp.
* `pos-block-center`: Your position will be rounded to the center of the block you're standing in.

The Y coordinate is always kept as-is.

`rot-mode` can be any of:
* `rot-exact`: Keep your *exact* rotation in the warp.
* `rot-45`: Your rotation will be rounded to a multiple of 45 degrees.
* `rot-cardinal`: Your rotation will be rounded to a cardinal direction.

The `name` can be anything. Really. Anything.

### /rmwarp `name`
Requires operator.

Delete an existing warp.
