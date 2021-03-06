/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.BuildCraftAPI;
import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IAreaProvider;
import buildcraft.api.mj.MjAPI;
import buildcraft.api.mj.MjBattery;
import buildcraft.api.mj.MjCapabilityHelper;
import buildcraft.api.tiles.IDebuggable;

import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.chunkload.ChunkLoaderManager;
import buildcraft.lib.chunkload.IChunkLoadingTile;
import buildcraft.lib.inventory.AutomaticProvidingTransactor;
import buildcraft.lib.inventory.TransactorEntityItem;
import buildcraft.lib.inventory.filter.StackFilter;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.PositionUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.AxisOrder;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.BoxIterator;
import buildcraft.lib.misc.data.EnumAxisOrder;
import buildcraft.lib.mj.MjBatteryReceiver;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.world.WorldEventListenerAdapter;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.BCBuildersEventDist;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.VolumeConnection;
import buildcraft.core.marker.VolumeSubCache;
import buildcraft.core.tile.TileMarkerVolume;

public class TileQuarry extends TileBC_Neptune implements ITickable, IDebuggable, IChunkLoadingTile {
    private static final long MAX_POWER_PER_TICK = 64 * MjAPI.MJ;
    private static final ResourceLocation ADVANCEMENT_COMPLETE =
        new ResourceLocation("buildcraftbuilders:diggy_diggy_hole");

