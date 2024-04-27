package me.enderkill98.cartographyhelper.mixin.client;

import me.enderkill98.cartographyhelper.CartographyHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.EmptyMapItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(HandledScreen.class)
public abstract class CartographyScreenMixin<T extends ScreenHandler> extends Screen {
	@Shadow @Final protected T handler;
	@Shadow protected int x;
	@Shadow protected int y;
	@Shadow protected int backgroundWidth;
	@Unique private final Logger LOGGER = CartographyHelper.LOGGER;
	@Unique private final static int SLOT_SOURCE = 0;
	@Unique private final static int SLOT_ACTION = 1;
	@Unique private final static int SLOT_TARGET = 2;

	protected CartographyScreenMixin(Text title) {
		super(title);
	}

	@Unique private static int totalCopies = 1; // 1 - 63 (inclusive)
	private ButtonWidget lock;

	private ButtonWidget copiesMinus;
	private ButtonWidget copy;
	private ButtonWidget copiesPlus;

	@Unique private boolean isCartographyTable = false;

	@Unique private boolean preventNextSlotClick = false;

	@Unique private boolean lastCopyHovered = false;
	@Unique private boolean copyingAllMaps = false;
	@Unique private long copyingAllMapsWaitUntil = -1L;
	@Unique private boolean lockingAllMaps = false;


	@Inject(at = @At("TAIL"), method = "render")
	public void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
		if(!isCartographyTable) return;

		// Buttons stay focused after click for some reason
		for(ButtonWidget button : new ButtonWidget[] {  lock, copiesMinus, copy, copiesPlus })
			if (button != null && button.isFocused()) button.setFocused(false);

