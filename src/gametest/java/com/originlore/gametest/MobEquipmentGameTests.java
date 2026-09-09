package com.originlore.gametest;

import com.originlore.ItemComponentManager;
import com.originlore.Originlore;
import com.originlore.config.ItemComponentConfig;
import com.originlore.config.ItemComponentConfig.ItemEntry;
import com.originlore.config.ItemComponentConfig.SourceRule;
import com.originlore.config.ItemComponentConfig.Variant;
import com.originlore.source.SourceContext;
import com.originlore.gameplay.StructureItems;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.structure.EndCityGenerator;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class MobEquipmentGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void endShipFrameGeneratesOnceAndPreservesPlayerItems(TestContext context) {
        var config = Originlore.getConfig();
        var original = config.snapshot();
        try {
            ItemEntry elytra = new ItemEntry();
            elytra.itemId = "minecraft:elytra";
            SourceRule source = new SourceRule();
            source.type = "ENTITY_DROP";
            Variant variant = new Variant();
            variant.id = "recovered";
            variant.weight = 1;
            variant.rule.maxDamageRange = new int[]{300, 400};
            source.variants.add(variant);
            elytra.sources.add(source);
            config.setItemConfig(elytra.itemId, elytra);
            check(config.save().success(), "frame quality config save failed");
            checkEndShipFrame(context);
        } finally {
            check(config.replaceSnapshot(original, config.getRevision()).success(), "frame config restore failed");
        }
        context.complete();
    }

    private static void checkEndShipFrame(TestContext context) {
        BlockPos pos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        var world = context.getWorld();
        new EndCityGenerator.Piece(world.getStructureTemplateManager(), "ship", pos, BlockRotation.NONE, true) {
            void generate() { handleMetadata("Elytra", pos, world, world.getRandom(), new BlockBox(pos)); }
        }.generate();
        var frames = world.getEntitiesByClass(ItemFrameEntity.class, new net.minecraft.util.math.Box(pos).expand(2),
                frame -> frame.getHeldItemStack().isOf(Items.ELYTRA));
        check(frames.size() == 1, "end ship metadata did not create one frame");
        ItemFrameEntity frame = frames.getFirst();
        try {
            NbtCompound identity = metadata(frame.getHeldItemStack());
            check(identity.getString("source_type").equals("ENTITY_DROP")
                            && !identity.getString("variant_id").isEmpty(), "end ship elytra missed its quality pool");
            NbtCompound saved = new NbtCompound();
            frame.writeCustomDataToNbt(saved);
            frame.readCustomDataFromNbt(saved);
            Originlore.getRefreshService().onEntityLoad(frame);
            check(identity.equals(metadata(frame.getHeldItemStack())), "frame reload rerolled the elytra");
            frame.onBreak(null);
            var drops = world.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(pos).expand(2),
                    entity -> entity.getStack().isOf(Items.ELYTRA));
            check(drops.size() == 1 && identity.equals(metadata(drops.getFirst().getStack())), "frame drop rerolled or lost elytra");
            drops.forEach(ItemEntity::discard);

            ItemStack commandItem = new ItemStack(Items.ELYTRA);
            Originlore.applyCustomComponents(commandItem, SourceContext.command());
            frame.setHeldItemStack(commandItem);
            StructureItems.refresh(frame);
            check(metadata(commandItem).equals(metadata(frame.getHeldItemStack())), "player frame reclassified command elytra");

            ItemFrameEntity pending = new ItemFrameEntity(world, pos, Direction.SOUTH);
            pending.setHeldItemStack(StructureItems.markEndShip(new ItemStack(Items.ELYTRA)));
            NbtCompound pendingNbt = new NbtCompound();
            pending.writeCustomDataToNbt(pendingNbt);
            frame.readCustomDataFromNbt(pendingNbt);
            StructureItems.refresh(frame);
            check(metadata(frame.getHeldItemStack()).getString("source_type").equals("ENTITY_DROP"),
                    "serialized generation marker lost the elytra source");
        } finally {
            frame.discard();
        }
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void naturalEquipmentSurvivesGenerationSaveReloadAndDrop(TestContext context) {
        ItemComponentConfig config = Originlore.getConfig();
        var original = config.snapshot();
        SkeletonEntity loaded = null;
        try {
            ItemEntry bow = new ItemEntry();
            bow.itemId = "minecraft:bow";
            SourceRule source = new SourceRule();
            source.type = "ENTITY_DROP";
            Variant variant = new Variant();
            variant.id = "spawned";
            variant.weight = 1;
            variant.rule.maxDamageRange = new int[]{150, 200};
            source.variants.add(variant);
            bow.sources.add(source);
            config.setItemConfig(bow.itemId, bow);
            check(config.save().success(), "equipment config save failed");

            SkeletonEntity generated = skeleton(context, SpawnReason.CHUNK_GENERATION);
            NbtCompound pending = new NbtCompound();
            generated.writeCustomDataToNbt(pending);
            check(pending.getBoolean("originlore_pending_equipment"), "chunk generation lost its pending provenance");
            loaded = new SkeletonEntity(EntityType.SKELETON, context.getWorld());
            loaded.readCustomDataFromNbt(pending);
            loaded.setPosition(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(1, 2, 1)).toCenterPos());
            loaded.setAiDisabled(true);
            check(context.getWorld().spawnEntity(loaded), "equipment test entity failed to spawn");
            ItemStack equipped = loaded.getMainHandStack();
            NbtCompound identity = metadata(equipped);
            check(identity.getString("source_type").equals("ENTITY_DROP")
                            && identity.getString("source_id").equals("minecraft:skeleton")
                            && identity.getString("variant_id").equals("spawned"),
                    "natural bow did not receive its entity quality");
            NbtCompound saved = new NbtCompound();
            loaded.writeCustomDataToNbt(saved);
            check(!saved.contains("originlore_pending_equipment"), "completed generation retained the pending marker");
            loaded.readCustomDataFromNbt(saved);
            Originlore.getRefreshService().onEntityLoad(loaded);
            check(identity.equals(metadata(loaded.getMainHandStack())), "reload rerolled equipment identity");
            loaded.setEquipmentDropChance(EquipmentSlot.MAINHAND, 2);
            loaded.dropAllEquipment();
            var drops = context.getWorld().getEntitiesByClass(ItemEntity.class, loaded.getBoundingBox().expand(1),
                    entity -> entity.getStack().isOf(Items.BOW) && identity.equals(metadata(entity.getStack())));
            check(drops.size() == 1 && loaded.getMainHandStack().isEmpty(), "equipment drop lost or rerolled the bow");
            drops.forEach(ItemEntity::discard);
        } finally {
            if (loaded != null) loaded.discard();
            check(config.replaceSnapshot(original, config.getRevision()).success(), "equipment config restore failed");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void commandUnknownAndPlayerEquipmentKeepTheirIdentity(TestContext context) {
        for (SpawnReason reason : new SpawnReason[]{SpawnReason.COMMAND, SpawnReason.SPAWN_EGG, SpawnReason.DISPENSER}) {
            SkeletonEntity mob = skeleton(context, reason);
            Originlore.getRefreshService().onEntityLoad(mob);
            Originlore.applyCustomComponents(mob.getMainHandStack());
            check(!metadata(mob.getMainHandStack()).getString("source_type").equals("ENTITY_DROP"),
                    "creative or command equipment acquired a natural quality");
            Originlore.getRefreshService().onEntityUnload(mob);
        }
        SkeletonEntity mob = skeleton(context, SpawnReason.NATURAL);
        ItemStack playerBow = new ItemStack(Items.BOW);
        Originlore.applyCustomComponents(playerBow, SourceContext.command());
        mob.equipStack(EquipmentSlot.MAINHAND, playerBow);
        NbtCompound before = metadata(playerBow);
        Originlore.getRefreshService().onEntityLoad(mob);
        check(before.equals(metadata(playerBow)), "player-provided equipment was reclassified");
        Originlore.getRefreshService().onEntityUnload(mob);
        SkeletonEntity old = new SkeletonEntity(EntityType.SKELETON, context.getWorld());
        old.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        Originlore.getRefreshService().onEntityLoad(old);
        Originlore.applyCustomComponents(old.getMainHandStack());
        check(!metadata(old.getMainHandStack()).getString("source_type").equals("ENTITY_DROP"),
                "old unclassified equipment invented a natural origin");
        Originlore.getRefreshService().onEntityUnload(old);
        context.complete();
    }

    private static SkeletonEntity skeleton(TestContext context, SpawnReason reason) {
        SkeletonEntity mob = new SkeletonEntity(EntityType.SKELETON, context.getWorld());
        mob.initialize(context.getWorld(), context.getWorld().getLocalDifficulty(mob.getBlockPos()), reason, null);
        return mob;
    }

    private static NbtCompound metadata(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt()
                .getCompound(ItemComponentManager.METADATA_KEY);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
