package net.minestom.server.instance.light;

import it.unimi.dsi.fastutil.shorts.ShortArrayFIFOQueue;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.minestom.server.instance.light.LightCompute.*;

final class BlockLight implements Light {
    private byte[] content;
    private byte[] contentPropagation;
    private byte[] contentPropagationSwap;

    private final AtomicBoolean isValidBorders = new AtomicBoolean(true);
    private final AtomicBoolean needsSend = new AtomicBoolean(false);

    private final Section[] neighborSections = new Section[BlockFace.values().length];

    @Override
    public void flip() {
        if (this.contentPropagationSwap != null)
            this.contentPropagation = this.contentPropagationSwap;
        this.contentPropagationSwap = null;
    }

    static ShortArrayFIFOQueue buildInternalQueue(Palette blockPalette) {
        ShortArrayFIFOQueue lightSources = new ShortArrayFIFOQueue();
        // Apply section light
        blockPalette.getAllPresent((x, y, z, stateId) -> {
            final Block block = Block.fromStateId((short) stateId);
            assert block != null;
            final byte lightEmission = (byte) block.registry().lightEmission();

            final int index = x | (z << 4) | (y << 8);
            if (lightEmission > 0) {
                lightSources.enqueue((short) (index | (lightEmission << 12)));
            }
        });
        return lightSources;
    }

    private ShortArrayFIFOQueue buildExternalQueue(Instance instance, Palette blockPalette, Point[] neighbors, byte[] content) {
        ShortArrayFIFOQueue lightSources = new ShortArrayFIFOQueue();

        for (int i = 0; i < neighbors.length; i++) {
            final BlockFace face = BlockFace.values()[i];
            Point neighborSection = neighbors[i];
            if (neighborSection == null) continue;

            Section otherSection = neighborSections[face.ordinal()];

            if (otherSection == null) {
                Chunk chunk = instance.getChunk(neighborSection.blockX(), neighborSection.blockZ());
                if (chunk == null) continue;

                otherSection = chunk.getSection(neighborSection.blockY());
                neighborSections[face.ordinal()] = otherSection;
            }

            Light otherLight = otherSection.blockLight();

            for (int bx = 0; bx < 16; bx++) {
                for (int by = 0; by < 16; by++) {
                    final int k = switch (face) {
                        case WEST, BOTTOM, NORTH -> 0;
                        case EAST, TOP, SOUTH -> 15;
                    };

                    final byte lightEmission = (byte) Math.max(switch (face) {
                        case NORTH, SOUTH -> (byte) otherLight.getLevel(bx, by, 15 - k);
                        case WEST, EAST -> (byte) otherLight.getLevel(15 - k, bx, by);
                        default -> (byte) otherLight.getLevel(bx, 15 - k, by);
                    } - 1, 0);

                    final int posTo = switch (face) {
                        case NORTH, SOUTH -> bx | (k << 4) | (by << 8);
                        case WEST, EAST -> k | (by << 4) | (bx << 8);
                        default -> bx | (by << 4) | (k << 8);
                    };

                    if (content != null) {
                        final int internalEmission = (byte) (Math.max(getLight(content, posTo) - 1, 0));
                        if (lightEmission <= internalEmission) continue;
                    }

                    final Block blockTo = switch (face) {
                        case NORTH, SOUTH -> getBlock(blockPalette, bx, by, k);
                        case WEST, EAST -> getBlock(blockPalette, k, bx, by);
                        default -> getBlock(blockPalette, bx, k, by);
                    };

                    final Block blockFrom = (switch (face) {
                        case NORTH, SOUTH -> getBlock(otherSection.blockPalette(), bx, by, 15 - k);
                        case WEST, EAST -> getBlock(otherSection.blockPalette(), 15 - k, bx, by);
                        default -> getBlock(otherSection.blockPalette(), bx, 15 - k, by);
                    });

                    if (blockTo == null && blockFrom != null) {
                        if (blockFrom.registry().collisionShape().isOccluded(Block.AIR.registry().collisionShape(), face.getOppositeFace()))
                            continue;
                    } else if (blockTo != null && blockFrom == null) {
                        if (Block.AIR.registry().collisionShape().isOccluded(blockTo.registry().collisionShape(), face))
                            continue;
                    } else if (blockTo != null && blockFrom != null) {
                        if (blockFrom.registry().collisionShape().isOccluded(blockTo.registry().collisionShape(), face.getOppositeFace()))
                            continue;
                    }

                    if (lightEmission > 0) {
                        final int index = posTo | (lightEmission << 12);
                        lightSources.enqueue((short) index);
                    }
                }
            }
        }

        return lightSources;
    }

