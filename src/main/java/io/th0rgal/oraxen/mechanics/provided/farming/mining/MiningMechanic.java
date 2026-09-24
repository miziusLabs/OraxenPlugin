package io.th0rgal.oraxen.mechanics.provided.farming.mining;

import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;

import java.util.ArrayList;
import java.util.List;

public class MiningMechanic extends Mechanic {

    private final List<Offset> offsets;

    public MiningMechanic(MechanicFactory factory, String itemID, List<?> entries) {
        super(factory, itemID);
        List<Offset> parsed = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String coordinates))
                throw new IllegalArgumentException("mining offsets must be strings in x,y,z format for " + itemID);
            String[] parts = coordinates.split(",", -1);
            if (parts.length != 3)
                throw new IllegalArgumentException("Invalid mining offset '" + coordinates + "' for " + itemID);
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                Offset offset = new Offset(x, y, z);
                if (!parsed.contains(offset)) parsed.add(offset);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid mining offset '" + coordinates + "' for " + itemID, exception);
            }
        }
        offsets = List.copyOf(parsed);
    }

    public List<Offset> getOffsets() {
        return offsets;
    }

    public record Offset(int x, int y, int z) {
    }
}
