package minegame159.meteorclient.modules.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import me.zero.alpine.listener.EventHandler;
import me.zero.alpine.listener.Listener;
import minegame159.meteorclient.events.ChunkDataEvent;
import minegame159.meteorclient.events.EventStore;
import minegame159.meteorclient.events.RenderEvent;
import minegame159.meteorclient.events.packets.ReceivePacketEvent;
import minegame159.meteorclient.modules.Category;
import minegame159.meteorclient.modules.ToggleModule;
import minegame159.meteorclient.rendering.ShapeBuilder;
import minegame159.meteorclient.settings.*;
import minegame159.meteorclient.utils.Color;
import minegame159.meteorclient.utils.MeteorTaskExecutor;
import minegame159.meteorclient.utils.Pool;
import minegame159.meteorclient.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;

public class Search extends ToggleModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTracers = settings.createGroup("Tracers", "tracers", "Draw lines to the blocks.", false);

    private final Long2ObjectArrayMap<MyChunk> chunks = new Long2ObjectArrayMap<>();

    // General

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks")
            .description("Blocks to search for.")
            .defaultValue(new ArrayList<>(0))
            .onChanged(blocks1 -> {
                if (Utils.canUpdate() && isActive()) {
                    synchronized (chunks) {
                        for (MyChunk chunk : chunks.values()) chunk.dispose();
                        chunks.clear();
                    }

                    searchViewDistance();
                }
            })
            .build()
    );

    private final Setting<Color> color = sgGeneral.add(new ColorSetting.Builder()
            .name("color")
            .description("Color.")
            .defaultValue(new Color(0, 255, 200))
            .build()
    );

    private final Setting<Boolean> fullBlock = sgGeneral.add(new BoolSetting.Builder()
            .name("full-block")
            .description("Outlines are rendered as full blocks.")
            .defaultValue(false)
            .build()
    );

    // Tracers

    private final Setting<Color> tracersColor = sgTracers.add(new ColorSetting.Builder()
            .name("tracers-color")
            .description("Tracers color.")
            .defaultValue(new Color(225, 225, 225))
            .build()
    );

    private final Pool<BlockPos.Mutable> blockPosPool = new Pool<>(BlockPos.Mutable::new);
    private final LongList toRemove = new LongArrayList();

    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();
    private Vec3d vec1 = new Vec3d(0, 0, 0);

    public Search() {
        super(Category.Render, "search", "Searches for specified blocks.");
    }

    @Override
    public void onActivate() {
        MeteorTaskExecutor.start();

        searchViewDistance();
    }

    @Override
    public void onDeactivate() {
        MeteorTaskExecutor.stop();

        for (MyChunk chunk : chunks.values()) chunk.dispose();
        chunks.clear();
    }

    private void searchViewDistance() {
        int viewDist = mc.options.viewDistance;
        for (int x = mc.player.chunkX - viewDist; x <= mc.player.chunkX + viewDist; x++) {
            for (int z = mc.player.chunkZ - viewDist; z <= mc.player.chunkZ + viewDist; z++) {
                if (mc.world.getChunkManager().isChunkLoaded(x, z)) searchChunk(mc.world.getChunk(x, z), null);
            }
        }
    }

    @EventHandler
    private final Listener<ChunkDataEvent> onChunkData = new Listener<>(event -> searchChunk(event.chunk, event));

    private void searchChunk(Chunk chunk, ChunkDataEvent event) {
        MeteorTaskExecutor.execute(() -> {
            MyChunk myChunk = new MyChunk(chunk.getPos().x, chunk.getPos().z);

            for (int x = chunk.getPos().getStartX(); x <= chunk.getPos().getEndX(); x++) {
                for (int z = chunk.getPos().getStartZ(); z <= chunk.getPos().getEndZ(); z++) {
                    int height = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - chunk.getPos().getStartX(), z - chunk.getPos().getStartZ());
                    for (int y = 0; y < height; y++) {
                        blockPos.set(x, y, z);
                        BlockState bs = chunk.getBlockState(blockPos);
                        if (blocks.get().contains(bs.getBlock())) myChunk.add(blockPos, false);
                    }
                }
            }

            synchronized (chunks) {
                if (myChunk.blockPoss.size() > 0) chunks.put(chunk.getPos().toLong(), myChunk);
            }

            if (event != null) EventStore.returnChunkDataEvent(event);
        });
    }

    @EventHandler
    private final Listener<ReceivePacketEvent> onReceivePacket = new Listener<>(event -> {
        if (!(event.packet instanceof BlockUpdateS2CPacket)) return;

        BlockPos blockPos = ((BlockUpdateS2CPacket) event.packet).getPos();
        BlockState bs = ((BlockUpdateS2CPacket) event.packet).getState();

        MeteorTaskExecutor.execute(() -> {
            int chunkX = blockPos.getX() >> 4;
            int chunkZ = blockPos.getZ() >> 4;
            long key = ChunkPos.toLong(chunkX, chunkZ);

            synchronized (chunks) {
                if (blocks.get().contains(bs.getBlock())) {
                    chunks.computeIfAbsent(key, aLong -> new MyChunk(chunkX, chunkZ)).add(blockPos, true);
                } else {
                    MyChunk chunk = chunks.get(key);
                    if (chunk != null) chunk.remove(blockPos);
                }
            }
        });
    });

    @EventHandler
    private final Listener<RenderEvent> onRender = new Listener<>(event -> {
        vec1 = new Vec3d(0, 0, 1)
                .rotateX(-(float) Math.toRadians(mc.cameraEntity.pitch))
                .rotateY(-(float) Math.toRadians(mc.cameraEntity.yaw))
                .add(mc.cameraEntity.getPos());

        synchronized (chunks) {
            toRemove.clear();
            
            for (long key : chunks.keySet()) {
                MyChunk chunk = chunks.get(key);
                if (chunk.shouldBeDeleted()) toRemove.add(key);
                else chunk.render(event);
            }
            
            for (long key : toRemove) {
                chunks.remove(key);
            }
        }
    });

    private class MyChunk {
        private final int x, z;
        private final List<BlockPos.Mutable> blockPoss = new ArrayList<>();

        public MyChunk(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public void add(BlockPos blockPos, boolean checkForDuplicates) {
            if (checkForDuplicates) {
                for (BlockPos.Mutable bp : blockPoss) {
                    if (bp.getX() == blockPos.getX() && bp.getY() == blockPos.getY() && bp.getZ() == blockPos.getZ()) return;
                }
            }

            BlockPos.Mutable pos = blockPosPool.get();
            pos.set(blockPos);
            blockPoss.add(pos);
        }

        public void remove(BlockPos blockPos) {
            for (int i = 0; i < blockPoss.size(); i++) {
                BlockPos.Mutable bp = blockPoss.get(i);

                if (bp.getX() == blockPos.getX() && bp.getY() == blockPos.getY() && bp.getZ() == blockPos.getZ()) {
                    blockPoss.remove(i);
                    return;
                }
            }
        }

        public boolean shouldBeDeleted() {
            int viewDist = mc.options.viewDistance + 1;
            return x > mc.player.chunkX + viewDist || x < mc.player.chunkX - viewDist || z > mc.player.chunkZ + viewDist || z < mc.player.chunkZ - viewDist;
        }

        public void render(RenderEvent event) {
            for (BlockPos.Mutable blockPos : blockPoss) {
                if (fullBlock.get()) {
                    ShapeBuilder.blockEdges(blockPos, color.get());
                } else {
                    VoxelShape shape = mc.world.getBlockState(blockPos).getOutlineShape(mc.world, blockPos);
                    if (shape.isEmpty()) {
                        ShapeBuilder.blockEdges(blockPos, color.get());
                        continue;
                    }

                    Box box = shape.getBoundingBox();
                    ShapeBuilder.boxEdges(blockPos.getX() + box.x1, blockPos.getY() + box.y1, blockPos.getZ() + box.z1, blockPos.getX() + box.x2, blockPos.getY() + box.y2, blockPos.getZ() + box.z2, color.get());
                }

                // Tracers
                if (sgTracers.isEnabled()) {
                    ShapeBuilder.line(vec1.x - (mc.cameraEntity.x - event.offsetX), vec1.y - (mc.cameraEntity.y - event.offsetY), vec1.z - (mc.cameraEntity.z - event.offsetZ), blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5f, tracersColor.get());
                }
            }
        }

        public void dispose() {
            for (BlockPos.Mutable blockPos : blockPoss) blockPosPool.free(blockPos);
            blockPoss.clear();
        }
    }
}