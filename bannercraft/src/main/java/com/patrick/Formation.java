package com.patrick;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.util.Vector;

public enum Formation {
    SHIELD_WALL,
    LINE,
    LOOSE;

    public List<Vector> getOffsets(int count) {
        List<Vector> offsets = new ArrayList<>();
        switch (this) {
            case SHIELD_WALL -> {
                int perRow = (int) Math.ceil(count / 3.0);
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < perRow && offsets.size() < count; c++) {
                        offsets.add(new Vector(c - perRow / 2.0, 0, r));
                    }
                }
            }
            case LINE -> {
                for (int i = 0; i < count; i++) {
                    offsets.add(new Vector(i - count / 2.0, 0, 0));
                }
            }
            case LOOSE -> {
                int perRow = (int) Math.ceil(Math.sqrt(count));
                for (int r = 0; r < perRow; r++) {
                    for (int c = 0; c < perRow && offsets.size() < count; c++) {
                        offsets.add(new Vector(c * 2, 0, r * 2));
                    }
                }
            }
        }
        return offsets;
    }
}