    private final MjBattery battery = new MjBattery(16000 * MjAPI.MJ);
    public final Box frameBox = new Box();
    private final Box miningBox = new Box();
    private BoxIterator boxIterator;
    public final List<BlockPos> framePoses = new ArrayList<>();
    private int frameBoxPosesCount = 0;
    private final LinkedList<BlockPos> toCheck = new LinkedList<>();
    private final Set<BlockPos> firstCheckedPoses = new HashSet<>();
    private boolean firstChecked = false;
    private final Set<BlockPos> frameBreakBlockPoses =
        new TreeSet<>(Comparator.<BlockPos> comparingDouble(pos::distanceSq));
    private final Set<BlockPos> framePlaceFramePoses = new HashSet<>();
    public Task currentTask = null;
    public Vec3d drillPos;
    public Vec3d clientDrillPos;
    public Vec3d prevClientDrillPos;
    private long debugPowerRate = 0;
    private List<AxisAlignedBB> collisionboxes = null;
    private final IWorldEventListener worldEventListener = new WorldEventListenerAdapter() {
        @Override
        public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState,
            int flags) {
            if (frameBox.isInitialized() && miningBox.isInitialized()) {
                if (frameBox.contains(pos)) {
                    check(pos);
                } else if (miningBox.contains(pos)) {
                    if (boxIterator != null
                        && (boxIterator.getCurrent() == null || pos.getY() >= boxIterator.getCurrent().getY())
                        && !canMoveThrough(pos) && canMoveTo(pos)) {
                        BoxIterator tempBoxIterator = createBoxIterator();
                        while (!Objects.equals(tempBoxIterator.getCurrent(), pos)) {
                            if (tempBoxIterator.advance() == null) {
                                return;
                            }
                            if (Objects.equals(tempBoxIterator.getCurrent(), boxIterator.getCurrent())) {
                                return;
                            }
                        }
                        boxIterator = tempBoxIterator;
                    }
                }
            }
        }
    };

    public TileQuarry() {
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
        caps.addCapabilityInstance(CapUtil.CAP_ITEM_TRANSACTOR, AutomaticProvidingTransactor.INSTANCE,
            EnumPipePart.VALUES);
    }

    @Nonnull
    private BoxIterator createBoxIterator() {
        return new BoxIterator(miningBox, AxisOrder.getFor(EnumAxisOrder.XZY, AxisOrder.Inversion.NNN), true);
    }

    private List<BlockPos> getFramePoses(IBlockState state) {
        List<BlockPos> framePositions = PositionUtil.getAllOnEdge(frameBox.min(), frameBox.max());

        framePositions = new ArrayList<>(new HashSet<>(framePositions));

        framePositions.sort(Comparator.comparing(blockPos -> Math.pow(blockPos.getX() - pos.getX(), 2)
            + Math.pow(blockPos.getY() - pos.getY(), 2) + Math.pow(blockPos.getZ() - pos.getZ(), 2)));

        List<BlockPos> framePositionsSorted = new ArrayList<>();
        EnumFacing facing = state.getValue(BlockBCBase_Neptune.PROP_FACING).getOpposite();
        framePositionsSorted.add(pos.offset(facing));
        while (framePositions.size() != framePositionsSorted.size()) {
            for (BlockPos blockPos : framePositions) {
                if (!framePositionsSorted.contains(blockPos)) {
                    if (framePositionsSorted.stream()
                        .flatMap(blockPosLocal -> Arrays.stream(EnumFacing.VALUES).map(blockPosLocal::offset))
                        .anyMatch(Predicate.isEqual(blockPos))) {
                        framePositionsSorted.add(blockPos);
                        break;
                    }
                }
            }
        }
        return framePositionsSorted;
    }

    private boolean shouldBeFrame(BlockPos p) {
        return frameBox.isOnEdge(p);
    }

    @Override
    public void onPlacedBy(EntityLivingBase placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (placer.world.isRemote) {
            return;
        }
        EnumFacing facing = world.getBlockState(pos).getValue(BlockBCBase_Neptune.PROP_FACING);
        BlockPos areaPos = pos.offset(facing.getOpposite());
        TileEntity tile = world.getTileEntity(areaPos);
        BlockPos min, max;
        if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            min = provider.min();
            max = provider.max();
            provider.removeFromWorld();
        } else {
            min = null;
            max = null;
            VolumeSubCache cache = VolumeCache.INSTANCE.getSubCache(getWorld());
            for (BlockPos markerPos : cache.getAllMarkers()) {
                TileMarkerVolume marker = (TileMarkerVolume) cache.getMarker(markerPos);
                if (marker == null) {
                    continue;
                }
                VolumeConnection connection = marker.getCurrentConnection();
                if (connection == null) {
                    continue;
                }
                Box volBox = connection.getBox();
                Box box2 = new Box();
                box2.initialize(volBox);
                if (!box2.isInitialized()) {
                    continue;
                }
                if (pos.getY() != box2.min().getY()) {
                    continue;
                }
                if (box2.contains(pos)) {
                    continue;
                }
                box2.expand(1);
                box2.setMin(box2.min().up());
                if (box2.isOnEdge(pos)) {
                    min = volBox.min();
                    max = volBox.max();
                    marker.removeFromWorld();
                    break;
                }
            }
        }

        if (min == null || max == null) {
            miningBox.reset();
            frameBox.reset();
            switch (facing.getOpposite()) {
                case DOWN:
                case UP:
                default:
                case EAST: // +X
                    min = pos.add(1, 0, -5);
                    max = pos.add(11, 4, 5);
                    break;
                case WEST: // -X
                    min = pos.add(-11, 0, -5);
                    max = pos.add(-1, 4, 5);
                    break;
                case SOUTH: // +Z
                    min = pos.add(-5, 0, 1);
                    max = pos.add(5, 4, 11);
                    break;
                case NORTH: // -Z
                    min = pos.add(-5, 0, -11);
                    max = pos.add(5, 4, -1);
                    break;
            }
        }
        if (max.getY() - min.getY() < 4) {
            max = new BlockPos(max.getX(), min.getY() + 4, max.getZ());
        }
        frameBox.reset();
        frameBox.setMin(min);
        frameBox.setMax(max);
        miningBox.reset();
        miningBox.setMin(new BlockPos(min.getX() + 1, 0, min.getZ() + 1));
        miningBox.setMax(new BlockPos(max.getX() - 1, max.getY() - 1, max.getZ() - 1));
        updatePoses();
    }

    private boolean canMine(BlockPos blockPos) {
        if (world.getBlockState(blockPos).getBlockHardness(world, blockPos) < 0) {
            return false;
        }
        Fluid fluid = BlockUtil.getFluidWithFlowing(world, blockPos);
        return fluid == null || fluid.getViscosity() <= 1000;
    }

    private boolean canMoveThrough(BlockPos blockPos) {
        if (world.isAirBlock(blockPos)) {
            return true;
        }
        Fluid fluid = BlockUtil.getFluidWithFlowing(world, blockPos);
        return fluid != null && fluid.getViscosity() <= 1000;
    }

    private boolean canMoveTo(BlockPos blockPos) {
        for (int y = miningBox.max().getY(); y > blockPos.getY(); y--) {
            if (!canMoveThrough(VecUtil.replaceValue(blockPos, Axis.Y, y))) {
                return false;
            }
        }
        return true;
    }

    private void check(BlockPos blockPos) {
        frameBreakBlockPoses.remove(blockPos);
        framePlaceFramePoses.remove(blockPos);
        if (shouldBeFrame(blockPos)) {
            if (world.getBlockState(blockPos).getBlock() != BCBuildersBlocks.frame) {
                if (!world.isAirBlock(blockPos)) {
                    frameBreakBlockPoses.add(blockPos);
                } else {
                    framePlaceFramePoses.add(blockPos);
                }
            }
        } else {
            if (!world.isAirBlock(blockPos)) {
                frameBreakBlockPoses.add(blockPos);
            }
        }
        if (!firstChecked) {
            firstCheckedPoses.add(blockPos);
            if (firstCheckedPoses.size() >= frameBoxPosesCount) {
                firstChecked = true;
            }
        }
    }

    @Override
    public void onLoad() {
        if (!world.isRemote) {
            updatePoses();
        }
    }

    @Override
    public void validate() {
        super.validate();
        BCBuildersEventDist.INSTANCE.validateQuarry(this);
        if (!world.isRemote) {
            world.addEventListener(worldEventListener);
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        BCBuildersEventDist.INSTANCE.invalidateQuarry(this);
        if (!world.isRemote) {
            world.removeEventListener(worldEventListener);
            ChunkLoaderManager.releaseChunksFor(this);
        }
    }

    @Nullable
    @Override
    public LoadType getLoadType() {
        return LoadType.HARD;
    }

    @Nullable
    @Override
    public Collection<ChunkPos> getChunksToLoad() {
        if (!miningBox.isInitialized()) {
            return null;
        }
        ArrayList<ChunkPos> list = new ArrayList<>();
        int minX = miningBox.min().getX() >> 4;
        int minZ = miningBox.min().getZ() >> 4;
        int maxX = miningBox.max().getX() >> 4;
        int maxZ = miningBox.max().getZ() >> 4;
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                list.add(new ChunkPos(x, z));
            }
        }
        return list;
    }

    private void updatePoses() {
        framePoses.clear();
        frameBoxPosesCount = 0;
        toCheck.clear();
        firstCheckedPoses.clear();
        firstChecked = false;
        frameBreakBlockPoses.clear();
        framePlaceFramePoses.clear();
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() == BCBuildersBlocks.quarry && frameBox.isInitialized()) {
            List<BlockPos> blocksInArea = frameBox.getBlocksInArea();
            frameBoxPosesCount = blocksInArea.size();
            toCheck.addAll(blocksInArea);
            framePoses.addAll(getFramePoses(state));
            ChunkLoaderManager.loadChunksForTile(this);
        }
    }

    @Override
    public void update() {
        if (world.isRemote) {
            prevClientDrillPos = clientDrillPos;
            clientDrillPos = drillPos;
            if (currentTask != null) {
                currentTask.clientTick();
            }
            return;
        }

        if (!frameBox.isInitialized() || !miningBox.isInitialized()) {
            return;
        }

        if (!toCheck.isEmpty()) {
            for (int i = 0; i < (firstChecked ? 10 : 50); i++) {
                BlockPos blockPos = toCheck.pollFirst();
                check(blockPos);
                toCheck.addLast(blockPos);
            }
        }

        if (currentTask != null) {
            long max = MAX_POWER_PER_TICK;
            max *= battery.getStored() + max;
            max /= battery.getCapacity() / 2;
            max = Math.min(max, MAX_POWER_PER_TICK);
            debugPowerRate = max;
            long power = battery.extractPower(0, max);
            if (currentTask.addPower(power)) {
                currentTask = null;
            }
            sendNetworkUpdate(NET_RENDER_DATA);
            return;
        }

        if (!firstChecked) {
            return;
        }

        if (!frameBreakBlockPoses.isEmpty()) {
            BlockPos blockPos = frameBreakBlockPoses.iterator().next();
            if (canMine(blockPos)) {
                drillPos = null;
                currentTask = new TaskBreakBlock(blockPos);
                sendNetworkUpdate(NET_RENDER_DATA);
            }
            check(blockPos);
            return;
        }

        if (!framePlaceFramePoses.isEmpty()) {
            for (BlockPos blockPos : framePoses) {
                if (!framePlaceFramePoses.contains(blockPos)) {
                    continue;
                }
                check(blockPos);
                if (!framePlaceFramePoses.contains(blockPos)) {
                    continue;
                }
                drillPos = null;
                currentTask = new TaskAddFrame(blockPos);
                sendNetworkUpdate(NET_RENDER_DATA);
                return;
            }
        }

        if (boxIterator == null || drillPos == null) {
            boxIterator = createBoxIterator();
            while (canMoveThrough(boxIterator.getCurrent()) || !canMine(boxIterator.getCurrent())
                || !canMoveTo(boxIterator.getCurrent())) {
                if (boxIterator.advance() == null) {
                    break;
                }
            }
            drillPos = new Vec3d(miningBox.closestInsideTo(pos));
        }

        if (boxIterator != null && boxIterator.hasNext()) {
            while (canMoveThrough(boxIterator.getCurrent()) || !canMine(boxIterator.getCurrent())
                || !canMoveTo(boxIterator.getCurrent())) {
                if (boxIterator.advance() == null) {
                    break;
                }
            }

            if (boxIterator.hasNext()) {
                boolean found = false;

                if (drillPos.squareDistanceTo(new Vec3d(boxIterator.getCurrent())) >= 1) {
                    currentTask = new TaskMoveDrill(drillPos, new Vec3d(boxIterator.getCurrent()));
                    found = true;
                } else if (canMine(boxIterator.getCurrent())) {
                    currentTask = new TaskBreakBlock(boxIterator.getCurrent());
                    found = true;
                }

                if (found) {
                    sendNetworkUpdate(NET_RENDER_DATA);
                } else {
                    AxisAlignedBB box = miningBox.getBoundingBox();
                    if (box.maxX - box.minX == 63 && box.maxZ - box.minZ == 63) {
                        AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT_COMPLETE);
                    }
                }
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("box", miningBox.writeToNBT());
        nbt.setTag("frame", frameBox.writeToNBT());
        if (boxIterator != null) {
            nbt.setTag("boxIterator", boxIterator.writeToNbt());
        }
        nbt.setTag("battery", battery.serializeNBT());
        if (currentTask != null) {
            nbt.setByte("currentTaskId",
                (byte) Arrays.stream(EnumTaskType.values()).filter(type -> type.clazz == currentTask.getClass())
                    .findFirst().orElseThrow(IllegalStateException::new).ordinal());
            nbt.setTag("currentTaskData", currentTask.serializeNBT());
        }
        if (drillPos != null) {
            nbt.setTag("drillPos", NBTUtilBC.writeVec3d(drillPos));
        }
        nbt.setBoolean("firstChecked", firstChecked);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        miningBox.initialize(nbt.getCompoundTag("box"));
        frameBox.initialize(nbt.getCompoundTag("frame"));
        boxIterator = BoxIterator.readFromNbt(nbt.getCompoundTag("boxIterator"));
        battery.deserializeNBT(nbt.getCompoundTag("battery"));
        if (nbt.hasKey("currentTask")) {
            currentTask = EnumTaskType.values()[(int) nbt.getByte("currentTaskId")].supplier.apply(this);
            currentTask.readFromNBT(nbt.getCompoundTag("currentTaskData"));
        } else {
            currentTask = null;
        }
        drillPos = NBTUtilBC.readVec3d(nbt.getTag("drillPos"));
        firstChecked = nbt.getBoolean("firstChecked");
    }

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                frameBox.writeData(buffer);
                miningBox.writeData(buffer);
                buffer.writeBoolean(drillPos != null);
                if (drillPos != null) {
                    MessageUtil.writeVec3d(buffer, drillPos);
                }
                buffer.writeBoolean(currentTask != null);
                if (currentTask != null) {
                    buffer.writeByte(
                        (byte) Arrays.stream(EnumTaskType.values()).filter(type -> type.clazz == currentTask.getClass())
                            .findFirst().orElseThrow(IllegalStateException::new).ordinal());
                    for (int i = 0; i < 2; i++) {
                        currentTask.toBytes(buffer);
                    }
                }
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                frameBox.readData(buffer);
                miningBox.readData(buffer);
                if (buffer.readBoolean()) {
                    drillPos = MessageUtil.readVec3d(buffer);
                } else {
                    drillPos = null;
                }
                if (buffer.readBoolean()) {
                    int taskId = buffer.readByte();
                    Task task = EnumTaskType.values()[taskId].supplier.apply(this);
                    task.fromBytes(buffer);
                    if (currentTask == null || !currentTask.equals(task)) {
                        currentTask = task;
                        Task tempTask = EnumTaskType.values()[taskId].supplier.apply(this);
                        tempTask.fromBytes(buffer);
                    } else {
                        currentTask.fromBytes(buffer);
                    }
                } else {
                    currentTask = null;
                }
            }
        }
    }

    public Iterable<AxisAlignedBB> getCollisionBoxes() {
        if (!frameBox.isInitialized() || drillPos == null) {
            return ImmutableList.of();
        }
        if (collisionboxes == null) {
            collisionboxes = new ArrayList<>(3);
            Vec3d min = VecUtil.convertCenter(frameBox.min());
            Vec3d max = VecUtil.convertCenter(frameBox.max());
            min = VecUtil.replaceValue(min, Axis.Y, max.y);

            Vec3d minXAdj = VecUtil.replaceValue(min, Axis.X, drillPos.x + 0.5);
            Vec3d maxXAdj = VecUtil.replaceValue(max, Axis.X, drillPos.x + 0.5);
            collisionboxes.add(BoundingBoxUtil.makeFrom(minXAdj, maxXAdj, 0.25));

            Vec3d minZAdj = VecUtil.replaceValue(min, Axis.Z, drillPos.z + 0.5);
            Vec3d maxZAdj = VecUtil.replaceValue(max, Axis.Z, drillPos.z + 0.5);
            collisionboxes.add(BoundingBoxUtil.makeFrom(minZAdj, maxZAdj, 0.25));

            Vec3d realDrillPos = drillPos.addVector(0.5, 0, 0.5);
            Vec3d minYAdj = realDrillPos;
            Vec3d maxYAdj = VecUtil.replaceValue(realDrillPos, Axis.Y, max.y);
            collisionboxes.add(BoundingBoxUtil.makeFrom(minYAdj, maxYAdj, 0.25));
        }
        return collisionboxes;
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("battery = " + battery.getDebugString());
        left.add("rate = " + LocaleUtil.localizeMjFlow(debugPowerRate));
        left.add("frameBox");
        left.add(" - min = " + frameBox.min());
        left.add(" - max = " + frameBox.max());
        left.add("miningBox:");
        left.add(" - min = " + miningBox.min());
        left.add(" - max = " + miningBox.max());

        BoxIterator iter = boxIterator;
        left.add("current = " + (iter == null ? "null" : iter.getCurrent()));

        Task task = currentTask;
        if (task != null) {
            left.add("task:");
            left.add(" - class = " + task.getClass().getName());
            left.add(" - power = " + LocaleUtil.localizeMj(task.getPower()));
            left.add(" - target = " + LocaleUtil.localizeMj(task.getTarget()));
        } else {
            left.add("task = null");
        }
        left.add("drill = " + drillPos);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(pos, miningBox);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }

    private enum EnumTaskType {
        BREAK_BLOCK(TaskBreakBlock.class, quarry -> quarry.new TaskBreakBlock()),
        ADD_FRAME(TaskAddFrame.class, quarry -> quarry.new TaskAddFrame()),
        MOVE_DRILL(TaskMoveDrill.class, quarry -> quarry.new TaskMoveDrill());

        public final Class<? extends Task> clazz;
        public final Function<TileQuarry, Task> supplier;

        EnumTaskType(Class<? extends Task> clazz, Function<TileQuarry, Task> supplier) {
            this.clazz = clazz;
            this.supplier = supplier;
        }
    }

    private abstract class Task {
        protected long power;
        public long clientPower;
        public long prevClientPower;

        public NBTTagCompound serializeNBT() {
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setLong("power", power);
            return nbt;
        }

        public void readFromNBT(NBTTagCompound nbt) {
            power = nbt.getLong("power");
        }

        public void toBytes(PacketBufferBC buffer) {
            buffer.writeLong(power);
        }

        public void fromBytes(PacketBufferBC buffer) {
            power = buffer.readLong();
        }

        public void clientTick() {
            prevClientPower = clientPower;
            clientPower = power;
        }

        public abstract long getTarget();

        /** @return {@code true} if this task has been completed, or cancelled. */
        protected abstract boolean onReceivePower();

        protected abstract boolean finish();

        public final long getPower() {
            return power;
        }

        /** @return {@code true} if this task has been completed, or cancelled. */
        public final boolean addPower(long microJoules) {
            power += microJoules;
            if (power >= getTarget()) {
                if (!finish()) {
                    battery.addPower(Math.min(power, battery.getCapacity() - battery.getStored()), false);
                }
                return true;
            } else {
                return onReceivePower();
            }
        }
    }

    public class TaskBreakBlock extends Task {
        public BlockPos breakPos = BlockPos.ORIGIN;

        public TaskBreakBlock() {}

        public TaskBreakBlock(BlockPos pos) {
            this.breakPos = pos;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            NBTTagCompound nbt = super.serializeNBT();
            nbt.setTag("breakPos", NBTUtilBC.writeBlockPos(breakPos));
            return nbt;
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            super.readFromNBT(nbt);
            breakPos = NBTUtilBC.readBlockPos(nbt.getTag("breakPos"));
            if (breakPos == null) {
                // We failed to read, abort
                currentTask = null;
            }
        }

        @Override
        public void toBytes(PacketBufferBC buffer) {
            super.toBytes(buffer);
            buffer.writeBlockPos(breakPos);
        }

        @Override
        public void fromBytes(PacketBufferBC buffer) {
            super.fromBytes(buffer);
            breakPos = buffer.readBlockPos();
        }

        @Override
        public long getTarget() {
            return BlockUtil.computeBlockBreakPower(world, breakPos);
        }

        @Override
        protected boolean onReceivePower() {
            if (!world.isAirBlock(breakPos)) {
                world.sendBlockBreakProgress(breakPos.hashCode(), breakPos, (int) (power * 9 / getTarget()));
                return false;
            } else {
                return true;
            }
        }

        @Override
        protected boolean finish() {
            EntityPlayer fake = BuildCraftAPI.fakePlayerProvider.getFakePlayer((WorldServer) world, getOwner(), pos);

            IBlockState state = world.getBlockState(breakPos);
            if (!canMine(breakPos)) {
                return true;
            }

            BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(world, breakPos, state, fake);
            MinecraftForge.EVENT_BUS.post(breakEvent);
            if (!breakEvent.isCanceled()) {
                // The drill pos will be null if we are making the frame: this is when we want to destroy the block, not
                // drop its contents
                world.sendBlockBreakProgress(breakPos.hashCode(), breakPos, -1);
                if (drillPos != null) {
                    world.destroyBlock(breakPos, true);
                    for (EntityItem entity : world.getEntitiesWithinAABB(EntityItem.class,
                        new AxisAlignedBB(breakPos).grow(1))) {
                        TransactorEntityItem transactor = new TransactorEntityItem(entity);
                        ItemStack stack;
                        while (!(stack = transactor.extract(StackFilter.ALL, 0, Integer.MAX_VALUE, false)).isEmpty()) {
                            InventoryUtil.addToBestAcceptor(world, pos, null, stack);
                        }
                    }
                } else {
                    world.destroyBlock(breakPos, false);
                }
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o
                || !(o == null || getClass() != o.getClass()) && breakPos.equals(((TaskBreakBlock) o).breakPos);

        }
    }

    public class TaskAddFrame extends Task {
        public BlockPos framePos = BlockPos.ORIGIN;

        public TaskAddFrame() {}

        public TaskAddFrame(BlockPos framePos) {
            this.framePos = framePos;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            NBTTagCompound nbt = super.serializeNBT();
            nbt.setTag("framePos", NBTUtilBC.writeBlockPos(framePos));
            return nbt;
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            super.readFromNBT(nbt);
            framePos = NBTUtilBC.readBlockPos(nbt.getTag("framePos"));
            if (framePos == null) {
                // We failed to read, abort
                currentTask = null;
            }
        }

        @Override
        public void toBytes(PacketBufferBC buffer) {
            super.toBytes(buffer);
            buffer.writeBlockPos(framePos);
        }

        @Override
        public void fromBytes(PacketBufferBC buffer) {
            super.fromBytes(buffer);
            framePos = buffer.readBlockPos();
        }

        @Override
        public long getTarget() {
            return 24 * MjAPI.MJ;
        }

        @Override
        protected boolean onReceivePower() {
            return !world.isAirBlock(framePos);
        }

        @Override
        protected boolean finish() {
            if (world.isAirBlock(framePos)) {
                world.setBlockState(framePos, BCBuildersBlocks.frame.getDefaultState());
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return this == o
                || !(o == null || getClass() != o.getClass()) && framePos.equals(((TaskAddFrame) o).framePos);

        }
    }

    private class TaskMoveDrill extends Task {
        public Vec3d from = Vec3d.ZERO;
        public Vec3d to = Vec3d.ZERO;

        public TaskMoveDrill() {}

        public TaskMoveDrill(Vec3d from, Vec3d to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            NBTTagCompound nbt = super.serializeNBT();
            nbt.setTag("from", NBTUtilBC.writeVec3d(from));
            nbt.setTag("to", NBTUtilBC.writeVec3d(to));
            return nbt;
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            super.readFromNBT(nbt);
            from = NBTUtilBC.readVec3d(nbt.getTag("from"));
            to = NBTUtilBC.readVec3d(nbt.getTag("to"));
            if (from == null || to == null) {
                // We failed to read. Abort.
                currentTask = null;
            }
        }

        @Override
        public void toBytes(PacketBufferBC buffer) {
            super.toBytes(buffer);
            MessageUtil.writeVec3d(buffer, from);
            MessageUtil.writeVec3d(buffer, to);
        }

        @Override
        public void fromBytes(PacketBufferBC buffer) {
            super.fromBytes(buffer);
            from = MessageUtil.readVec3d(buffer);
            to = MessageUtil.readVec3d(buffer);
        }

        @Override
        public long getTarget() {
            return (long) (from.distanceTo(to) * 20 * MjAPI.MJ);
        }

        @Override
        protected boolean onReceivePower() {
            // Vec3d oldDrillPos = drillPos;
            drillPos = from.scale(1 - power / (double) getTarget()).add(to.scale(power / (double) getTarget()));
            // moveEntities(oldDrillPos);
            return false;
        }

        @Override
        protected boolean finish() {
            // Vec3d oldDrillPos = drillPos;
            drillPos = to;
            // moveEntities(oldDrillPos);
            collisionboxes = null;
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || !(o == null || getClass() != o.getClass()) && from.equals(((TaskMoveDrill) o).from)
                && to.equals(((TaskMoveDrill) o).to);

        }
    }
}