    @Override
    public Set<Point> calculateInternal(Instance instance, int chunkX, int sectionY, int chunkZ, Palette blockPalette) {
        this.isValidBorders.set(true);

        Chunk chunk = instance.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return Set.of();
        }


        // Update single section with base lighting changes
        ShortArrayFIFOQueue queue = buildInternalQueue(blockPalette);

        this.content = LightCompute.compute(blockPalette, queue);

        // Propagate changes to neighbors and self
        Set<Point> toUpdate = new HashSet<>();
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Chunk neighborChunk = instance.getChunk(chunkX + i, chunkZ + j);
                if (neighborChunk == null) continue;

                for (int k = -1; k <= 1; k++) {
                    Vec neighborPos = new Vec(chunkX + i, sectionY + k, chunkZ + j);

                    if (neighborPos.blockY() >= neighborChunk.getMinSection() && neighborPos.blockY() < neighborChunk.getMaxSection()) {
                        if (neighborChunk.getSection(neighborPos.blockY()).blockLight() instanceof BlockLight blockLight)
                            blockLight.contentPropagation = null;
                    }
                }
            }
        }
        toUpdate.add(new Vec(chunk.getChunkX(), sectionY, chunk.getChunkZ()));
        return toUpdate;
    }

    @Override
    public void invalidate() {
        this.needsSend.set(true);
        this.isValidBorders.set(false);
        this.contentPropagation = null;
    }

    @Override
    public boolean requiresUpdate() {
        return !isValidBorders.get();
    }

    @Override
    @ApiStatus.Internal
    public void set(byte[] copyArray) {
        this.content = copyArray.clone();
        this.contentPropagation = this.content;
        this.isValidBorders.set(true);
        this.needsSend.set(true);
    }

    @Override
    public boolean requiresSend() {
        return needsSend.getAndSet(false);
    }

    @Override
    public byte[] array() {
        if (content == null) return new byte[0];
        if (contentPropagation == null) return content;
        var res = LightCompute.bake(contentPropagation, content);
        if (res == EMPTY_CONTENT) return new byte[0];
        return res;
    }

    @Override
    public Set<Point> calculateExternal(Instance instance, Chunk chunk, int sectionY, Palette blockPalette) {
        if (!isValidBorders.get()) {
            return Set.of();
        }

        final Point[] neighbors = Light.getNeighbors(chunk, sectionY);
        ShortArrayFIFOQueue queue = buildExternalQueue(instance, blockPalette, neighbors, content);

        final byte[] contentPropagationTemp = LightCompute.compute(blockPalette, queue);

        this.contentPropagationSwap = LightCompute.bake(contentPropagationSwap, contentPropagationTemp);

        // Propagate changes to neighbors and self
        Set<Point> toUpdate = new HashSet<>();
        for (int i = 0; i < neighbors.length; i++) {
            final Point neighbor = neighbors[i];
            if (neighbor == null) continue;
            final BlockFace face = BlockFace.values()[i];
            if (!LightCompute.compareBorders(content, contentPropagation, contentPropagationTemp, face)) {
                toUpdate.add(neighbor);
            }
        }
        return toUpdate;
    }

    @Override
    public int getLevel(int x, int y, int z) {
        if (content == null) return 0;
        int index = x | (z << 4) | (y << 8);
        if (contentPropagation == null) return LightCompute.getLight(content, index);
        return Math.max(LightCompute.getLight(contentPropagation, index), LightCompute.getLight(content, index));
    }
}
