package me.timvinci.terrastorage.util;

import me.timvinci.terrastorage.api.ItemFavoritingUtils;
import me.timvinci.terrastorage.config.ConfigManager;
import me.timvinci.terrastorage.inventory.*;
import me.timvinci.terrastorage.item.StackIdentifier;
import me.timvinci.terrastorage.item.StackProcessor;
import me.timvinci.terrastorage.mixin.DoubleInventoryAccessor;
import me.timvinci.terrastorage.mixin.EntityAccessor;
import me.timvinci.terrastorage.mixin.LockableContainerBlockEntityAccessor;
import me.timvinci.terrastorage.network.NetworkHandler;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Function;

/**
 * Utility class that stores the implementation of the core options provided by Terrastorage.
 */
public class TerrastorageCore {

    /**
     * Attempts to loot all the items from the storage to the player.
     * @param playerInventory The player's inventory.
     * @param storageInventory The storage's inventory.
     * @param hotbarProtection The hotbar protection value of the player.
     */
    public static void lootAll(PlayerInventory playerInventory, Inventory storageInventory, boolean hotbarProtection) {
        // Create an inventory state from the player's inventory.
        CompleteInventoryState playerInventoryState = new CompleteInventoryState(playerInventory, hotbarProtection);

        for (int i = 0; i < storageInventory.size(); i++) {
            ItemStack storageStack = storageInventory.getStack(i);
            if (storageStack.isEmpty()) {
                continue;
            }

            InventoryUtils.transferStack(playerInventory, playerInventoryState, storageStack);
        }

        if (playerInventoryState.wasModified()) {
            playerInventory.markDirty();
            storageInventory.markDirty();
        }
    }

    /**
     * Attempts to deposit all the items from the player to the storage.
     * @param playerInventory The player's inventory.
     * @param storageInventory The storage's inventory.
     * @param firstSlot The first slot of the screen handler of the storage inventory.
     * @param hotbarProtection The hotbar protection value of the player.
     */
    public static void depositAll(PlayerInventory playerInventory, Inventory storageInventory, Slot firstSlot, boolean hotbarProtection) {
        // Create an inventory state from the storage's inventory.
        CompleteInventoryState storageInventoryState = new CompleteInventoryState(storageInventory);

        for (int i = PlayerInventory.getHotbarSize(); i < playerInventory.getMainStacks().size(); i++) {
            ItemStack playerStack = playerInventory.getStack(i);
            if (playerStack.isEmpty() || ItemFavoritingUtils.isFavorite(playerStack) || !firstSlot.canInsert(playerStack)) {
                continue;
            }

            InventoryUtils.transferStack(storageInventory, storageInventoryState, playerStack);
        }

        if (!hotbarProtection) {
            for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
                ItemStack playerStack = playerInventory.getStack(i);

                if (playerStack.isEmpty() || ItemFavoritingUtils.isFavorite(playerStack) || !firstSlot.canInsert(playerStack)) {
                    continue;
                }

                InventoryUtils.transferStack(storageInventory, storageInventoryState, playerStack);
            }
        }

