package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.JsonObject;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Locale;

/** Model rotations for full native blocks, independent of their allocated appearance states. */
public enum NativeBlockRotation {
    NONE(List.of(new Variant("", 0, 0))),
    HORIZONTAL(List.of(new Variant("", 0, 0), new Variant("#rotation=east", 0, 90),
            new Variant("#rotation=south", 0, 180), new Variant("#rotation=west", 0, 270))),
    AXIS(List.of(new Variant("", 0, 0), new Variant("#rotation=axis_x", 90, 90),
            new Variant("#rotation=axis_z", 90, 0)));

    private final List<Variant> variants;

    NativeBlockRotation(List<Variant> variants) {
        this.variants = variants;
    }

    public static NativeBlockRotation parse(String value) {
        if (value == null) return NONE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid appearance.rotation '" + value
                    + "'. Expected none, horizontal or axis", exception);
        }
    }

    /** The first variant uses the root item identity to preserve its existing allocation. */
    public List<Variant> variants() {
        return variants;
    }

    /** Horizontal models face the player. Axis models align their original vertical axis to the clicked face. */
    public Variant select(BlockFace clickedFace, float playerYaw) {
        return switch (this) {
            case NONE -> variants.getFirst();
            case HORIZONTAL -> {
                if (!Float.isFinite(playerYaw)) throw new IllegalArgumentException("Player yaw must be finite");
                double normalizedYaw = ((double) playerYaw % 360 + 360) % 360;
                int quadrant = (int) Math.floor(normalizedYaw / 90 + 0.5) % 4;
                yield variants.get(quadrant);
            }
            case AXIS -> {
                if (clickedFace == null) throw new IllegalArgumentException("Axis rotation requires a clicked face");
                yield switch (clickedFace) {
                    case EAST, WEST -> variants.get(1);
                    case NORTH, SOUTH -> variants.get(2);
                    case UP, DOWN -> variants.getFirst();
                    default -> throw new IllegalArgumentException("Axis rotation requires a cardinal block face");
                };
            }
        };
    }

    public record Variant(String suffix, int x, int y) {
        public String identity(String itemId) {
            return itemId + suffix;
        }

        /** Returns a copy so one variant cannot change another variant's model. */
        public JsonObject apply(JsonObject model) {
            JsonObject rotated = model.deepCopy();
            rotated.remove("x");
            rotated.remove("y");
            if (x != 0) rotated.addProperty("x", x);
            if (y != 0) rotated.addProperty("y", y);
            return rotated;
        }
    }
}