		if(copy.isHovered() != lastCopyHovered) {
			updateButtonStates();
			lastCopyHovered = copy.isHovered();
		}
	}

	@Inject(at = @At("TAIL"), method = "init")
	public void init(CallbackInfo info) {
		isCartographyTable = handler instanceof CartographyTableScreenHandler;
		if(!isCartographyTable) return;

		HandledScreen<?> screen = (HandledScreen<?>) (Object) this;
		//int titleHeight = 17;
		int btnHeight = 20;
		//int btnGap = 3;

		int minusPlusBtnWidth = 12;
		int copyBtnWidth = 56;
		int lockBtnWidth = 40;

		int lockCopyGap = 5;
		int btnX = x + backgroundWidth - (lockBtnWidth + lockCopyGap + minusPlusBtnWidth + copyBtnWidth + minusPlusBtnWidth) - 2;
		int btnY = y - (20 + 2);
		//int btnY = y + bgHeight + 2;

		lock = ButtonWidget.builder(Text.literal("Lock"), this::onLockPressed)
				.position(btnX, btnY)
				.size(lockBtnWidth, btnHeight)
				.build();
		addDrawableChild(lock);

		copiesMinus = ButtonWidget.builder(Text.literal("-"), this::onCopiesMinusPressed)
				.position(btnX + lockBtnWidth + lockCopyGap, btnY)
				.size(minusPlusBtnWidth, btnHeight)
				.build();
		addDrawableChild(copiesMinus);

		copy = ButtonWidget.builder(Text.literal("Copy " + totalCopies + "x"), this::onCopyPressed)
				.position(btnX + lockBtnWidth + lockCopyGap + minusPlusBtnWidth, btnY)
				.size(copyBtnWidth, btnHeight)
				.build();
		addDrawableChild(copy);

		copiesPlus = ButtonWidget.builder(Text.literal("+"), this::onCopiesPlusPressed)
				.position(btnX + lockBtnWidth + lockCopyGap + minusPlusBtnWidth + copyBtnWidth, btnY)
				.size(minusPlusBtnWidth, btnHeight)
				.build();
		addDrawableChild(copiesPlus);

		updateButtonStates();
		//btnY += btnHeight + btnGap;
	}

	@Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V", at = @At("HEAD"), cancellable = true)
	public void onMouseClick(CallbackInfo info) {
		if(preventNextSlotClick) {
			preventNextSlotClick = false;
			LOGGER.info("Prevented clicking a slot once!");
			info.cancel();
		}
	}

	@Unique private void updateButtonStates() {
		preventNextSlotClick = true; // Prevent throwing items in cursor out of window!
		copiesPlus.active = totalCopies != 63;
		copiesMinus.active = totalCopies != 1;
		int totalCopies = CartographyScreenMixin.totalCopies;
		if(copy.isHovered() && !handler.getSlot(SLOT_ACTION).getStack().isEmpty() && handler.getSlot(SLOT_ACTION).getStack().getItem() instanceof EmptyMapItem)
			totalCopies = Math.min(63, handler.getSlot(SLOT_ACTION).getStack().getCount());

		copy.setMessage(Text.literal("Copy " + totalCopies + "x"));
	}

	@Unique private void onCopiesMinusPressed(ButtonWidget buttonWidget) {
		if(Screen.hasControlDown())
			totalCopies = 1;
		else
			totalCopies = Math.max(1, totalCopies - (Screen.hasShiftDown() ? 10 : 1));
		updateButtonStates();
	}

	@Unique private void onCopiesPlusPressed(ButtonWidget buttonWidget) {
		preventNextSlotClick = true; // Prevent throwing items in cursor out of window!
		if(Screen.hasControlDown())
			totalCopies = 63;
		else
			totalCopies = Math.min(63, totalCopies + (Screen.hasShiftDown() ? 10 : 1));
		updateButtonStates();
	}

	@Unique private void onCopyPressed(ButtonWidget buttonWidget) {
		preventNextSlotClick = true; // Prevent throwing items in cursor out of window!

		if(handler.getCursorStack() != null && !handler.getCursorStack().isEmpty()) {
			// Attempt to just copy item in cursor, by either amount of filled map in action slot
			// or totalCopies count!

			if(handler.getCursorStack().getItem() instanceof FilledMapItem) {
				if(!handler.getSlot(SLOT_SOURCE).getStack().isEmpty()) {
					LOGGER.info("Can't copy map in cursor. Input slots are not empty!");
					return;
				}
				final String cursorStackName = handler.getCursorStack().getName() == null ? null : handler.getCursorStack().getName().getString();

				// Put map down into slot
				client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 1, SlotActionType.PICKUP, client.player);

				// Either use existing maps as copy count or pull needed ones
				int totalCopies;
				if(handler.getSlot(SLOT_ACTION).getStack().isEmpty()) {
					totalCopies = CartographyScreenMixin.totalCopies;
					if(!putEmptyMapsIntoActionSlot(totalCopies)) {
						LOGGER.info("Can't copy maps. Not enough empty maps in inventory.");
						return;
					}
				}else {
					if(!(handler.getSlot(SLOT_ACTION).getStack().getItem() instanceof EmptyMapItem)) {
						LOGGER.info("Can't copy maps. Action slot is not an empty map.");
						return;
					}
					totalCopies = Math.min(63, handler.getSlot(SLOT_ACTION).getStack().getCount());
					totalCopies = Math.min(64 - handler.getCursorStack().getCount(), totalCopies);
				}

				createCopies(totalCopies);

				LOGGER.info("Copied map in cursor (" + cursorStackName + ") " + totalCopies + " times!");
				return;
			} else {
				LOGGER.info("Can't copy maps. Cursor stack is not a filled map.");
				return;
			}
		}

		// Copy all single maps in inventory
		if(!handler.getSlot(SLOT_SOURCE).getStack().isEmpty() || !handler.getSlot(SLOT_ACTION).getStack().isEmpty()) {
			LOGGER.info("Can't copy maps. Input slots are not empty!");
			return;
		}

		copyingAllMaps = true;
		copyingAllMapsWaitUntil = -1L;
		copy.active = false;
		LOGGER.info("Starting to copy all single maps in inventory...");
	}

	@Unique private void onLockPressed(ButtonWidget buttonWidget) {
		preventNextSlotClick = true; // Prevent throwing items in cursor out of window!

		if(handler.getCursorStack() != null && !handler.getCursorStack().isEmpty()) {
			// Attempt to lock the map in cursor!

			if(handler.getCursorStack().getItem() instanceof FilledMapItem && handler.getCursorStack().getCount() == 1) {
				if(!handler.getSlot(SLOT_SOURCE).getStack().isEmpty()) {
					LOGGER.info("Can't lock map in cursor. Input slots are not empty!");
					return;
				}
				MapState mapState = FilledMapItem.getMapState(handler.getCursorStack(), client.world);
				if(mapState != null && mapState.locked) {
					LOGGER.info("The map in cursor is already locked!");
					return;
				}

				final String cursorStackName = handler.getCursorStack().getName() == null ? null : handler.getCursorStack().getName().getString();

				// Put map down into slot
				client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);

				// Either use existing maps as copy count or pull needed ones
				if(!putOneGlasspaneIntoActionSlot()) {
					client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);
					LOGGER.info("Failed to lock map in cursor! No glass panes were found!");
					return;
				}

				client.interactionManager.clickSlot(handler.syncId, SLOT_TARGET, 0, SlotActionType.PICKUP, client.player);
				LOGGER.info("Locked map in cursor (" + cursorStackName + ")!");
				return;
			} else {
				LOGGER.info("Can't lock maps. Cursor stack is not a single filled map.");
				return;
			}
		}

		// Lock all unlocked maps in inventory
		if(!handler.getSlot(SLOT_SOURCE).getStack().isEmpty() || !handler.getSlot(SLOT_ACTION).getStack().isEmpty()) {
			LOGGER.info("Can't lock maps. Input slots are not empty!");
			return;
		}

		lockingAllMaps = true;
		lock.active = false;
		LOGGER.info("Starting to lock all single, unlocked maps in inventory...");
	}

	@Inject(method = "tick", at = @At("TAIL"))
	public void tick(CallbackInfo info) {
		if(copyingAllMaps && (copyingAllMapsWaitUntil == -1L || System.currentTimeMillis() > copyingAllMapsWaitUntil)) {
			int nextSingleMapSlot = -1;
			for(int i = 3; i < handler.slots.size(); i++) {
				Slot slot = handler.slots.get(i);
				ItemStack stack = slot.getStack();
				if(stack.isEmpty() || !(stack.getItem() instanceof FilledMapItem) || stack.getCount() > 1) continue;
				nextSingleMapSlot = i;
				break;
			}

			if(nextSingleMapSlot == -1) {
				LOGGER.info("No single map found in inventory (anymore). Stopping copy process!");
				copy.active = true;
				copyingAllMaps = false;
				return;
			}

			if(handler.getSlot(SLOT_ACTION).getStack().isEmpty() && handler.getSlot(SLOT_SOURCE).getStack().isEmpty()) {
				// Wait for input slots to become empty!
				if(!putEmptyMapsIntoActionSlot(totalCopies)) {
					LOGGER.info("Can't copy next map, due to insufficient empty maps! Stopping copy process.");
					copy.active = true;
					copyingAllMaps = false;
					return;
				}

				String stackName = handler.getSlot(nextSingleMapSlot).getStack().getName() == null ? null : handler.getSlot(nextSingleMapSlot).getStack().getName().getString();

				//client.interactionManager.clickSlot(handler.syncId, nextSingleMapSlot, 0, SlotActionType.QUICK_MOVE, client.player);
				client.interactionManager.clickSlot(handler.syncId, nextSingleMapSlot, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);
				createCopies(totalCopies);
				client.interactionManager.clickSlot(handler.syncId, nextSingleMapSlot, 0, SlotActionType.PICKUP, client.player);
				long waitMs = (int) (1000.0 /(450.0/((double) (totalCopies * 2 + 10))));
				LOGGER.info("Copied map in slot " + nextSingleMapSlot + " (" + stackName + ") " + totalCopies + " times! Waiting at least " + waitMs + " ms before continuing!");
				copyingAllMapsWaitUntil = System.currentTimeMillis() + waitMs; // Try to not surpass rate limits
			}
		}

		if(lockingAllMaps) {
			int nextUnlockedMapSlot = -1;
			for(int i = 3; i < handler.slots.size(); i++) {
				Slot slot = handler.slots.get(i);
				ItemStack stack = slot.getStack();
				if(stack.isEmpty() || !(stack.getItem() instanceof FilledMapItem) || stack.getCount() > 1) continue;
				MapState mapState = FilledMapItem.getMapState(stack, client.world);
				if(mapState == null || mapState.locked) continue;
				nextUnlockedMapSlot = i;
				break;
			}

			if(nextUnlockedMapSlot == -1) {
				LOGGER.info("No single, unlocked map found in inventory (anymore). Stopping locking process!");
				lock.active = true;
				lockingAllMaps = false;
				return;
			}

			if(handler.getSlot(SLOT_ACTION).getStack().isEmpty() && handler.getSlot(SLOT_SOURCE).getStack().isEmpty()) {
				// Wait for input slots to become empty!
				if(!putOneGlasspaneIntoActionSlot()) {
					LOGGER.info("Can't lock next map, due to insufficient glass panes! Stopping locking process.");
					lock.active = true;
					lockingAllMaps = false;
					return;
				}

				String stackName = handler.getSlot(nextUnlockedMapSlot).getStack().getName() == null ? null : handler.getSlot(nextUnlockedMapSlot).getStack().getName().getString();

				client.interactionManager.clickSlot(handler.syncId, nextUnlockedMapSlot, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_TARGET, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, nextUnlockedMapSlot, 0, SlotActionType.PICKUP, client.player);
				LOGGER.info("Locked map in slot " + nextUnlockedMapSlot + " (" + stackName + ")!");
			}
		}

	}

	@Unique private Pair<Integer, ArrayList<Integer>> findEmptyMaps(boolean ignoreTopPart) {
		ArrayList<Integer> matchingSlots = new ArrayList<>();
		int total = 0;

		for(int i = ignoreTopPart ? 3 : 0; i < handler.slots.size(); i++) {
			Slot slot = handler.slots.get(i);
			ItemStack stack = slot.getStack();
			if(!stack.isEmpty() && stack.getItem() instanceof EmptyMapItem) {
				matchingSlots.add(i);
				total += stack.getCount();
			}
		}

		return new Pair<>(total, matchingSlots);
	}

	@Unique private boolean putEmptyMapsIntoActionSlot(int total) {
		Pair<Integer, ArrayList<Integer>> emptyMaps = findEmptyMaps(false);
		if(total > emptyMaps.getLeft()) {
			LOGGER.info("Can't put " + total + " Empty Maps into Action Slot! Only " + emptyMaps.getLeft() + " found!");
			return false;
		}
		if(!handler.getSlot(SLOT_ACTION).getStack().isEmpty()) {
			LOGGER.info("Can't put Empty Maps into Action Slot! Slot is not empty!");
			return false;
		}

		LOGGER.info("total: " + total);
		int transferred = 0;
		while(transferred < total) {
			int remaining = total - transferred;
			emptyMaps = findEmptyMaps(true);

			if(emptyMaps.getRight().isEmpty()) {
				LOGGER.info("Unexpected: Ran out of Empty Maps to transfer!");
				return false;
			}
			int nextEmptyMapSlot = emptyMaps.getRight().get(0);
			int nextEmptyMapCount = handler.getSlot(nextEmptyMapSlot).getStack().getCount();

			if(nextEmptyMapCount < remaining) {
				LOGGER.info("1 (slot " + nextEmptyMapSlot + " has x" + nextEmptyMapCount + ")");
				client.interactionManager.clickSlot(handler.syncId, nextEmptyMapSlot, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_ACTION, 0, SlotActionType.PICKUP, client.player);
				transferred += nextEmptyMapCount;
			}else {
				int possible = Math.min(nextEmptyMapCount, remaining);
				LOGGER.info("2: " + possible + " (slot " + nextEmptyMapSlot + " has x" + nextEmptyMapCount + ")");
				client.interactionManager.clickSlot(handler.syncId, nextEmptyMapSlot, 0, SlotActionType.PICKUP, client.player);
				for(int i = 0; i < possible; i++)
					client.interactionManager.clickSlot(handler.syncId, SLOT_ACTION, 1, SlotActionType.PICKUP, client.player);
				if(nextEmptyMapCount >= remaining)
					client.interactionManager.clickSlot(handler.syncId, nextEmptyMapSlot, 0, SlotActionType.PICKUP, client.player);
				transferred += possible;
			}

			LOGGER.info("transferred: " + transferred);
		}

		if(transferred != total) {
			LOGGER.info("Unexpected: Transferred " + transferred + " Empty Maps, but expected " + total);
			return false;
		}
		return true;
	}

	@Unique private boolean putOneGlasspaneIntoActionSlot() {
		if(!handler.getSlot(SLOT_ACTION).getStack().isEmpty()) {
			LOGGER.info("Can't put Glass Pane into Action Slot! Slot is not empty!");
			return false;
		}

		for(int slot = 3; slot < handler.slots.size(); slot++) {
			if(handler.getSlot(slot).getStack().isEmpty() || handler.getSlot(slot).getStack().getItem() != Items.GLASS_PANE)
				continue;

			if(handler.getSlot(slot).getStack().getCount() > 1) {
				client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_ACTION, 1, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
			}else {
				client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
				client.interactionManager.clickSlot(handler.syncId, SLOT_ACTION, 0, SlotActionType.PICKUP, client.player);
			}
			return true;
		}

		return false;
	}

	// Expects a single map in the source slot and the needed empty maps
	// in the action slot already!
	// Result will be in the cursor
	public void createCopies(int total) {
		for(int i = 0; i < total; i++) {
			client.interactionManager.clickSlot(handler.syncId, SLOT_TARGET, 0, SlotActionType.PICKUP, client.player);
			client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);
		}
		// Put back into cursor
		client.interactionManager.clickSlot(handler.syncId, SLOT_SOURCE, 0, SlotActionType.PICKUP, client.player);
	}

}