        if (storageInventoryState.wasModified()) {
            playerInventory.markDirty();
            storageInventory.markDirty();
        }
    }

    /**
     * Performs a quick stack operation on a storage inventory.
     * @param playerInventory The player's inventory.
     * @param storageInventory The storage's inventory.
     * @param hotbarProtection The hotbar protection value of the player.
     * @param smartDepositMode Whether the player's quick stack mode is 'smart deposit'.
     */
    public static void quickStack(PlayerInventory playerInventory, Inventory storageInventory, boolean hotbarProtection, boolean smartDepositMode) {
        InventoryState storageInventoryState = smartDepositMode ?
                new ExpandedInventoryState(storageInventory) :
                new CompactInventoryState(storageInventory);

        StackProcessor processor = InventoryUtils.createStackProcessor(storageInventoryState, storageInventory, smartDepositMode);

        int startIndex = hotbarProtection ? PlayerInventory.getHotbarSize() : 0;
        for (int i = startIndex; i < playerInventory.getMainStacks().size(); i++) {
            processor.tryProcess(playerInventory.getStack(i));
        }

        if (storageInventoryState.wasModified()) {
            playerInventory.markDirty();
            storageInventory.markDirty();
        }
    }

    /**
     * Attempts to loot all the items of the storage that can stack with existing items of the player, from the storage
     * to the player.
     * @param playerInventory The player's inventory.
     * @param storageInventory The storage's inventory.
     * @param hotbarProtection The hotbar protection value of the player.
     */
    public static void restock(PlayerInventory playerInventory, Inventory storageInventory, boolean hotbarProtection) {
        // Create an inventory state from the player's inventory.
        CompactInventoryState playerInventoryState = new CompactInventoryState(playerInventory, hotbarProtection);

        for (int i = 0; i < storageInventory.size(); i++) {
            ItemStack storageStack = storageInventory.getStack(i);
            if (storageStack.isEmpty() || !playerInventoryState.getNonFullItemSlots().containsKey(new StackIdentifier(storageStack))) {
                continue;
            }

            InventoryUtils.transferToExistingStack(playerInventory, playerInventoryState, storageStack);
        }

        if (playerInventoryState.wasModified()) {
            playerInventory.markDirty();
            storageInventory.markDirty();
        }
    }

    /**
     * Sorts the items of a storage.
     * @param storageInventory The storage's inventory.
     * @param type The sorting type of the player.
     */
    public static void sortStorageItems(Inventory storageInventory, SortType type) {
        List<ItemStack> sortedStacks = InventoryUtils.combineAndSortInventory(storageInventory, type, 0, storageInventory.size(), false);

        int slotIndex = 0;
        for (ItemStack stack : sortedStacks) {
            storageInventory.setStack(slotIndex++, stack);
        }

        storageInventory.markDirty();
    }

    /**
     * Handles the renaming of an entity or block entity that the player is interacting with.
     * Updates the name of the entity or block entity and sends the new name to all players tracking it.
     * Also reopens the screen for the player who initiated the rename action.
     * @param player The player initiating the rename action.
     * @param newName The new name to apply to the entity or block entity. If empty, the name will be reset to default.
     */
    public static void renameStorage(ServerPlayerEntity player, String newName) {
        Text newCustomName = newName.isEmpty() ? null : Text.literal(newName);
        NamedScreenHandlerFactory factory;
        Inventory containerInventory = player.currentScreenHandler.slots.getFirst().inventory;
        if (containerInventory instanceof VehicleInventory vehicleInventory) {
            Entity entity = (Entity) vehicleInventory;
            if (newName.equals(((EntityAccessor)entity).invokeGetDefaultName().getString())) {
                newCustomName = null;
            }

            entity.setCustomName(newCustomName);
            factory = (NamedScreenHandlerFactory) entity;
        }
        else if (containerInventory instanceof DoubleInventoryAccessor accessor) {
            if (accessor.first() instanceof LockableContainerBlockEntity firstPart &&
                    accessor.second() instanceof LockableContainerBlockEntity secondPart) {

                String containerName = ((LockableContainerBlockEntityAccessor)firstPart).invokeGetContainerName().getString();
                String doubleContainerName = Text.translatable("container.chestDouble").getString().replace(Text.translatable("container.chest").getString(), containerName);
                if (newName.equals(doubleContainerName)) {
                    newCustomName = null;
                }

                ((LockableContainerBlockEntityAccessor) firstPart).setCustomName(newCustomName);
                ((LockableContainerBlockEntityAccessor) secondPart).setCustomName(newCustomName);

                firstPart.markDirty();
                secondPart.markDirty();

                NetworkHandler.sendGlobalBlockRenamedPayload(player.getWorld(), firstPart.getPos(), newCustomName == null ? "" : newCustomName.getString());
                NetworkHandler.sendGlobalBlockRenamedPayload(player.getWorld(), secondPart.getPos(), newCustomName == null ? "" : newCustomName.getString());
                factory = firstPart.getCachedState().createScreenHandlerFactory(player.getWorld(), firstPart.getPos());
            }
            else {
                player.sendMessage(Text.literal("The storage you tried to rename is currently unsupported by Terrastorage."));
                return;
            }
        }
        else if (containerInventory instanceof LockableContainerBlockEntity lockableContainerBlockEntity) {
            LockableContainerBlockEntityAccessor accessor = (LockableContainerBlockEntityAccessor) lockableContainerBlockEntity;

            if (newName.equals(accessor.invokeGetContainerName().getString())) {
                newCustomName = null;
            }

            accessor.setCustomName(newCustomName);
            lockableContainerBlockEntity.markDirty();

            NetworkHandler.sendGlobalBlockRenamedPayload(player.getWorld(), lockableContainerBlockEntity.getPos(), newCustomName == null ? "" : newCustomName.getString());
            factory = lockableContainerBlockEntity.getCachedState().createScreenHandlerFactory(player.getWorld(), lockableContainerBlockEntity.getPos());
        }
        else {
            player.sendMessage(Text.literal("The storage you tried to rename is currently unsupported by Terrastorage."));
            return;
        }

        player.closeHandledScreen();
        player.openHandledScreen(factory);
    }

    /**
     * Sorts the items of a player's inventory.
     * @param playerInventory The player's inventory.
     * @param type The sorting type of the player.
     * @param hotbarProtection The hotbar protection value of the player.
     */
    public static void sortPlayerItems(PlayerInventory playerInventory, SortType type, boolean hotbarProtection) {
        List<ItemStack> sortedList = InventoryUtils.combineAndSortInventory(playerInventory, type,
                hotbarProtection ? PlayerInventory.getHotbarSize() : 0,
                playerInventory.getMainStacks().size(), true);
        ArrayDeque<ItemStack> sortedStacks = new ArrayDeque<>(sortedList);

        int slotIndex = PlayerInventory.getHotbarSize();
        while (!sortedStacks.isEmpty() && slotIndex < 36) {
            if (playerInventory.getMainStacks().get(slotIndex).isEmpty()) {
                playerInventory.getMainStacks().set(slotIndex, sortedStacks.pollFirst());
            }
            slotIndex++;
        }
        if (!hotbarProtection && !sortedStacks.isEmpty()) {
            slotIndex = 0;
            do {
                if (playerInventory.getMainStacks().get(slotIndex).isEmpty()) {
                    playerInventory.getMainStacks().set(slotIndex, sortedStacks.pollFirst());
                }
                slotIndex++;
            }
            while (!sortedStacks.isEmpty());
        }

        playerInventory.markDirty();
    }

    /**
     * Performs a quick stack operation on all storage nearby the player.
     * @param player The player who initiated the operation.
     * @param hotbarProtection The player's hotbar protection value.
     * @param smartDepositMode Whether the player's quick stack mode is 'smart deposit'.
     */
    public static void quickStackToNearbyStorages(ServerPlayerEntity player, boolean hotbarProtection, boolean smartDepositMode) {
        List<Pair<Inventory, Vec3d>> nearbyStorages = InventoryUtils.getNearbyStorages(player);
        if (nearbyStorages.isEmpty()) {
            return;
        }

        Function<Inventory, InventoryState> stateFactory = InventoryUtils.getInventoryStateFactory(smartDepositMode);
        Map<Vec3d, ArrayList<Item>> animationMap = new HashMap<>();

        PlayerInventory playerInventory = player.getInventory();
        int startIndex = hotbarProtection ? PlayerInventory.getHotbarSize() : 0;
        boolean playerInventoryModified = false;

        for (Pair<Inventory, Vec3d> storagePair : nearbyStorages) {
            Inventory storage = storagePair.getLeft();
            Vec3d storagePos = storagePair.getRight();

            InventoryState storageState = stateFactory.apply(storage);
            StackProcessor processor = InventoryUtils.createStackProcessor(storageState, storage, smartDepositMode);

            for (int i = startIndex; i < playerInventory.getMainStacks().size(); i++) {
                ItemStack playerStack = playerInventory.getStack(i);
                Item playerItem = playerStack.getItem();
                if (processor.tryProcess(playerStack)) {
                    animationMap.computeIfAbsent(storagePos, k -> new ArrayList<>()).add(playerItem);
                }
            }

            if (storageState.wasModified()) {
                storage.markDirty();
                playerInventoryModified = true;
            }
        }

        if (playerInventoryModified) {
            playerInventory.markDirty();
        }


        int itemAnimationLength = ConfigManager.getInstance().getConfig().getItemAnimationLength();
        if (itemAnimationLength != 0) {
            InventoryUtils.triggerFlyOutAnimation(player.getWorld(), player.getEyePos(), itemAnimationLength, animationMap);
        }
    }
}
